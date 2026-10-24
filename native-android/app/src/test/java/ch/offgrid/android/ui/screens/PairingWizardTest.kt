package ch.offgrid.android.ui.screens

import ch.offgrid.android.crypto.FixedKeyUnlockTokenSource
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.data.SettingDao
import ch.offgrid.android.data.SettingEntity
import ch.offgrid.android.domain.AntennaActivity
import ch.offgrid.android.domain.AntennaListener
import ch.offgrid.android.domain.AntennaManager
import ch.offgrid.android.domain.AntennaPhase
import ch.offgrid.android.domain.AntennaState
import ch.offgrid.android.domain.Pairing
import ch.offgrid.android.domain.PairingError
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.link.Incoming
import ch.offgrid.android.link.int
import ch.offgrid.android.link.sim.SimFlashStore
import ch.offgrid.android.link.sim.SimulatedHeltec
import ch.offgrid.android.link.sim.SimulatedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The pairing wizard against the simulated team firmware: both start orders, the countdown, the error texts,
 * cancelling and reopening. The contact's `active` state is set by hand, as the app's store does it on
 * `contact.state`; time is a fake clock shared by [Pairing] and the wizard.
 */
class PairingWizardTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class Rig(
        val device: SimulatedHeltec,
        val antenna: AntennaManager,
        val pairing: Pairing,
        val store: FakeStore,
        val time: AtomicLong,
    ) {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val discarded = mutableListOf<Long>()
    }

    private fun rig(lab: Boolean = false): Rig {
        val device = SimulatedHeltec(SimFlashStore(null), scope, labRadio = lab)
        val antenna = AntennaManager(scope, { SimulatedLink(device) }, FixedKeyUnlockTokenSource(OgCrypto.sha256("p".toByteArray())),
            Settings(MemorySettingDao(), scope)).apply {
            listener = object : AntennaListener {
                override suspend fun onReady(antenna: AntennaManager, deviceId: String) {}
                override suspend fun onEvent(event: Incoming.Event) {}
                override suspend fun onDeviceRebooted() {}
            }
        }
        val store = FakeStore()
        val time = AtomicLong(1_000_000L)
        return Rig(device, antenna, Pairing(antenna, store, scope, now = time::get), store, time)
    }

    private suspend fun Rig.ready() {
        antenna.connect()
        withTimeout(10_000) { antenna.state.first { it.ready } }
    }

    /** A wizard on its own child scope, like a ViewModel's; [close] ends it as the screen going away would. */
    private inner class Screen(val r: Rig, startWithScan: Boolean = false) {
        private val job = Job(scope.coroutineContext[Job])
        val wizard = PairingWizard(r.pairing, r.antenna.state, CoroutineScope(scope.coroutineContext + job),
            discardContact = { r.discarded += it }, now = r.time::get, ticks = r.ticks, startWithScan = startWithScan)

        suspend fun await(what: String = "state", predicate: (WizardUi) -> Boolean): WizardUi =
            try {
                withTimeout(5_000) { wizard.ui.first(predicate) }
            } catch (e: Exception) {
                throw AssertionError("$what not reached; last: ${wizard.ui.value}", e)
            }

        suspend fun step(step: WizardStep) = await("$step") { it.step == step }

        suspend fun code(): String = await("code") { it.qr != null && it.secondsLeft != null }.qr!!

        suspend fun tick(advanceMs: Long) {
            r.time.addAndGet(advanceMs)
            r.ticks.emit(Unit)
        }

        fun close() {
            wizard.dispose()
            job.cancel()
        }
    }

    private suspend fun Rig.contactIdOf(name: String): Long =
        withTimeout(5_000) { store.states.first { it.isNotEmpty() } }.let { store.idOf(name) }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        withTimeout(5_000) { while (!check()) delay(20) }
        assertTrue(what, check())
    }

    @Test
    fun scanFirstLeadsToShowingOurCodeAndThenToSuccess() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        assertEquals(1, s.step(WizardStep.Intro).stepNumber)

        s.wizard.choose(PairRole.SCAN)
        assertEquals(2, s.step(WizardStep.Half(PairRole.SCAN, second = false)).stepNumber)
        val ours = s.code()
        s.wizard.scan(r.device.addVirtualPeer("Anna", -80, scansBack = false))

        val counter = s.step(WizardStep.Half(PairRole.SHOW, second = true))
        assertEquals(3, counter.stepNumber)
        assertEquals("Anna", counter.peerName)
        assertEquals("the peer needs the code we had while scanning", ours, counter.qr)
        assertNull("our scan is done, the window no longer matters to us", counter.secondsLeft)
        assertEquals(listOf("Code von Anna gescannt", "Anna scannt deinen Code", "Bestätigung per Funk"), counter.waiting!!.map { it.label })

        // The peer scanned our code and its ping arrived: the contact turns active.
        val id = r.contactIdOf("Anna")
        r.store.activate(id)
        assertEquals(WizardStep.Success(id), s.await("success") { it.step is WizardStep.Success }.step)
        assertNull(s.wizard.ui.value.stepNumber)
    }

    @Test
    fun showFirstLeadsToScanningAndWaitsForTheRadio() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SHOW)
        assertEquals(2, s.step(WizardStep.Half(PairRole.SHOW, second = false)).stepNumber)
        assertEquals(300, s.await { it.secondsLeft != null }.secondsLeft)
        assertTrue(s.wizard.ui.value.cancelNeedsConfirm)

        // Nothing goes over the radio before both sides completed: the user says the peer has scanned.
        s.wizard.peerScanned()
        val scan = s.step(WizardStep.Half(PairRole.SCAN, second = true))
        assertEquals(3, scan.stepNumber)
        assertNotNull("our scan is still to come, so the window counts", scan.secondsLeft)

        s.wizard.scan(r.device.addVirtualPeer("Ben", -80, scansBack = false))
        val waiting = s.step(WizardStep.Confirming)
        assertEquals(3, waiting.stepNumber)
        assertEquals("Code gescannt", waiting.waiting!!.first().label)
        assertFalse(waiting.codeRenewed)

        r.store.activate(r.contactIdOf("Ben"))
        s.await("success") { it.step is WizardStep.Success }
    }

    @Test
    fun whileWaitingTheCodeCanBeShownAgainAndAScanCanBeUndone() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SHOW)
        val ours = s.code()
        s.wizard.peerScanned()
        s.wizard.peerNotScannedYet()
        s.step(WizardStep.Half(PairRole.SHOW, second = false))
        s.wizard.peerScanned()
        s.wizard.scan(r.device.addVirtualPeer("Ben", -80, scansBack = false))
        s.step(WizardStep.Confirming)
        s.wizard.showCodeAgain()
        assertEquals(ours, s.step(WizardStep.Half(PairRole.SHOW, second = true)).qr)
    }

    @Test
    fun theRoleCanBeSwitchedInStepTwoButNotAfterOurScan() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SHOW)
        val ours = s.code()
        s.wizard.choose(PairRole.SCAN)
        s.step(WizardStep.Half(PairRole.SCAN, second = false))
        assertEquals("switching keeps the session", ours, s.code())
        s.wizard.scan(r.device.addVirtualPeer("Anna", -80, scansBack = false))
        s.step(WizardStep.Half(PairRole.SHOW, second = true))
        s.wizard.choose(PairRole.SCAN)
        assertEquals(WizardStep.Half(PairRole.SHOW, second = true), s.wizard.ui.value.step)
    }

    @Test
    fun theCountdownFollowsTheSessionAndEndsInExpired() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SHOW)
        val first = s.code()
        s.tick(61_000)
        assertEquals(239, s.await { it.secondsLeft == 239 }.secondsLeft)
        s.tick(239_000)
        val expired = s.step(WizardStep.Expired)
        assertNull(expired.stepNumber)
        assertFalse(expired.cancelNeedsConfirm)

        s.wizard.restart()
        s.step(WizardStep.Intro)
        s.wizard.choose(PairRole.SHOW)
        val fresh = s.await("new code") { it.qr != null && it.qr != first }
        assertEquals(300, fresh.secondsLeft)
    }

    @Test
    fun theWindowDoesNotExpireAfterOurOwnScan() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SCAN)
        s.code()
        s.wizard.scan(r.device.addVirtualPeer("Anna", -80, scansBack = false))
        s.step(WizardStep.Half(PairRole.SHOW, second = true))
        s.tick(400_000)
        delay(200)
        assertEquals(WizardStep.Half(PairRole.SHOW, second = true), s.wizard.ui.value.step)
    }

    @Test
    fun scanErrorsBecomePlainGermanProblems() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SCAN)
        val ours = s.code()

        s.wizard.scan("https://example.com/kein-offgrid-code")
        assertEquals(WizardProblem.NOT_OFFGRID_CODE, s.await { it.problem != null }.problem)
        s.wizard.clearError()
        s.await { it.problem == null }

        s.wizard.scan(ours)
        assertEquals(WizardProblem.OWN_CODE, s.await { it.problem != null }.problem)
        assertEquals("still in the scan step", WizardStep.Half(PairRole.SCAN, second = false), s.wizard.ui.value.step)

        s.wizard.cameraProblem(CameraProblem.DENIED)
        assertEquals(WizardProblem.CAMERA_DENIED, s.await { it.problem == WizardProblem.CAMERA_DENIED }.problem)
    }

    @Test
    fun anExistingContactIsReportedAndCanBePairedAgain() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val anna = r.device.addVirtualPeer("Anna", -80, scansBack = false)
        val first = Screen(r)
        first.wizard.choose(PairRole.SCAN)
        first.code()
        first.wizard.scan(anna)
        val id = r.contactIdOf("Anna")
        r.store.activate(id)
        first.await { it.step is WizardStep.Success }
        first.close()

        val again = Screen(r)
        again.wizard.choose(PairRole.SCAN)
        again.code()
        again.wizard.scan(anna)
        val exists = again.await { it.problem != null }
        assertEquals(WizardProblem.ALREADY_PAIRED, exists.problem)
        assertTrue(exists.canReplace)

        r.store.deactivate(id)
        again.wizard.replace()
        val replaced = again.step(WizardStep.Half(PairRole.SHOW, second = true))
        assertNull("a replaced contact is not removed on cancel", replaced.cancelRemoves)
        again.wizard.cancel()
        assertTrue(r.discarded.isEmpty())
    }

    @Test
    fun cancelEndsTheSessionOnTheAntenna() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SHOW)
        s.code()
        assertNotNull(r.device.pairingSalt())
        s.wizard.cancel()
        eventually("pair.cancel reached the antenna") { r.device.pairingSalt() == null }
        assertTrue(r.discarded.isEmpty())
    }

    @Test
    fun cancelAfterOurScanRemovesTheHalfPairedContact() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val s = Screen(r)
        s.wizard.choose(PairRole.SCAN)
        s.code()
        s.wizard.scan(r.device.addVirtualPeer("Anna", -80, scansBack = false))
        val counter = s.step(WizardStep.Half(PairRole.SHOW, second = true))
        assertEquals("Anna", counter.cancelRemoves)
        assertTrue(counter.cancelNeedsConfirm)
        s.wizard.cancel()
        assertEquals(listOf(r.contactIdOf("Anna")), r.discarded)
    }

    @Test
    fun reopeningKeepsTheCodeAndTheCountdown() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val first = Screen(r)
        first.wizard.choose(PairRole.SHOW)
        val code = first.code()
        first.tick(30_000)
        first.close()

        val second = Screen(r)
        second.wizard.choose(PairRole.SHOW)
        assertEquals(code, second.code())
        second.tick(0)
        assertEquals(270, second.await { it.secondsLeft == 270 }.secondsLeft)
        // The antenna still has exactly the salt in the code, so a peer who scanned it gets the same key.
        assertArrayEquals(OgCrypto.unb64(code).copyOfRange(35, 51), r.device.pairingSalt())
    }

    @Test
    fun leavingStepOneWithoutAChoiceKeepsAnEarlierSession() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        val first = Screen(r)
        first.wizard.choose(PairRole.SHOW)
        val code = first.code()
        first.close()

        val peek = Screen(r)
        assertFalse(peek.step(WizardStep.Intro).cancelNeedsConfirm)
        peek.wizard.cancel()

        val third = Screen(r)
        third.wizard.choose(PairRole.SHOW)
        assertEquals(code, third.code())
    }

    @Test
    fun theScanTabRouteStartsInTheScanStep() = runBlocking<Unit> {
        val r = rig()
        r.ready()
        Screen(r, startWithScan = true).step(WizardStep.Half(PairRole.SCAN, second = false))
    }

    @Test
    fun problemsMapPairingErrorsCameraAndAntenna() {
        val ready = AntennaState(phase = AntennaPhase.READY)
        val away = AntennaState(phase = AntennaPhase.SEARCHING)
        val locked = AntennaState(phase = AntennaPhase.CONNECTING, activity = AntennaActivity.UNLOCKING, deviceState = "locked")
        assertNull(wizardProblem(null, ready, null))
        assertEquals(WizardProblem.NOT_OFFGRID_CODE, wizardProblem(PairingError.INVALID_QR, ready, null))
        assertEquals(WizardProblem.OWN_CODE, wizardProblem(PairingError.SELF_PAIR, ready, null))
        assertEquals(WizardProblem.ALREADY_PAIRED, wizardProblem(PairingError.CONTACT_EXISTS, ready, null))
        assertEquals(WizardProblem.CODE_USED, wizardProblem(PairingError.CODE_USED, ready, null))
        assertEquals(WizardProblem.FAILED, wizardProblem(PairingError.FAILED, ready, null))
        assertEquals(WizardProblem.ANTENNA_DISCONNECTED, wizardProblem(null, away, null))
        assertEquals(WizardProblem.ANTENNA_DISCONNECTED, wizardProblem(PairingError.ANTENNA_NOT_READY, away, null))
        assertEquals(WizardProblem.ANTENNA_LOCKED, wizardProblem(PairingError.ANTENNA_NOT_READY, locked, null))
        assertEquals(WizardProblem.CAMERA_DENIED, wizardProblem(null, away, CameraProblem.DENIED))
        assertEquals(WizardProblem.NO_CAMERA, wizardProblem(null, ready, CameraProblem.NO_CAMERA))
        assertEquals("the scan result wins", WizardProblem.OWN_CODE, wizardProblem(PairingError.SELF_PAIR, ready, CameraProblem.DENIED))
    }

    private class FakeStore : Pairing.Store {
        val states = MutableStateFlow<Map<Long, String>>(emptyMap())
        private val names = mutableMapOf<String, Long>()

        override suspend fun save(deviceId: String, contact: JsonObject): Long {
            val id = contact.int("contact_id")!!.toLong()
            synchronized(names) { names[contact["name"].toString().trim('"')] = id }
            states.value = states.value + (id to "pending")
            return id
        }

        override fun contactState(contactId: Long): Flow<String?> = states.map { it[contactId] }

        fun idOf(name: String): Long = synchronized(names) { names.getValue(name) }

        fun activate(id: Long) {
            states.value = states.value + (id to "active")
        }

        fun deactivate(id: Long) {
            states.value = states.value + (id to "pending")
        }
    }

    private class MemorySettingDao : SettingDao {
        private val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun observeAll(): Flow<List<SettingEntity>> = map.map { m -> m.map { SettingEntity(it.key, it.value) } }
        override suspend fun get(key: String): String? = map.value[key]
        override suspend fun put(setting: SettingEntity) { map.value = map.value + (setting.key to setting.value) }
        override suspend fun remove(key: String) { map.value = map.value - key }
    }
}
