package ch.offgrid.android.link.hw

import ch.offgrid.android.crypto.FixedKeyUnlockTokenSource
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.data.SettingDao
import ch.offgrid.android.data.SettingEntity
import ch.offgrid.android.domain.AntennaError
import ch.offgrid.android.domain.AntennaListener
import ch.offgrid.android.domain.AntennaManager
import ch.offgrid.android.domain.AntennaPhase
import ch.offgrid.android.domain.AntennaState
import ch.offgrid.android.domain.Pairing
import ch.offgrid.android.domain.ResetResult
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.link.Incoming
import ch.offgrid.android.link.int
import ch.offgrid.android.link.str
import ch.offgrid.android.link.tcp.TcpLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's own link, security and pairing code against two boards running the team firmware unchanged
 * (`OffGridCH/firmware` `main`, no lab image), behind `bridge.py` as for [RealAntennaTest]:
 *
 * ```
 * OFFGRID_TCP_LINK=127.0.0.1:7755 OFFGRID_TCP_LINK_PEER=127.0.0.1:7756 \
 *   ./gradlew testDebugUnitTest --tests '*TeamFirmwareTest*'
 * ```
 *
 * Both boards must be erased (unprovisioned) or provisioned by this test's token sources. One scenario in a fixed
 * order, because every step leaves the boards in the state the next one needs: provisioning, heartbeat, pairing
 * the way the screens do it (each side leaves and reopens its code before and after scanning), text, location and
 * reaction with the arguments [ch.offgrid.android.domain.Messaging] sends, a failed delivery to a locked peer,
 * relock and unlock, wrong token and backoff, token rotation through a backup transfer, and the factory reset.
 */
class TeamFirmwareTest {
    private val endpointA = System.getenv("OFFGRID_TCP_LINK").orEmpty()
    private val endpointB = System.getenv("OFFGRID_TCP_LINK_PEER").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun log(text: String) = println("[team] $text")

    private class Phone(val label: String, val manager: AntennaManager, val settings: Settings) {
        val events = MutableSharedFlow<Incoming.Event>(replay = 64, extraBufferCapacity = 256)
        val contacts = MutableStateFlow<Map<Long, JsonObject>>(emptyMap())
        val states = MutableStateFlow<Map<Int, String>>(emptyMap())
        val state: AntennaState get() = manager.state.value
    }

    private fun phone(label: String, endpoint: String, key: String, settings: Settings = Settings(MemorySettingDao(), scope)): Phone {
        val link = TcpLink(endpoint.substringBeforeLast(':'), endpoint.substringAfterLast(':').toInt())
        val tokens = FixedKeyUnlockTokenSource(OgCrypto.sha256(key.toByteArray()))
        val manager = AntennaManager(scope, { link }, tokens, settings, log = { log("$label: $it") })
        val p = Phone(label, manager, settings)
        manager.listener = object : AntennaListener {
            override suspend fun onReady(antenna: AntennaManager, deviceId: String) = Unit
            override suspend fun onEvent(event: Incoming.Event) {
                log("$label event ${event.name} ${event.data}")
                if (event.name == "contact.state") {
                    p.states.value = p.states.value + (event.data.int("contact_id")!! to event.data.str("state").orEmpty())
                }
                p.events.emit(event)
            }
            override suspend fun onDeviceRebooted() = log("$label rebooted")
        }
        return p
    }

    private fun store(p: Phone) = object : Pairing.Store {
        override suspend fun save(deviceId: String, contact: JsonObject): Long {
            val id = contact.int("contact_id")!!.toLong()
            p.contacts.value = p.contacts.value + (id to contact)
            return id
        }
        override fun contactState(contactId: Long): Flow<String?> = p.states.map { it[contactId.toInt()] }
    }

    private suspend fun ready(p: Phone, timeoutMs: Long = 30_000): AntennaState =
        withTimeout(timeoutMs) { p.manager.state.first { it.phase == AntennaPhase.READY } }

    private suspend fun event(p: Phone, timeoutMs: Long, match: (Incoming.Event) -> Boolean): Incoming.Event? =
        withTimeoutOrNull(timeoutMs) { p.events.first(match) }

