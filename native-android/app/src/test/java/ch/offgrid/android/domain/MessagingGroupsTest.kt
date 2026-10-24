package ch.offgrid.android.domain

import ch.offgrid.android.crypto.FixedKeyUnlockTokenSource
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.data.GroupEntity
import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.KeyState
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.MessageStatus
import ch.offgrid.android.link.int
import ch.offgrid.android.link.obj
import ch.offgrid.android.link.sim.SimFlashStore
import ch.offgrid.android.link.sim.SimTiming
import ch.offgrid.android.link.sim.SimulatedHeltec
import ch.offgrid.android.link.sim.SimulatedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Groups in [Messaging] against the simulated antenna: create and key delivery, joining, receiving, sending and member changes. */
class MessagingGroupsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class Rig(val device: SimulatedHeltec, val antenna: AntennaManager, val messaging: Messaging, val db: FakeDatabase, val incoming: MutableList<MessageEntity>) {
        val groups get() = db.groupDao
        val contacts get() = db.contactDao.rows.value
    }

    private suspend fun rig(vararg peers: String, labRadio: Boolean = true): Rig {
        val device = SimulatedHeltec(SimFlashStore(null), scope, labRadio = labRadio,
            timing = SimTiming(firstTxMs = 20, ackWaitMs = 40, retryMs = 40, retries = 2, resendMs = 150, parkMs = 1_500, groupSyncMs = 80))
        val db = FakeDatabase()
        val settings = Settings(db.settingDao, scope)
        val antenna = AntennaManager(scope, { SimulatedLink(device) }, FixedKeyUnlockTokenSource(OgCrypto.sha256("p".toByteArray())), settings)
        val incoming = mutableListOf<MessageEntity>()
        val messaging = Messaging(db, antenna, scope, settings, onIncoming = { incoming += it })
        antenna.listener = messaging
        antenna.connect()
        withTimeout(10_000) { antenna.state.first { it.ready } }
        peers.forEach { name ->
            antenna.call("pair.begin")
            val contact = antenna.call("pair.complete", buildJsonObject { put("qr", device.addVirtualPeer(name, -90)) }).obj("contact")!!
            messaging.upsertFromDevice(device.deviceIdHex, contact, keepName = false)
        }
        return Rig(device, antenna, messaging, db, incoming)
    }

    private suspend fun <T : Any> eventually(what: String, block: suspend () -> T?): T = withTimeout(10_000) {
        var value = block()
        while (value == null) {
            delay(25)
            value = block()
        }
        value
    }

    private suspend fun Rig.members(groupId: Long): List<GroupMemberEntity> = groups.membersOf(groupId)

    @Test
    fun hellosFeaturesDecideWhetherGroupsAreOffered() {
        val withGroups = buildJsonObject {
            putJsonArray("features") { add(JsonPrimitive("groups")) }
            put("limits", buildJsonObject { put("max_groups", 4); put("max_group_members", 6); put("max_group_text_bytes", 60) })
        }
        assertEquals(GroupSupport(maxGroups = 4, maxMembers = 6, maxTextBytes = 60), GroupSupport.parse(withGroups))
        assertEquals(5, GroupSupport.parse(withGroups)!!.maxOtherMembers)
        assertEquals(8, GroupSupport().maxOtherMembers) // the app caps a group at 8 members plus the owner
        assertNull(GroupSupport.parse(buildJsonObject { put("limits", buildJsonObject { put("max_groups", 8) }) }))
        assertNull(GroupSupport.parse(buildJsonObject { putJsonArray("features") { add(JsonPrimitive("other")) } }))
    }

    @Test
    fun theAntennaStateCarriesTheLimitsFromHello() = runBlocking<Unit> {
        val r = rig()
        val support = r.antenna.state.value.groups
        assertEquals(GroupSupport(maxGroups = 8, maxMembers = 16, maxTextBytes = 83), support)
    }

    @Test
    fun createReadsTheGroupObjectAndDeliversTheKeyToEveryMember() = runBlocking<Unit> {
        val r = rig("Anna", "Luca")
        val id = r.messaging.createGroup("Team", r.contacts.map { it.id }).getOrThrow()

        val g: GroupEntity = r.groups.byId(id)!!
        assertEquals(1, g.deviceGroupId)
        assertEquals(8, g.gid.length)
        assertEquals(1, g.epoch)
        assertTrue(g.owner)
        val members = r.members(id)
        assertEquals(listOf(1, 2), members.map { it.member })
        assertEquals(r.contacts.map { it.id }, members.map { it.contactId })
        assertEquals(r.contacts.map { it.nodeId }, members.map { it.nodeId })

        // One group.sync per member, tracked from msg.status until the ACK.
        eventually("keys delivered") { r.members(id).takeIf { m -> m.all { it.keyState == KeyState.DELIVERED } } }
        assertTrue(r.members(id).all { it.syncRef == null && it.syncSession == null })
    }

    @Test
    fun aFailedKeyCanBeSentAgain() = runBlocking<Unit> {
        val r = rig("Anna", labRadio = false)
        r.device.setPeerInRange(r.contacts.single().deviceContactId, false)
        val id = r.messaging.createGroup("Team", listOf(r.contacts.single().id)).getOrThrow()

        val failed = eventually("sync failed") { r.members(id).single().takeIf { it.keyState == KeyState.FAILED } }
        assertEquals("timeout", failed.syncReason)

        r.device.setPeerInRange(r.contacts.single().deviceContactId, true)
        r.messaging.resendKey(id, failed.member)
        val delivered = eventually("key delivered") { r.members(id).single().takeIf { it.keyState == KeyState.DELIVERED } }
        assertNull(delivered.syncReason)
    }

    @Test
    fun aGroupTextEndsAtSentWithoutAnAck() = runBlocking<Unit> {
        val r = rig("Anna")
        val id = r.messaging.createGroup("Team", listOf(r.contacts.single().id)).getOrThrow()
        r.messaging.sendGroupText(id, "Hallo Gruppe!")

        // msg.status of a group message carries group_id and no `to`; sent is final.
        val sent = eventually("sent") { r.db.messageDao.rows.value.singleOrNull()?.takeIf { it.status == MessageStatus.SENT } }
        assertEquals(id, sent.groupId)
        assertEquals("sent", sent.deviceState)
        assertNull(sent.msgRef)
        assertNull(sent.failReason)
    }

    @Test
    fun aLongGroupTextIsSplitAtTheGroupLimit() = runBlocking<Unit> {
        val r = rig("Anna")
        val id = r.messaging.createGroup("Team", listOf(r.contacts.single().id)).getOrThrow()
        r.messaging.sendGroupText(id, List(30) { "wort$it" }.joinToString(" "))
        val parts = eventually("two parts sent") { r.db.messageDao.rows.value.takeIf { l -> l.size >= 2 && l.all { it.status == MessageStatus.SENT } } }
        assertTrue(parts.all { MessageLimits.utf8Bytes(it.text) <= 83 })
    }

    @Test
    fun groupReceivedWithoutAContactShowsTheNodeId() = runBlocking<Unit> {
        val r = rig("Anna")
        val anna = r.contacts.single()
        val slot = r.device.peerInvites(anna.deviceContactId, "Gipfeltour", others = listOf("ab12"))
        val g = eventually("joined") { r.groups.byDeviceGroup(r.device.deviceIdHex, slot) }

        r.device.memberSays(slot, member = 2, text = "Wer bin ich?")
        val stranger = eventually("text from node") { r.db.messageDao.rows.value.singleOrNull() }
        assertEquals(g.id, stranger.groupId)
        assertNull(stranger.contactId)
        assertNull(stranger.senderContactId)
        assertEquals("AB12", stranger.senderName)
        assertEquals("AB12", stranger.senderNodeId)
        assertEquals("in", stranger.direction)

        r.device.memberSays(slot, member = 0, text = "Hier Anna")
        val known = eventually("text from contact") { r.db.messageDao.rows.value.firstOrNull { it.text == "Hier Anna" } }
        assertEquals(anna.id, known.senderContactId)
        assertEquals("Anna", known.senderName)
        assertEquals(2, r.incoming.size)
    }

    @Test
    fun aJoinedGroupIsCreatedAndFilledFromGroupGet() = runBlocking<Unit> {
        val r = rig("Anna")
        val anna = r.contacts.single()
        val slot = r.device.peerInvites(anna.deviceContactId, "Gipfeltour", others = listOf("AB12"))
        val g = eventually("joined") { r.groups.byDeviceGroup(r.device.deviceIdHex, slot) }
        assertEquals("Gipfeltour", g.name)
        assertFalse(g.owner)
        assertEquals(1, g.epoch)
        val members = r.members(g.id)
        // The owner is member 0, I am member 1 and not listed, the stranger is known by NodeID only.
        assertEquals(listOf(0, 2), members.map { it.member })
        assertEquals(anna.id, members[0].contactId)
        assertNull(members[1].contactId)
        assertEquals("AB12", members[1].nodeId)
        assertTrue(members.all { it.keyState == KeyState.NONE })
    }

    @Test
    fun addingAndRemovingMembersStartsANewEpochAndSyncsEveryoneAgain() = runBlocking<Unit> {
        val r = rig("Anna", "Luca", "Mia")
        val (anna, luca, mia) = r.contacts
        val id = r.messaging.createGroup("Team", listOf(anna.id, luca.id)).getOrThrow()
        eventually("first keys") { r.members(id).takeIf { m -> m.all { it.keyState == KeyState.DELIVERED } } }

        r.messaging.addMembers(id, listOf(mia.id)).getOrThrow()
        assertEquals(2, r.groups.byId(id)!!.epoch)
        assertEquals(listOf(1, 2, 3), r.members(id).map { it.member })
        eventually("keys of epoch 2") { r.members(id).takeIf { m -> m.size == 3 && m.all { it.keyState == KeyState.DELIVERED } } }

        r.messaging.removeMember(id, member = 1).getOrThrow()
        assertEquals(3, r.groups.byId(id)!!.epoch)
        assertEquals(listOf(2, 3), r.members(id).map { it.member })
        assertEquals(listOf(luca.id, mia.id), r.members(id).map { it.contactId })
        eventually("keys of epoch 3") { r.members(id).takeIf { m -> m.all { it.keyState == KeyState.DELIVERED } } }
        assertNotNull(r.groups.byId(id))
    }

    @Test
    fun deletingAGroupFreesItsSlotAndHistory() = runBlocking<Unit> {
        val r = rig("Anna")
        val id = r.messaging.createGroup("Team", listOf(r.contacts.single().id)).getOrThrow()
        r.messaging.sendGroupText(id, "Hoi")
        eventually("sent") { r.db.messageDao.rows.value.singleOrNull()?.takeIf { it.status == MessageStatus.SENT } }

        r.messaging.deleteGroup(id).getOrThrow()
        assertNull(r.groups.byId(id))
        assertTrue(r.members(id).isEmpty())
        assertTrue(r.db.messageDao.rows.value.isEmpty())
        val next = r.antenna.call("group.create", buildJsonObject {
            put("name", "Neu"); putJsonArray("members") { add(JsonPrimitive(r.contacts.single().deviceContactId)) }
        })
        assertEquals(1, next.obj("group")!!.int("group_id"))
    }

    @Test
    fun aGroupRemovedFromTheAntennaKeepsItsHistoryButLosesItsSlotOnReconnect() = runBlocking<Unit> {
        val r = rig("Anna")
        val id = r.messaging.createGroup("Team", listOf(r.contacts.single().id)).getOrThrow()
        r.antenna.call("group.delete", buildJsonObject { put("group_id", 1); put("confirm", true) })
        r.messaging.onReady(r.antenna, r.device.deviceIdHex)
        assertEquals(Messaging.NO_GROUP, r.groups.byId(id)!!.deviceGroupId)
    }
}
