package ch.offgrid.android.ui.screens

import ch.offgrid.android.domain.AntennaActivity
import ch.offgrid.android.domain.AntennaError
import ch.offgrid.android.domain.AntennaState
import ch.offgrid.android.domain.Pairing
import ch.offgrid.android.domain.PairingError
import ch.offgrid.android.domain.PairingState
import ch.offgrid.android.ui.components.Step
import ch.offgrid.android.ui.components.StepState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What this phone does in one half of the mutual scan. */
enum class PairRole { SHOW, SCAN }

enum class CameraProblem { NO_CAMERA, DENIED }

/** Where the guided pairing of "Kontakt hinzufügen" stands. */
sealed interface WizardStep {
    /** Step 1: how the mutual scan works and who starts. */
    data object Intro : WizardStep

    /** Step 2 ([second] false) or step 3: one half of the mutual scan. */
    data class Half(val role: PairRole, val second: Boolean) : WizardStep

    /** Step 3 after our own scan as the second half: waiting for the peer's first packet over the radio. */
    data object Confirming : WizardStep

    /** The contact is active on this antenna: both sides scanned and a packet arrived. */
    data class Success(val contactId: Long) : WizardStep

    /** The antenna's pairing window ran out before this phone scanned. */
    data object Expired : WizardStep
}

/** What the wizard reports under the current step, in plain German. */
enum class WizardProblem(val text: String) {
    NOT_OFFGRID_CODE("Das ist kein OffGrid-Code. Scanne den QR-Code, den die OffGrid-App deines Gegenübers anzeigt."),
    OWN_CODE("Das ist dein eigener Code. Scanne den Code auf dem Handy deines Gegenübers."),
    ALREADY_PAIRED(
        "Ihr seid schon gekoppelt: Dieser Kontakt ist bereits gespeichert. „Neu koppeln“ ersetzt eure Schlüssel; " +
            "dein Gegenüber muss danach auch neu koppeln.",
    ),
    CODE_USED("Diesen Code hat deine Antenne schon verwendet. Dein Gegenüber soll die Kopplung neu starten, dann scannst du den neuen Code."),
    ANTENNA_DISCONNECTED("Die Antenne ist nicht verbunden. Schliess sie an, dann geht die Kopplung hier weiter."),
    ANTENNA_LOCKED("Die Antenne ist gesperrt. Sobald sie entsperrt ist, geht die Kopplung hier weiter."),
    FAILED("Der Schlüsselaustausch hat nicht geklappt. Scanne den Code nochmals."),
    CAMERA_DENIED("Die App darf die Kamera nicht verwenden. Erlaube den Zugriff in den Android-Einstellungen."),

    /** Spec error `scanner-unavailable`. */
    NO_CAMERA("Der QR-Scanner ist auf diesem Handy nicht verfügbar."),
}

/** A pairing error, a camera problem or the antenna state as the one problem the wizard shows; the scan result wins. */
fun wizardProblem(error: PairingError?, antenna: AntennaState, camera: CameraProblem?): WizardProblem? = when (error) {
    PairingError.INVALID_QR -> WizardProblem.NOT_OFFGRID_CODE
    PairingError.SELF_PAIR -> WizardProblem.OWN_CODE
    PairingError.CONTACT_EXISTS -> WizardProblem.ALREADY_PAIRED
    PairingError.CODE_USED -> WizardProblem.CODE_USED
    PairingError.FAILED -> WizardProblem.FAILED
    PairingError.ANTENNA_NOT_READY, null -> when {
        camera == CameraProblem.DENIED -> WizardProblem.CAMERA_DENIED
        camera == CameraProblem.NO_CAMERA -> WizardProblem.NO_CAMERA
        antenna.ready && error == null -> null
        antenna.locked() -> WizardProblem.ANTENNA_LOCKED
        else -> WizardProblem.ANTENNA_DISCONNECTED
    }
}

private fun AntennaState.locked() = deviceState == "locked" ||
    activity == AntennaActivity.UNLOCKING || activity == AntennaActivity.RELOCKING ||
    error == AntennaError.UNLOCK_FAILED || error == AntennaError.RATE_LIMITED

