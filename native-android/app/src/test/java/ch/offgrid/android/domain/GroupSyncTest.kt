package ch.offgrid.android.domain

import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.KeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupSyncTest {
    private val now = "dev/new"

    private fun member(index: Int, ref: Int?, state: String = KeyState.PENDING, session: String? = "dev/old") =
        GroupMemberEntity(groupId = 1, member = index, contactId = index.toLong(), nodeId = "N$index", keyState = state, syncRef = ref, syncSession = session)

    @Test
    fun onlyTheAckDeliversTheKeyAndFailureEndsIt() {
        val m = member(1, 10)
        assertEquals(KeyState.DELIVERED, GroupSync.applyEvent(m, "delivered", null)!!.keyState)
        assertNull(GroupSync.applyEvent(m, "delivered", null)!!.syncRef)
        with(GroupSync.applyEvent(m, "failed", "timeout")!!) {
            assertEquals(KeyState.FAILED, keyState)
            assertEquals("timeout", syncReason)
        }
        assertEquals("cancelled", GroupSync.applyEvent(m, "cancelled", null)!!.syncReason)
        for (open in listOf("queued", "sent", "relayed", "retrying", "waiting")) assertNull(open, GroupSync.applyEvent(m, open, null))
    }

    @Test
    fun aFinishedKeyIgnoresLateEvents() {
        assertNull(GroupSync.applyEvent(member(1, null, KeyState.DELIVERED), "failed", "timeout"))
        assertNull(GroupSync.applyEvent(member(1, null, KeyState.QUEUED), "delivered", null))
    }

    @Test
    fun reconcileMovesQueuedSyncsAppliesOutcomesAndFailsTheLostOnes() {
        val snapshot = PendingSnapshot(
            pending = listOf(DeviceMessage(10, "sent")),
            recent = listOf(DeviceMessage(11, "delivered"), DeviceMessage(12, "failed", "timeout")),
        )
        val open = listOf(member(1, 10), member(2, 11), member(3, 12), member(4, 13))
        val byIndex = GroupSync.reconcile(open, snapshot, now).associateBy { it.member }
        assertEquals(now, byIndex.getValue(1).syncSession)
        assertEquals(KeyState.PENDING, byIndex.getValue(1).keyState)
        assertEquals(KeyState.DELIVERED, byIndex.getValue(2).keyState)
        assertEquals("timeout", byIndex.getValue(3).syncReason)
        assertEquals(OutboxReconciler.REASON_LOST, byIndex.getValue(4).syncReason)
    }

    @Test
    fun aRestartLosesEveryOpenSyncAndResendingQueuesItAgain() {
        val lost = GroupSync.reconcile(listOf(member(1, 10)), PendingSnapshot.EMPTY, "").single()
        assertEquals(KeyState.FAILED, lost.keyState)
        val again = GroupSync.requeue(lost)
        assertEquals(KeyState.QUEUED, again.keyState)
        assertNull(again.syncReason)
    }

    @Test
    fun backupsWithoutMemberIndexesGetDistinctOnes() {
        fun m(group: Long, index: Int) = GroupMemberEntity(groupId = group, member = index, contactId = index.toLong())
        val old = listOf(m(1, 0), m(1, 0), m(1, 0), m(2, 3), m(2, 4))
        val fixed = BackupService.uniqueMemberIndexes(old)
        assertEquals(listOf(1, 2, 3), fixed.filter { it.groupId == 1L }.map { it.member })
        assertEquals(listOf(3, 4), fixed.filter { it.groupId == 2L }.map { it.member })
    }
}
