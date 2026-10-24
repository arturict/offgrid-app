package ch.offgrid.android.domain

import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaResetTest {
    private val device = "A4CF12F00D42"
    private val other = "B8D61A00FF01"

    private fun contact(id: Long, deviceId: String = device, slot: Int = id.toInt(), state: String = "active") =
        ContactEntity(id = id, deviceId = deviceId, deviceContactId = slot, displayName = "C$id", nodeId = "0$id", publicKey = "pk$id",
            fingerprint = "fp$id", state = state, lastSeenAt = 5, createdAt = 1)

    private fun out(id: Long, contactId: Long, status: Int, ref: Int? = null, session: String? = null) =
        MessageEntity(id = id, contactId = contactId, direction = "out", kind = "text", text = "m$id", status = status,
            msgRef = ref, refSession = session, sentAt = id)

    @Test
    fun contactsOfTheResetAntennaNeedPairingAgainAndKeepTheirData() {
        val e = AntennaReset.effects(
            listOf(contact(1), contact(2, state = "pending"), contact(3, other)), emptyList(), emptyList(), device,
        )
        assertEquals(listOf(1L, 2L), e.contacts.map { it.id })
        e.contacts.forEach {
            assertEquals(Messaging.UNPAIRED, it.state)
            assertEquals(Messaging.NO_SLOT, it.deviceContactId)
        }
        // Identity and history anchors stay, so pairing the same key later reuses the row.
        with(e.contacts.first()) {
            assertEquals("pk1", publicKey)
            assertEquals("C1", displayName)
            assertEquals(5L, lastSeenAt)
            assertEquals(device, deviceId)
        }
    }

    @Test
    fun contactsAlreadyWithoutASlotAreNotCountedAgain() {
        val e = AntennaReset.effects(listOf(contact(1, slot = Messaging.NO_SLOT, state = Messaging.UNPAIRED)), emptyList(), emptyList(), device)
        assertTrue(e.contacts.isEmpty())
    }

    @Test
    fun openMessagesOfThatAntennaAreLostOthersStay() {
        val e = AntennaReset.effects(
            listOf(contact(1), contact(3, other)),
            listOf(
                out(1, 1, MessageStatus.DEVICE_QUEUED, 100, "$device/s1"),
                out(2, 1, MessageStatus.WAITING, 101, "$device/s1"),
                out(3, 3, MessageStatus.SENT, 102, "$other/s9"),
            ),
            emptyList(), device,
        )
        assertEquals(listOf(1L, 2L), e.messages.map { it.id })
        e.messages.forEach {
            assertEquals(MessageStatus.FAILED, it.status)
            assertEquals(OutboxReconciler.REASON_LOST, it.failReason)
            assertNull(it.msgRef)
        }
    }

    @Test
    fun aSessionThatStartedAfterTheRestartIsKept() {
        val e = AntennaReset.effects(
            listOf(contact(1)), listOf(out(1, 1, MessageStatus.SENT, 100, "$device/new")), emptyList(), device, keepSession = "$device/new",
        )
        assertTrue(e.messages.isEmpty())
    }

    @Test
    fun queuedMessagesForUnpairedContactsFailInsteadOfHanging() {
        val e = AntennaReset.effects(
            listOf(contact(1), contact(3, other)), emptyList(),
            listOf(out(1, 1, MessageStatus.LOCAL_QUEUED), out(2, 3, MessageStatus.LOCAL_QUEUED)), device,
        )
        assertEquals(listOf(1L), e.messages.map { it.id })
        assertEquals("no_contact", e.messages.single().failReason)
        assertEquals(MessageStatus.FAILED, e.messages.single().status)
    }

    @Test
    fun incomingHistoryIsNeverTouched() {
        val incoming = out(1, 1, MessageStatus.DELIVERED).copy(direction = "in")
        val e = AntennaReset.effects(listOf(contact(1)), listOf(incoming), listOf(incoming), device)
        assertTrue(e.messages.isEmpty())
    }
}