/** The wizard's own part of the state; the rest comes from [Pairing] and the antenna. */
data class WizardState(
    val step: WizardStep = WizardStep.Intro,
    val camera: CameraProblem? = null,
    /** Our code changed after the peer may have scanned the old one (see [PairingState.codeRenewed]). */
    val codeRenewed: Boolean = false,
    /** The last scan replaced the keys of an existing contact; cancelling must not delete it. */
    val replaced: Boolean = false,
)

/** Everything the screen draws for one moment of the wizard. */
data class WizardUi(
    val step: WizardStep,
    /** 1 to [WIZARD_STEPS]; null when finished or expired. */
    val stepNumber: Int?,
    /** Our code; null while it loads or while the antenna is away before our scan (see [addContactUi]). */
    val qr: String?,
    /** Seconds until the antenna's session ends; only shown while this phone still has to scan. */
    val secondsLeft: Int?,
    val problem: WizardProblem?,
    /** "Neu koppeln" is offered for a contact the antenna already has. */
    val canReplace: Boolean,
    val codeRenewed: Boolean,
    /** The peer's name from its code, once we scanned it. */
    val peerName: String?,
    /** Progress list after our own scan, null before. */
    val waiting: List<Step>?,
    /** Leaving now may break a scan the peer already did: ask first. */
    val cancelNeedsConfirm: Boolean,
    /** Name of the contact that [PairingWizard.cancel] removes again because only we completed it. */
    val cancelRemoves: String?,
)

const val WIZARD_STEPS = 3

fun wizardUi(w: WizardState, p: PairingState, antenna: AntennaState, now: Long): WizardUi {
    val base = addContactUi(p, antenna.ready)
    val step = w.step
    val ownScanDone = p.scannedName != null
    val counting = step is WizardStep.Half && !ownScanDone && base.qr != null
    return WizardUi(
        step = step,
        stepNumber = when (step) {
            WizardStep.Intro -> 1
            is WizardStep.Half -> if (step.second) 3 else 2
            WizardStep.Confirming -> 3
            is WizardStep.Success, WizardStep.Expired -> null
        },
        qr = base.qr,
        secondsLeft = p.expiresAt?.takeIf { counting }?.let { ((it - now + 999) / 1_000).coerceAtLeast(0).toInt() },
        problem = wizardProblem(base.error, antenna, w.camera.takeIf { step is WizardStep.Half && step.role == PairRole.SCAN }),
        canReplace = base.canReplace,
        codeRenewed = w.codeRenewed,
        peerName = p.scannedName,
        waiting = when {
            !ownScanDone -> null
            step is WizardStep.Half && step.role == PairRole.SHOW -> peerScansSteps(p.scannedName.orEmpty(), base.confirmed)
            else -> scanSteps(pingSent = p.pingSent, confirmed = base.confirmed)
        },
        cancelNeedsConfirm = step is WizardStep.Half || step == WizardStep.Confirming,
        cancelRemoves = p.scannedName?.takeIf { p.scannedContactId != null && !base.confirmed && !w.replaced },
    )
}

/** Step 3 after we scanned first: the peer still has to scan our code. */
fun peerScansSteps(name: String, confirmed: Boolean): List<Step> = listOf(
    Step("Code von $name gescannt", StepState.Done, meta = "erledigt"),
    Step("$name scannt deinen Code", if (confirmed) StepState.Done else StepState.Active, meta = if (confirmed) "erledigt" else "wartet …"),
    Step("Bestätigung per Funk", if (confirmed) StepState.Done else StepState.Todo),
)

/** One tick per second for the countdown. */
private fun everySecond(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1_000)
    }
}