    private suspend fun sendAndDeliver(from: Phone, to: Phone, args: JsonObject, received: (JsonObject) -> Boolean): JsonObject {
        val sent = from.manager.call("msg.send", args)
        val ref = sent.int("msg_ref")
        val got = event(to, 90_000) { it.name == "msg.received" && received(it.data) }
        assertNotNull("${to.label} received ${args.str("type")}", got)
        val done = event(from, 120_000) { it.name == "msg.status" && it.data.int("msg_ref") == ref && it.data.str("state") in FINAL }
        assertEquals("${from.label} ${args.str("type")} delivered", "delivered", done?.data?.str("state"))
        log("${args.str("type")} ${from.label}->${to.label}: rtt ${done?.data?.int("rtt_ms")} ms, rssi ${got!!.data.int("rssi")}")
        return got.data
    }

    @Before
    fun setUp() = assumeTrue("OFFGRID_TCP_LINK and OFFGRID_TCP_LINK_PEER not set", ':' in endpointA && ':' in endpointB)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun appWorksWithTheTeamFirmware() = runBlocking {
        val a = phone("A", endpointA, "team-test-phone-a")
        val b = phone("B", endpointB, "team-test-phone-b")

        // Provision or unlock; the team firmware has no lab extras.
        a.manager.connect(); b.manager.connect()
        for (p in listOf(a, b)) {
            val s = ready(p)
            log("${p.label}: fw ${s.fwVersion} node ${s.nodeId} lab ${s.labRadio}")
            assumeTrue("${p.label} runs a lab image", !s.labRadio)
            assertEquals("1 % pacing", 1, p.manager.radioStats().int("tx_duty_cycle_max_percent"))
        }

        // The app's 1 s heartbeat keeps both open past the 15 s lock timeout.
        delay(20_000)
        for (p in listOf(a, b)) assertEquals("unlocked", p.manager.call("sys.status").str("state"))

        // Pairing as on the screens: show, leave and reopen, scan, reopen again after the scan, then the peer scans.
        for (p in listOf(a, b)) p.manager.call("pair.cancel")
        val pa = Pairing(a.manager, store(a), scope)
        val pb = Pairing(b.manager, store(b), scope)
        pa.begin(); pb.begin()
        val qrA = withTimeout(10_000) { pa.state.first { it.qr != null } }.qr!!
        val qrB = withTimeout(10_000) { pb.state.first { it.qr != null } }.qr!!
        pb.leave(); pb.begin()
        assertEquals("B shows the same code after reopening", qrB, withTimeout(10_000) { pb.state.first { it.qr != null } }.qr)
        pb.complete(qrA, replace = true)
        withTimeout(15_000) { b.contacts.first { it.isNotEmpty() } }
        pa.leave(); pa.begin()
        assertEquals("A shows the same code after reopening", qrA, withTimeout(10_000) { pa.state.first { it.qr != null } }.qr)
        pb.leave(); pb.begin()
        assertEquals("B still shows its code after its own scan", qrB, withTimeout(10_000) { pb.state.first { it.qr != null } }.qr)
        pa.complete(qrB, replace = true)
        withTimeout(15_000) { a.contacts.first { it.isNotEmpty() } }
        val slotAB = a.contacts.value.keys.first().toInt()
        val slotBA = b.contacts.value.keys.first().toInt()
        assertEquals(a.contacts.value.values.first().str("fingerprint"), b.contacts.value.values.first().str("fingerprint"))

        // Text both ways (umlauts and emoji, within 83 bytes), location, reaction: Messaging's arguments.
        val textAB = "Grüezi aus der Hütte 🏔️ – alles ok?"
        val gotB = sendAndDeliver(a, b, buildJsonObject { put("to", slotAB); put("type", "text"); put("text", textAB); put("ack", true) }) {
            it.str("text") == textAB
        }
        assertEquals(0, gotB.int("hops"))
        val textBA = "Ja, bis später! 👍"
        sendAndDeliver(b, a, buildJsonObject { put("to", slotBA); put("type", "text"); put("text", textBA); put("ack", true) }) {
            it.str("text") == textBA
        }
        sendAndDeliver(a, b, buildJsonObject {
            put("to", slotAB); put("type", "geo"); put("lat", 47.0502); put("lon", 8.3093); put("age_s", 30); put("ack", true)
        }) { it.str("type") == "geo" }
        b.manager.call("msg.send", buildJsonObject {
            put("to", slotBA); put("type", "reaction")
            putJsonObject("target") { put("author", "peer"); put("ref", gotB.int("ctr")!! and 0xFFFF) }
            put("emoji", "❤️")
        })
        val reaction = event(a, 60_000) { it.name == "msg.received" && it.data.str("type") == "reaction" }
        assertEquals("❤️", reaction?.data?.str("emoji"))

        // A peer that is locked (radio off on the team firmware) makes a delivery end as failed/timeout.
        b.manager.disconnect()
        delay(17_000)
        val lost = a.manager.call("msg.send", buildJsonObject { put("to", slotAB); put("type", "text"); put("text", "Bist du da?"); put("ack", true) })
        val failed = event(a, 180_000) { it.name == "msg.status" && it.data.int("msg_ref") == lost.int("msg_ref") && it.data.str("state") in FINAL }
        assertEquals("failed", failed?.data?.str("state"))
        assertEquals("timeout", failed?.data?.str("reason"))
        b.manager.connect()
        assertEquals("B unlocks again after the heartbeat lock", AntennaPhase.READY, ready(b).phase)

        // Wrong token: unlock fails, the app counts the failures and backs off instead of hammering the board.
        b.manager.disconnect()
        val intruder = phone("X", endpointB, "some-other-phone")
        intruder.manager.connect()
        val refused = withTimeout(20_000) { intruder.manager.state.first { it.phase == AntennaPhase.ERROR } }
        assertEquals(AntennaError.UNLOCK_FAILED, refused.error)
        intruder.manager.disconnect()
        repeat(3) {
            intruder.manager.connect()
            withTimeout(20_000) { intruder.manager.state.first { it.phase == AntennaPhase.ERROR } }
            log("X attempt ${it + 2}: ${intruder.state.error} ${intruder.state.errorCode} retryAt ${intruder.state.retryAt}")
            intruder.manager.disconnect()
        }
        assertTrue("backoff after repeated failures", intruder.state.error == AntennaError.RATE_LIMITED || intruder.state.retryAt != null)
        delay(65_000) // let the board's backoff run out (60 s at most)

        // Token rotation: a new phone gets B's token through a backup transfer, unlocks once and rotates to its own.
        val transferSettings = Settings(MemorySettingDao(), scope)
        transferSettings.put(Settings.TRANSFER_TOKEN, OgCrypto.b64(b.manager.tokenFor(b.state.deviceId!!)))
        transferSettings.put(Settings.TRANSFER_DEVICE, b.state.deviceId!!)
        val newPhone = phone("B2", endpointB, "team-test-phone-b2", transferSettings)
        newPhone.manager.connect()
        assertEquals("new phone opens B with the transferred token", AntennaPhase.READY, ready(newPhone, 60_000).phase)
        newPhone.manager.disconnect()
        delay(1_000)
        b.manager.connect()
        val oldPhone = withTimeout(30_000) { b.manager.state.first { it.phase == AntennaPhase.ERROR || it.phase == AntennaPhase.READY } }
        assertEquals("the old token no longer opens B", AntennaError.UNLOCK_FAILED, oldPhone.error)
        b.manager.disconnect()

        // Factory reset through the serial API: new identity, no contacts, provisioned again by this phone.
        val nodeBefore = a.state.nodeId
        assertEquals(ResetResult.DONE, a.manager.factoryReset())
        val after = ready(a, 60_000)
        assertNotEquals("new NodeID after the reset", nodeBefore, after.nodeId)
        assertEquals(0, a.manager.call("contact.list", buildJsonObject { put("offset", 0); put("limit", 10) }).int("total"))
        a.manager.disconnect()
    }

    private class MemorySettingDao : SettingDao {
        private val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun observeAll(): Flow<List<SettingEntity>> = map.map { m -> m.map { SettingEntity(it.key, it.value) } }
        override suspend fun get(key: String): String? = map.value[key]
        override suspend fun put(setting: SettingEntity) { map.value = map.value + (setting.key to setting.value) }
        override suspend fun remove(key: String) { map.value = map.value - key }
    }

    private companion object {
        val FINAL = setOf("delivered", "failed", "cancelled")
    }
}
