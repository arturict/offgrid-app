package ch.offgrid.android.domain

import ch.offgrid.android.crypto.FixedKeyUnlockTokenSource
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.data.SettingDao
import ch.offgrid.android.data.SettingEntity
import ch.offgrid.android.link.Incoming
import ch.offgrid.android.link.int
import ch.offgrid.android.link.sim.SimFlashStore
import ch.offgrid.android.link.sim.SimulatedHeltec
import ch.offgrid.android.link.sim.SimulatedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's antenna session against the simulated lab.3 antenna: provisioning, wrong phone, rate limit,
 * auto-lock and the authenticated factory reset. Real hardware is not involved.
 */
class AntennaSecurityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flashFile = File.createTempFile("sim-heltec", ".json").also { it.delete() }
    private val device = SimulatedHeltec(SimFlashStore(flashFile), scope)
    private val link = SimulatedLink(device)
    private val readyCount = Channel<String>(Channel.UNLIMITED)

    @After
    fun tearDown() {
        scope.cancel()
        flashFile.delete()
    }

    private fun manager(phone: String, settings: Settings = Settings(MemorySettingDao(), scope)) =
        AntennaManager(scope, { link }, FixedKeyUnlockTokenSource(OgCrypto.sha256(phone.toByteArray())), settings).apply {
            listener = object : AntennaListener {
                override suspend fun onReady(antenna: AntennaManager, deviceId: String) { readyCount.trySend(deviceId) }
                override suspend fun onEvent(event: Incoming.Event) {}
                override suspend fun onDeviceRebooted() {}
            }
        }

    private suspend fun AntennaManager.settle(): AntennaState = withTimeout(15_000) {
        state.first { it.phase == AntennaPhase.READY || it.phase == AntennaPhase.ERROR }
    }

    private suspend fun AntennaManager.reconnect(): AntennaState {
        disconnect()
        connect()
        return settle()
    }

    @Test
    fun anotherPhoneSeesUnlockFailedThenRateLimited() = runBlocking {
        val owner = manager("phone-a")
        owner.connect()
        assertEquals(AntennaPhase.READY, owner.settle().phase)
        owner.disconnect()
        device.powerCycle() // locked again, as after unplugging it

        val other = manager("phone-b")
        other.connect()
        assertEquals(AntennaError.UNLOCK_FAILED, other.settle().error)
        assertEquals(AntennaError.UNLOCK_FAILED, other.reconnect().error)
        assertEquals(AntennaError.UNLOCK_FAILED, other.reconnect().error)
        // Three failures: the antenna waits 2 s before it checks a token again (Security.cpp backoff).
        with(other.reconnect()) {
            assertEquals(AntennaError.RATE_LIMITED, error)
            assertEquals("E_RATE_LIMITED", errorCode)
        }
    }

    @Test
    fun rateLimitCarriesTheEstimatedEndAndRetriesOnItsOwn() = runBlocking {
        val owner = manager("phone-a")
        owner.connect()
        owner.settle()
        owner.disconnect()
        device.powerCycle()

        val other = manager("phone-b")
        other.connect()
        other.settle()
        other.reconnect()
        other.reconnect()
        val limited = other.reconnect()
        assertEquals(AntennaError.RATE_LIMITED, limited.error)
        // Third failure: 2 s backoff, counted from that failure.
        val retryAt = limited.retryAt!!
        assertTrue(retryAt - System.currentTimeMillis() in 1..2_000)
        // When it ends the session tries again by itself and meets the wrong token, not the rate limit.
        val again = withTimeout(6_000) { other.state.first { it.phase == AntennaPhase.ERROR && it.error != AntennaError.RATE_LIMITED } }
        assertEquals(AntennaError.UNLOCK_FAILED, again.error)
        assertNull(again.retryAt)
    }

    @Test
    fun backoffEstimateFollowsSecurityFailure() {
        assertEquals(0, AuthBackoff.delayAfter(2))
        assertEquals(2_000, AuthBackoff.delayAfter(3))
        assertEquals(16_000, AuthBackoff.delayAfter(6))
        assertEquals(60_000, AuthBackoff.delayAfter(8))
        assertEquals(60_000, AuthBackoff.delayAfter(30))
        // Own failures known: from the last failure. Unknown (another phone failed): 2 s, then doubling.
        assertEquals(1_500L + 4_000, AuthBackoff.retryAt(now = 2_000, failures = 4, lastFailureAt = 1_500, rateLimited = 1))
        assertEquals(10_000L + 2_000, AuthBackoff.retryAt(now = 10_000, failures = 0, lastFailureAt = 0, rateLimited = 1))
        assertEquals(10_000L + 8_000, AuthBackoff.retryAt(now = 10_000, failures = 0, lastFailureAt = 0, rateLimited = 3))
        assertEquals(10_000L + 60_000, AuthBackoff.retryAt(now = 10_000, failures = 0, lastFailureAt = 0, rateLimited = 9))
    }

    @Test
    fun autoLockIsReopenedAndTheReasonKeptAsLastLock() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        m.settle()
        readyCount.receive()
        m.call("sec.lock")
        withTimeout(5_000) { readyCount.receive() }
        val state = m.state.value
        assertEquals(AntennaPhase.READY, state.phase)
        assertNull(state.lockReason)
        assertEquals("lock", state.lastLock?.reason)
        assertEquals(2, state.unlockCount)
    }

    @Test
    fun aRestartIsNoticedAndTheVaultOpenedAgain() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        m.settle()
        readyCount.receive()
        device.powerCycle()
        withTimeout(5_000) { readyCount.receive() }
        assertEquals(AntennaPhase.READY, m.state.value.phase)
        assertEquals(AntennaManager.REASON_RESTART, m.state.value.lastLock?.reason)
    }

    @Test
    fun provisioningReportsDeviceStateRadioMeshAndLab() = runBlocking {
        val m = manager("phone-a")
        val seen = mutableListOf<AntennaActivity>()
        val watcher = scope.launch { m.state.collect { s -> s.activity?.let { if (seen.lastOrNull() != it) seen += it } } }
        m.connect()
        val s = m.settle()
        watcher.cancel()
        assertEquals(AntennaPhase.READY, s.phase)
        assertTrue(AntennaActivity.PROVISIONING in seen)
        assertEquals("unlocked", s.deviceState)
        assertEquals(true, s.radioRunning)
        assertEquals("ready", s.vaultStatus)
        assertEquals(15, s.lockTimeoutS)
        assertEquals(3, s.mesh?.hopDefault)
        assertTrue(s.labRadio)
        assertEquals(s.nodeId, s.lab?.nodeId)
        assertEquals(emptyList<String>(), s.lab?.ignoreNodes)
        assertEquals(869.525, s.freqMhz!!, 0.0001)
    }

    @Test
    fun teamFirmwareHasNoLabSettings() = runBlocking {
        val team = SimulatedHeltec(SimFlashStore(null), scope, labRadio = false)
        val m = AntennaManager(scope, { SimulatedLink(team) }, FixedKeyUnlockTokenSource(OgCrypto.sha256("p".toByteArray())),
            Settings(MemorySettingDao(), scope)).apply { listener = NoopListener }
        m.connect()
        val s = m.settle()
        assertEquals(AntennaPhase.READY, s.phase)
        assertFalse(s.labRadio)
        assertNull(s.lab)
        assertEquals(SimulatedHeltec.TEAM_FW_VERSION, s.fwVersion)
        assertEquals(MessageLimits.CODEC_RAW, s.textCodec)
    }

    @Test
    fun aMockImageIsRefused() = runBlocking {
        device.mockImage = true
        val m = manager("phone-a")
        m.connect()
        assertEquals(AntennaError.MOCK_FIRMWARE, m.settle().error)
    }

    @Test
    fun aHungAntennaEndsTheSessionAsUnresponsive() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        m.settle()
        device.hung = true
        val s = withTimeout(15_000) { m.state.first { it.phase == AntennaPhase.ERROR } }
        assertEquals(AntennaError.UNRESPONSIVE, s.error)
    }

    @Test
    fun meshAndLabSettingsAreWrittenAndKept() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        m.settle()
        val mesh = m.setMesh(buildJsonObject { put("hop_default", 1); put("relay_enabled", false) })
        assertEquals(1, mesh.hopDefault)
        assertEquals(false, m.state.value.mesh?.relayEnabled)
        val lab = m.setLab(buildJsonObject { putJsonArray("ignore_nodes") { add(JsonPrimitive("033B")) } })
        assertEquals(listOf("033B"), lab.ignoreNodes)
        assertEquals(listOf("033B"), m.state.value.lab?.ignoreNodes)
        val stats = m.radioStats()
        assertTrue(RadioStats.rows(stats).any { it.first == "Gesendete Pakete" })
    }

    @Test
    fun provisioningAFreshVaultDropsAnOldTransferToken() = runBlocking {
        val settings = Settings(MemorySettingDao(), scope)
        settings.put(Settings.TRANSFER_TOKEN, OgCrypto.b64(ByteArray(32)))
        settings.put(Settings.TRANSFER_DEVICE, device.deviceIdHex)
        val m = manager("phone-a", settings)
        m.connect()
        assertEquals(AntennaPhase.READY, m.settle().phase)
        assertNull(settings.get(Settings.TRANSFER_TOKEN))
        assertNull(settings.get(Settings.TRANSFER_DEVICE))
    }

    @Test
    fun aBackupImportRotatesToTheNewPhoneAndKeepsBothTokensUntilConfirmed() = runBlocking {
        val owner = manager("phone-a")
        owner.connect()
        assertEquals(AntennaPhase.READY, owner.settle().phase)
        owner.call("sec.lock")
        owner.disconnect()
        val deviceBytes = OgCrypto.unhex(device.deviceIdHex)
        val oldToken = FixedKeyUnlockTokenSource(OgCrypto.sha256("phone-a".toByteArray())).token(deviceBytes)
        val settings = Settings(MemorySettingDao(), scope)
        settings.put(Settings.TRANSFER_TOKEN, OgCrypto.b64(oldToken))
        settings.put(Settings.TRANSFER_DEVICE, device.deviceIdHex)
        val newPhone = manager("phone-b", settings)
        newPhone.connect()
        assertEquals(AntennaPhase.READY, newPhone.settle().phase)
        assertNull(settings.get(Settings.TRANSFER_TOKEN))
        // The rotation's answer got lost: the transfer token is still there, but the antenna takes ours already.
        settings.put(Settings.TRANSFER_TOKEN, OgCrypto.b64(oldToken))
        settings.put(Settings.TRANSFER_DEVICE, device.deviceIdHex)
        newPhone.call("sec.lock")
        assertEquals(AntennaPhase.READY, newPhone.reconnect().phase)
        assertNull(settings.get(Settings.TRANSFER_TOKEN))
        // The old phone's token no longer opens it.
        newPhone.call("sec.lock")
        newPhone.disconnect()
        assertEquals(AntennaError.UNLOCK_FAILED, owner.reconnect().error)
    }

    @Test
    fun factoryResetErasesTheAntennaAndSetsItUpAgain() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        val before = m.settle()
        readyCount.receive()
        val qr = device.addVirtualPeer("Anna", -90)
        m.call("pair.begin")
        m.call("pair.complete", buildJsonObject { put("qr", qr) })
        assertEquals(1, m.call("contact.list", buildJsonObject { put("offset", 0); put("limit", 10) }).int("total"))

        assertEquals(ResetResult.DONE, m.factoryReset())
        withTimeout(10_000) { readyCount.receive() }
        val after = m.state.value
        assertEquals(AntennaPhase.READY, after.phase)
        assertEquals(before.deviceId, after.deviceId)
        // New vault, new identity, no contacts.
        assertNotEquals(before.nodeId, after.nodeId)
        assertEquals(0, m.call("contact.list", buildJsonObject { put("offset", 0); put("limit", 10) }).int("total"))
    }

    @Test
    fun onlyTheOwnersTokenResetsTheAntenna() = runBlocking {
        val owner = manager("phone-a")
        owner.connect()
        owner.settle()
        owner.disconnect()
        // The antenna stays unlocked until its heartbeat timeout, so another phone gets a session without a
        // token; the reset still needs the owner's token under the reset label.
        val stranger = manager("phone-b")
        stranger.connect()
        assertEquals(AntennaPhase.READY, stranger.settle().phase)
        assertEquals(ResetResult.WRONG_PHONE, stranger.factoryReset())
        stranger.disconnect()

        // After a backup import the old phone's token is tried as well.
        val settings = Settings(MemorySettingDao(), scope)
        settings.put(Settings.TRANSFER_TOKEN, OgCrypto.b64(owner.tokenFor(device.deviceIdHex)))
        settings.put(Settings.TRANSFER_DEVICE, device.deviceIdHex)
        val heir = manager("phone-c", settings)
        heir.connect()
        assertEquals(AntennaPhase.READY, heir.settle().phase)
        assertEquals(ResetResult.DONE, heir.factoryReset())
        assertNull(settings.get(Settings.TRANSFER_TOKEN))
    }

    @Test
    fun awaitSetUpReturnsOnceTheResetAntennaIsReadyAgain() = runBlocking {
        val m = manager("phone-a")
        m.connect()
        m.settle()
        assertEquals(ResetResult.DONE, m.factoryReset())
        assertTrue(m.awaitSetUp())
        assertEquals(AntennaPhase.READY, m.state.value.phase)
    }

    @Test
    fun refusedResetsLeaveContactsOnTheAntenna() = runBlocking {
        val owner = manager("phone-a")
        owner.connect()
        owner.settle()
        val qr = device.addVirtualPeer("Anna", -90)
        owner.call("pair.begin")
        owner.call("pair.complete", buildJsonObject { put("qr", qr) })
        val nodeId = owner.state.value.nodeId
        owner.disconnect()

        // Another phone is refused, and its failed attempts end in the antenna's rate limit.
        val stranger = manager("phone-b")
        stranger.connect()
        stranger.settle()
        assertEquals(ResetResult.WRONG_PHONE, stranger.factoryReset())
        assertEquals(ResetResult.WRONG_PHONE, stranger.factoryReset())
        assertEquals(ResetResult.WRONG_PHONE, stranger.factoryReset())
        assertEquals(ResetResult.RATE_LIMITED, stranger.factoryReset())
        stranger.disconnect()

        device.powerCycle()
        val back = manager("phone-a")
        back.connect()
        // The backoff may still run for a moment; the session retries on its own.
        withTimeout(30_000) { back.state.first { it.phase == AntennaPhase.READY } }
        assertEquals(nodeId, back.state.value.nodeId)
        assertEquals(1, back.call("contact.list", buildJsonObject { put("offset", 0); put("limit", 10) }).int("total"))
    }

    @Test
    fun withoutAnAntennaSessionNothingIsReset() = runBlocking {
        assertEquals(ResetResult.NOT_CONNECTED, manager("phone-a").factoryReset())
    }

    private object NoopListener : AntennaListener {
        override suspend fun onReady(antenna: AntennaManager, deviceId: String) {}
        override suspend fun onEvent(event: Incoming.Event) {}
        override suspend fun onDeviceRebooted() {}
    }

    private class MemorySettingDao : SettingDao {
        private val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun observeAll(): Flow<List<SettingEntity>> = map.map { m -> m.map { SettingEntity(it.key, it.value) } }
        override suspend fun get(key: String): String? = map.value[key]
        override suspend fun put(setting: SettingEntity) { map.value = map.value + (setting.key to setting.value) }
        override suspend fun remove(key: String) { map.value = map.value - key }
    }
}