/**
 * The guided mutual QR scan (link spec 5) as a state machine, without Compose so it can be tested on the JVM.
 *
 * Steps: 1 [WizardStep.Intro] → 2 [WizardStep.Half] (first half, the user picks the role) → 3 the other role →
 * [WizardStep.Success]. When "done" is known:
 * - Our scan: `pair.complete` with the peer's code succeeded ([PairingState.scannedName]). The contact is pending.
 * - The peer's scan: nothing goes over the radio before both sides completed, so while we show first the user
 *   confirms it ([peerScanned]). After our own scan it shows as the contact turning `active`: the peer's ping
 *   or the ACK of ours is the first authenticated packet ([PairingState.confirmedContactId]).
 *
 * The countdown is the antenna's session ([PairingState.expiresAt], `pair.begin` `expires_s`, 300 s). The wizard
 * starts it with `autoRenew = false`, so the code never changes silently; at zero the step becomes
 * [WizardStep.Expired] and [restart] gets a new code. After our own scan the window no longer matters to us.
 *
 * [cancel] is the user's abort: `pair.cancel` and a contact that only we completed is removed again, so no
 * half-paired contact stays behind. [dispose] (screen gone some other way) keeps the session, so reopening
 * the screen shows the same code (salt fix of 69466b6).
 */
