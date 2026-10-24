package ch.offgrid.android.domain

import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.KeyState

/**
 * Key delivery of an owned group (link spec 6, "Groups"). `group.sync` is an acknowledged direct message to
 * one member, so a member's [KeyState] follows the same `msg.status` events and the same reconnect rules as a
 * direct message ([OutboxReconciler]): the `msg_ref` is only valid in one link session, and what the antenna
 * lost is "fehlgeschlagen" with a way to send it again, never resent on its own.
 */
object GroupSync {
    /** Back to waiting for the antenna: a new attempt gets a new ref, the old outcome is forgotten. */
    fun requeue(m: GroupMemberEntity): GroupMemberEntity =
        m.copy(keyState = KeyState.QUEUED, syncRef = null, syncSession = null, syncReason = null)

    fun pending(m: GroupMemberEntity, ref: Int, session: String): GroupMemberEntity =
        m.copy(keyState = KeyState.PENDING, syncRef = ref, syncSession = session, syncReason = null)

    fun failed(m: GroupMemberEntity, reason: String): GroupMemberEntity =
        m.copy(keyState = KeyState.FAILED, syncRef = null, syncSession = null, syncReason = reason)

    private fun lost(m: GroupMemberEntity) = failed(m, OutboxReconciler.REASON_LOST)

    /**
     * Applies a `msg.status` state of the sync to [m]. Only the ACK ends it well; `failed` and `cancelled` end it
     * badly, everything else (`queued`, `sent`, `relayed`, `retrying`, `waiting`) keeps it pending. Null when
     * nothing changes.
     */
    fun applyEvent(m: GroupMemberEntity, state: String, reason: String?): GroupMemberEntity? {
        if (m.keyState != KeyState.PENDING) return null
        return when (state) {
            "delivered" -> m.copy(keyState = KeyState.DELIVERED, syncRef = null, syncSession = null, syncReason = null)
            "failed" -> failed(m, reason ?: "failed")
            "cancelled" -> failed(m, "cancelled")
            else -> null
        }
    }

    /**
     * Settles the [open] syncs (accepted by an antenna, no final state) against [snapshot], the `msg.pending`
     * answer at the start of link session [session], like [OutboxReconciler.reconcile]: still queued means the
     * sync moves into the new session, a final outcome in `recent` is applied, anything else was lost. Returns
     * only the rows that changed.
     */
    fun reconcile(open: List<GroupMemberEntity>, snapshot: PendingSnapshot, session: String): List<GroupMemberEntity> {
        val pending = snapshot.pending.associateBy { it.ref }
        val recent = snapshot.recent.associateBy { it.ref }
        return open.mapNotNull { m ->
            val ref = m.syncRef ?: return@mapNotNull null
            val updated = when {
                pending[ref] != null -> pending.getValue(ref).let { applyEvent(m, it.state, it.reason) ?: m.copy(syncSession = session) }
                recent[ref] != null -> recent.getValue(ref).let { applyEvent(m, it.state, it.reason) ?: lost(m) }
                else -> lost(m)
            }
            updated.takeIf { it != m }
        }
    }
}
