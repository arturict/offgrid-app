package ch.offgrid.android.domain

import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.MessageStatus

/**
 * What a factory reset of one antenna (link spec 11) does to the data on the phone. The antenna loses its
 * identity and every contact key, so the contacts that lived on it can no longer be written to until both sides
 * scan their codes again. Chats stay: the rows are only marked, nothing is deleted and nothing is sent.
 */
object AntennaReset {
    /** Changed rows only; the caller writes them. */
    data class Effects(val contacts: List<ContactEntity>, val messages: List<MessageEntity>)

    /** The contact as the app shows it after the antenna forgot its keys: "Neu koppeln", no slot (see [Messaging.UNPAIRED]). */
    fun ContactEntity.unpaired(): ContactEntity = copy(state = Messaging.UNPAIRED, deviceContactId = Messaging.NO_SLOT)

    /**
     * Settles contacts and outgoing messages of antenna [deviceId] after it accepted the reset. Contacts of other
     * antennas are not touched. Messages the old antenna had accepted but not finished are lost with its queue
     * ([OutboxReconciler.REASON_LOST]); messages still waiting on the phone for an unpaired contact fail as
     * "no_contact" instead of hanging until the next connect. [openOutgoing] and [localQueue] are the open
     * (`msg_ref` set) and the locally queued outgoing messages; rows of session [keepSession] (a session that
     * already started after the restart) are left alone.
     */
    fun effects(
        contacts: List<ContactEntity>,
        openOutgoing: List<MessageEntity>,
        localQueue: List<MessageEntity>,
        deviceId: String,
        keepSession: String? = null,
    ): Effects {
        val changedContacts = contacts.filter { it.deviceId == deviceId && it.deviceContactId != Messaging.NO_SLOT }
        val unpairedIds = changedContacts.map { it.id }.toSet()
        val lost = OutboxReconciler.reconcile(openOutgoing.filter { it.refSession != keepSession }, deviceId, PendingSnapshot.EMPTY, session = "")
        val unsent = localQueue.filter { it.status == MessageStatus.LOCAL_QUEUED && it.contactId in unpairedIds }
            .map { it.copy(status = MessageStatus.FAILED, failReason = "no_contact") }
        return Effects(changedContacts.map { it.unpaired() }, lost + unsent)
    }
}