class PairingWizard(
    private val pairing: Pairing,
    private val antenna: StateFlow<AntennaState>,
    private val scope: CoroutineScope,
    /** Deletes a contact on the antenna and in the app; it has to outlive the screen. */
    private val discardContact: (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    ticks: Flow<Unit> = everySecond(),
    startWithScan: Boolean = false,
) {
    private val state = MutableStateFlow(WizardState())
    private val clock = MutableStateFlow(now())

    /** This visit asked the antenna for a session. */
    private var started = false

    /** The code shown in a "show" step; a different code after our scan means the peer must scan again. */
    private var shownQr: String? = null

    /** Cancelled or finished: the session is no longer ours to keep. */
    private var closed = false

    /** A valid code scanned while the antenna was away; `pair.complete` runs once it is back. */
    private var waitingCode: String? = null

    // The step changes in reaction to pairing states, so the newest pairing state is read here instead of the one
    // combine saw last; otherwise a new step could briefly be drawn with the pairing state before it.
    val ui: StateFlow<WizardUi> = combine(state, pairing.state, antenna, clock) { w, _, a, t -> wizardUi(w, pairing.state.value, a, t) }
        .stateIn(scope, SharingStarted.Eagerly, wizardUi(state.value, pairing.state.value, antenna.value, clock.value))

    init {
        scope.launch { pairing.state.collect(::onPairing) }
        scope.launch { ticks.collect { tick() } }
        scope.launch {
            // Only transitions back to ready; the first value is the state when the screen opened.
            antenna.map { it.ready }.distinctUntilChanged().drop(1).filter { it }.collectLatest { onReconnected() }
        }
        if (startWithScan) choose(PairRole.SCAN)
    }

    /** Step 1 → 2, or switching the role while still in step 2. */
    fun choose(role: PairRole) {
        val step = state.value.step
        val switchable = step == WizardStep.Intro || (step is WizardStep.Half && !step.second)
        if (!switchable || pairing.state.value.scannedName != null) return
        pairing.clearError()
        state.update { it.copy(step = WizardStep.Half(role, second = false), camera = null) }
        if (!started) {
            started = true
            pairing.begin(autoRenew = false)
        }
    }

    /** Step 2 "show" → 3: the user says the peer has scanned our code. */
    fun peerScanned() = move(WizardStep.Half(PairRole.SHOW, second = false), WizardStep.Half(PairRole.SCAN, second = true))

    /** Step 3 "scan" back to 2 "show": the peer had not scanned our code yet. */
    fun peerNotScannedYet() = move(WizardStep.Half(PairRole.SCAN, second = true), WizardStep.Half(PairRole.SHOW, second = false))

    /** While waiting for the radio: show our code again in case the peer has not scanned it. */
    fun showCodeAgain() = move(WizardStep.Confirming, WizardStep.Half(PairRole.SHOW, second = true))

    private fun move(from: WizardStep, to: WizardStep) {
        pairing.clearError()
        state.update { if (it.step == from) it.copy(step = to, camera = null) else it }
    }

    fun scan(code: String) {
        val step = state.value.step
        if (step !is WizardStep.Half || step.role != PairRole.SCAN) return
        state.update { it.copy(camera = null, replaced = false) }
        // Pairing drops a code it cannot send yet, but the problem text promises that the pairing goes on once the
        // antenna is back, so the wizard keeps the code until then.
        waitingCode = code.takeIf { !antenna.value.ready && Pairing.looksLikeOffGridQr(it.trim()) }
        pairing.complete(code)
    }

    /** "Neu koppeln" after `E_CONTACT_EXISTS`: replaces the keys of that contact; the peer has to do the same. */
    fun replace() {
        val code = pairing.state.value.replaceCode ?: return
        state.update { it.copy(replaced = true) }
        pairing.complete(code, replace = true)
    }

    fun cameraProblem(problem: CameraProblem?) {
        if (problem != null) pairing.clearError()
        state.update { it.copy(camera = problem) }
    }

    fun clearError() {
        pairing.clearError()
        state.update { it.copy(camera = null) }
    }

    /** "Neu starten" after the window ran out: back to step 1; choosing a role then gets a new code. */
    fun restart() {
        if (state.value.step != WizardStep.Expired) return
        pairing.leave()
        started = false
        shownQr = null
        state.value = WizardState()
    }

    /** The user aborts: end the antenna's session and remove a contact only this side completed. */
    fun cancel() {
        if (closed) return
        closed = true
        val p = pairing.state.value
        val halfPaired = p.scannedContactId?.takeIf { p.confirmedContactId == null && !state.value.replaced }
        if (started || p.scannedName != null) pairing.finish() else pairing.leave()
        halfPaired?.let(discardContact)
    }

    /** The screen went away without [cancel] (another screen on top replaced it, process end): keep the session. */
    fun dispose() {
        if (!closed) pairing.leave()
    }

    private fun onPairing(p: PairingState) {
        val step = state.value.step
        if (step is WizardStep.Half && step.role == PairRole.SHOW && p.qr != null && p.scannedName == null) shownQr = p.qr
        val confirmed = p.confirmedContactId
        if (confirmed != null && step !is WizardStep.Success) {
            closed = true
            state.update { it.copy(step = WizardStep.Success(confirmed), camera = null) }
            pairing.finish()
            return
        }
        if (p.scannedName == null || step !is WizardStep.Half || step.role != PairRole.SCAN) return
        val next = if (!step.second) {
            WizardState(step = WizardStep.Half(PairRole.SHOW, second = true), replaced = state.value.replaced)
        } else {
            // We showed first; when our completion had to renew the session, the peer scanned a code that is gone.
            val renewed = shownQr != null && p.qr != shownQr
            WizardState(
                step = if (renewed) WizardStep.Half(PairRole.SHOW, second = true) else WizardStep.Confirming,
                codeRenewed = renewed, replaced = state.value.replaced,
            )
        }
        state.update { if (it.step == step) next else it }
    }

    private fun tick() {
        val t = now()
        clock.value = t
        val p = pairing.state.value
        val end = p.expiresAt ?: return
        if (t < end || p.scannedName != null) return
        state.update { if (it.step is WizardStep.Half) WizardState(step = WizardStep.Expired) else it }
    }

    private suspend fun onReconnected() {
        if (closed) return
        val before = pairing.state.value
        val code = waitingCode
        // Unplugging the antenna restarts it and loses the pending salt behind the shown code; Pairing then
        // fetches a fresh one (it keeps the code when the antenna stayed unlocked). After our own scan the
        // contact is stored with that salt and the code must stay the same until the other side has scanned it.
        val freshCode = before.scannedName == null
        if (freshCode && started) pairing.begin(autoRenew = false)
        if (code == null) return
        // `pair.complete` must run against the pairing that belongs to the code on screen.
        if (freshCode && started) pairing.state.first { it.qr != null && it.codeVersion > before.codeVersion }
        waitingCode = null
        pairing.complete(code)
    }
}
