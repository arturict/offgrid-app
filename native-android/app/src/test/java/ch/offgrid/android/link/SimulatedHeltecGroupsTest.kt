package ch.offgrid.android.link

import ch.offgrid.android.crypto.FixedKeyUnlockTokenSource
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.crypto.TokenWrap
import ch.offgrid.android.link.sim.SimFlashStore
import ch.offgrid.android.link.sim.SimTiming
import ch.offgrid.android.link.sim.SimulatedHeltec
import ch.offgrid.android.link.sim.SimulatedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** The group commands, events and result shapes of the simulated antenna against the firmware doc (link spec 6 and 10). */
class SimulatedHeltecGroupsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = SimulatedHeltec(SimFlashStore(null), scope, timing = SimTiming(firstTxMs = 20, ackWaitMs = 40, retryMs = 40, retries = 2, groupSyncMs = 100))
    private val link = SimulatedLink(device)
    private val tokens = FixedKeyUnlockTokenSource(OgCrypto.sha256("test-phone".toByteArray()))
    private val hello = buildJsonObject { put("app", "offgrid-android"); put("app_proto", "1.1") }

    @After
    fun tearDown() = scope.cancel()

    private class Rig(val client: LinkClient, val helloResult: JsonObject, val contacts: List<JsonObject>)

    /** Provisions the board and pairs [names] with virtual peers; the contacts are returned in pairing order. */
    private suspend fun rig(vararg names: String): Rig {
        val client = LinkClient(link.open(link.find()!!), scope)
        val h = client.call("sys.hello", hello)
        val deviceId = h.str("device_id")!!
        val s = h.obj("session")!!
        val wrapped = TokenWrap.wrap(tokens.token(OgCrypto.unhex(deviceId)), OgCrypto.unhex(deviceId),
            OgCrypto.unb64(s.str("device_eph_pub")!!), OgCrypto.unb64(s.str("nonce")!!))
        client.call("sec.provision", buildJsonObject {
            put("app_eph_pub", OgCrypto.b64(wrapped.appEphPub)); put("wrapped_token", OgCrypto.b64(wrapped.wrapped)); put("name", "HolyG")
        })
        val contacts = names.map { name ->
            val qr = device.addVirtualPeer(name, -90)
            client.call("pair.begin")
            client.call("pair.complete", buildJsonObject { put("qr", qr) }).obj("contact")!!
        }
        return Rig(client, h, contacts)
    }

    private fun slots(contacts: List<JsonObject>) = contacts.map { it.int("contact_id")!! }

    private suspend fun LinkClient.awaitEvent(name: String, match: (JsonObject) -> Boolean = { true }): JsonObject = withTimeout(10_000) {
        var found: JsonObject? = null
        while (found == null) {
            val e = events.receive()
            if (e.name == name && match(e.data)) found = e.data
        }
        found
    }

    private suspend fun LinkClient.create(name: String, ids: List<Int>): JsonObject = call("group.create", buildJsonObject {
        put("name", name)
        putJsonArray("members") { ids.forEach { add(JsonPrimitive(it)) } }
    }).obj("group")!!

    private fun members(ids: List<Int>) = buildJsonObject { putJsonArray("members") { ids.forEach { add(JsonPrimitive(it)) } } }

    @Test
    fun helloListsTheGroupsFeatureAndLimits() = runBlocking {
        val r = rig()
        assertEquals("1.1", r.helloResult.str("proto"))
        assertEquals(listOf("groups"), (r.helloResult["features"] as JsonArray).map { (it as JsonPrimitive).content })
        val limits = r.helloResult.obj("limits")!!
        assertEquals(8, limits.int("max_groups"))
        assertEquals(16, limits.int("max_group_members"))
        assertEquals(83, limits.int("max_group_text_bytes"))
        r.client.close()
    }

    @Test
    fun createReturnsTheGroupObjectWithMembersByNodeId() = runBlocking {
        val r = rig("Anna", "Luca")
        val ids = slots(r.contacts)
        val g = r.client.create("Team", ids)
        assertEquals(1, g.int("group_id"))
        assertEquals(8, g.str("gid")!!.length)
        assertEquals("Team", g.str("name"))
        assertEquals(1, g.int("epoch"))
        assertEquals(true, g.bool("owner"))
        assertTrue(g.isNull("owner_contact_id"))
        assertEquals(0, g.int("member"))
        assertEquals(3, g.int("member_count"))
        val members = (g["members"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(0, 1, 2), members.map { it.int("member") })
        assertTrue(members[0].isNull("contact_id"))
        assertEquals(ids, members.drop(1).map { it.int("contact_id") })
        assertEquals(r.contacts.map { it.str("node_id") }, members.drop(1).map { it.str("node_id") })

        val list = r.client.call("group.list")
        assertEquals(1, list.int("total"))
        assertNull((list["groups"] as JsonArray).first().let { it as JsonObject }["members"])
        val got = r.client.call("group.get", buildJsonObject { put("group_id", 1) }).obj("group")!!
        assertEquals(3, (got["members"] as JsonArray).size)
        r.client.close()
    }

    @Test
    fun syncIsAnAcknowledgedDirectMessageWithToAndGroupId() = runBlocking {
        val r = rig("Anna")
        val slot = slots(r.contacts).single()
        r.client.create("Team", listOf(slot))
        val sent = r.client.call("group.sync", buildJsonObject { put("group_id", 1); put("contact_id", slot) })
        val ref = sent.int("msg_ref")!!
        assertEquals(true, sent.bool("ack_requested"))
        val delivered = r.client.awaitEvent("msg.status") { it.int("msg_ref") == ref && it.str("state") == "delivered" }
        assertEquals(slot, delivered.int("to"))
        assertEquals(1, delivered.int("group_id"))
        r.client.close()
    }

    @Test
    fun sendNeedsATypeRefusesAckAndEndsAtSent() = runBlocking {
        val r = rig("Anna")
        r.client.create("Team", slots(r.contacts))
        val refused = runCatching {
            r.client.call("group.send", buildJsonObject { put("group_id", 1); put("type", "text"); put("text", "Hoi"); put("ack", true) })
        }.exceptionOrNull() as LinkCommandException
        assertEquals("E_INVALID_ARG", refused.error.code)

        val sent = r.client.call("group.send", buildJsonObject { put("group_id", 1); put("type", "text"); put("text", "Hallo Gruppe!") })
        assertEquals(false, sent.bool("ack_requested"))
        val ref = sent.int("msg_ref")!!
        val status = r.client.awaitEvent("msg.status") { it.int("msg_ref") == ref }
        assertEquals("sent", status.str("state"))
        assertEquals(1, status.int("group_id"))
        assertFalse(status.containsKey("to"))
        // Group messages have no receipts: sent is final, the queue is empty.
        assertEquals(0, (r.client.call("msg.pending")["pending"] as JsonArray).size)
        r.client.close()
    }

    @Test
    fun aQuestionInTheGroupIsAnsweredWithGroupReceived() = runBlocking {
        val r = rig("Anna")
        r.client.create("Team", slots(r.contacts))
        r.client.call("group.send", buildJsonObject { put("group_id", 1); put("type", "text"); put("text", "Wer kommt?") })
        val received = r.client.awaitEvent("group.received")
        assertEquals(1, received.int("group_id"))
        assertEquals(1, received.int("member"))
        assertEquals(r.contacts.single().int("contact_id"), received.int("from"))
        assertEquals(r.contacts.single().str("node_id"), received.str("node_id"))
        assertEquals("text", received.str("type"))
        r.client.close()
    }

    @Test
    fun aMemberWithoutContactArrivesWithFromNull() = runBlocking {
        val r = rig("Anna")
        val slot = device.peerInvites(slots(r.contacts).single(), "Gipfeltour", others = listOf("ab12"))
        r.client.awaitEvent("group.state") { it.str("state") == "joined" }
        device.memberSays(slot, member = 2, text = "Wer bin ich?")
        val received = r.client.awaitEvent("group.received")
        assertTrue(received.isNull("from"))
        assertEquals("AB12", received.str("node_id"))
        assertEquals(2, received.int("member"))
        r.client.close()
    }

    @Test
    fun aSimulatedInvitationJoinsAfterTheSyncDelay() = runBlocking {
        val r = rig("Anna")
        val anna = slots(r.contacts).single()
        val slot = device.peerInvites(anna, "Gipfeltour")
        val joined = r.client.awaitEvent("group.state")
        assertEquals(slot, joined.int("group_id"))
        assertEquals("joined", joined.str("state"))
        assertEquals(1, joined.int("epoch"))
        val g = r.client.call("group.get", buildJsonObject { put("group_id", slot) }).obj("group")!!
        assertEquals(false, g.bool("owner"))
        assertEquals(anna, g.int("owner_contact_id"))
        assertEquals("Gipfeltour", g.str("name"))
        // Only the owner changes members and hands out keys.
        val refused = runCatching { r.client.call("group.add", buildJsonObject { put("group_id", slot); put("members", JsonArray(emptyList())) }) }
            .exceptionOrNull() as LinkCommandException
        assertEquals("E_INVALID_ARG", refused.error.code)
        r.client.close()
    }

    @Test
    fun addAndRemoveStartANewEpochAndCancelOldSyncs() = runBlocking {
        val r = rig("Anna", "Luca", "Mia")
        val (anna, luca, mia) = slots(r.contacts)
        r.client.create("Team", listOf(anna, luca))
        val pendingSync = r.client.call("group.sync", buildJsonObject { put("group_id", 1); put("contact_id", anna) }).int("msg_ref")!!

        val added = r.client.call("group.add", buildJsonObject {
            put("group_id", 1)
            putJsonArray("members") { add(JsonPrimitive(mia)) }
        }).obj("group")!!
        assertEquals(2, added.int("epoch"))
        assertEquals(4, added.int("member_count"))
        val cancelled = r.client.awaitEvent("msg.status") { it.int("msg_ref") == pendingSync }
        assertEquals("cancelled", cancelled.str("state"))

        val removed = r.client.call("group.remove", buildJsonObject {
            put("group_id", 1)
            putJsonArray("members") { add(JsonPrimitive(1)) }
        }).obj("group")!!
        assertEquals(3, removed.int("epoch"))
        assertEquals(listOf(0, 2, 3), (removed["members"] as JsonArray).map { (it as JsonObject).int("member") })

        // The owner cannot be removed, and at least two members must stay.
        for (bad in listOf(0, 9)) {
            val e = runCatching {
                r.client.call("group.remove", buildJsonObject { put("group_id", 1); putJsonArray("members") { add(JsonPrimitive(bad)) } })
            }.exceptionOrNull() as LinkCommandException
            assertEquals("E_INVALID_ARG", e.error.code)
        }
        val tooFew = runCatching { r.client.call("group.remove", buildJsonObject { put("group_id", 1); put("members", members(listOf(2, 3))["members"]!!) }) }
            .exceptionOrNull() as LinkCommandException
        assertEquals("E_INVALID_ARG", tooFew.error.code)
        r.client.close()
    }

    @Test
    fun deleteFreesTheSlotAndUnknownGroupsAreRefused() = runBlocking {
        val r = rig("Anna")
        r.client.create("Team", slots(r.contacts))
        assertEquals(true, r.client.call("group.delete", buildJsonObject { put("group_id", 1); put("confirm", true) }).bool("deleted"))
        val gone = runCatching { r.client.call("group.get", buildJsonObject { put("group_id", 1) }) }.exceptionOrNull() as LinkCommandException
        assertEquals("E_NO_SUCH_GROUP", gone.error.code)
        assertNotNull(r.client.create("Neu", slots(r.contacts)))
        r.client.close()
    }
}
