package ch.offgrid.android.domain

import ch.offgrid.android.analytics.AnalyticsEvent
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.link.LinkClosedException
import ch.offgrid.android.link.LinkCommandException
import ch.offgrid.android.link.LinkTimeoutException
import ch.offgrid.android.link.int
import ch.offgrid.android.link.obj
import ch.offgrid.android.link.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class PairingError {
    INVALID_QR, ANTENNA_NOT_READY, CONTACT_EXISTS, SELF_PAIR, FAILED,

    /** The antenna refused a second `pair.complete` with the same peer code (no key and counter reuse). */
    CODE_USED,
}

data class PairingState(
    /** QR text from `pair.begin`; rendered as the QR code. */
    val qr: String? = null,
    /** When the antenna's session behind [qr] ends (`pair.begin` `expires_s`, on the [Pairing] clock). */
    val expiresAt: Long? = null,
    /** Increases with every `pair.begin` answer the screen shows, also when the code stayed the same. */
    val codeVersion: Int = 0,
    /** The shown code was replaced after someone may already have scanned the old one: they must scan again. */
    val codeRenewed: Boolean = false,
    val error: PairingError? = null,
    /** With [PairingError.CONTACT_EXISTS]: the scanned code, to pair again with `replace: true`. */
    val replaceCode: String? = null,
    /** Set after we scanned the other side. */
    val scannedName: String? = null,
    val scannedContactId: Long? = null,
    /** Our first packet to the peer went out (step "Schlüssel per Funk gesendet"). */
    val pingSent: Boolean = false,
    /** The peer's first valid packet arrived: the contact is active (step 3). */
    val confirmedContactId: Long? = null,
)

/**
 * QR key exchange as in link spec 5. Both people show their code (`pair.begin`) and scan the other one
 * (`pair.complete`) from the same pairing session; the contact is `pending` until the first authenticated
 * packet from the peer. To make that happen right away, the app sends an empty reaction as a ping.
 *
 * The code on screen must belong to the session the antenna completes with. Nils' firmware draws a new salt on
 * every `pair.begin` and the lab image only within its 5-minute window, so this class keeps the session of the
 * last `pair.begin` and shows it again when the screen is opened again, instead of asking for a new one. It is
 * renewed only when less than a minute is left, after a lock or restart of the antenna (the salt is gone), or
 * when the antenna says it is gone (`E_NO_PAIRING`). Leaving the screen does not cancel it; only [finish] does.
 */
class Pairing(
    private val antenna: AntennaManager,
    private val store: Store,
    private val scope: CoroutineScope,
    private val track: (AnalyticsEvent) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var watchJob: Job? = null

    /** The `pair.cancel` of [finish]; a following [begin] waits for it so it cannot wipe the new salt. */
    private var cancelJob: Job? = null

    /** The antenna's pairing session behind [PairingState.qr]. */
    private data class Session(val qr: String, val expiresAt: Long, val unlockCount: Int, val deviceId: String?)

    @Volatile
    private var session: Session? = null

    /**
     * Shows this antenna's code: the open session when it is still good, else a new one. With [autoRenew] the code
     * is replaced a minute before the session ends; without it the code stays until the session is over, so a
     * countdown on screen can end in "abgelaufen" instead of silently swapping a code someone may have scanned.
     */
    fun begin(autoRenew: Boolean = true) {
        track(AnalyticsEvent.PairingStarted)
        refreshJob?.cancel()
        val cancelling = cancelJob
        refreshJob = scope.launch {
            cancelling?.join()
            while (true) {
                if (!antenna.state.value.ready) {
                    _state.update { it.copy(error = PairingError.ANTENNA_NOT_READY) }
                    antenna.state.first { it.ready }
                }
                val current = try {
                    currentSession()
                } catch (_: LinkCommandException) {
                    null
                } catch (_: LinkClosedException) {
                    null
                } catch (_: LinkTimeoutException) {
                    null
                }
                if (current == null) {
                    delay(1_000)
                    continue
                }
                _state.update {
                    it.copy(
                        qr = current.qr, expiresAt = current.expiresAt, codeVersion = it.codeVersion + 1,
                        error = it.error.takeIf { e -> e != PairingError.ANTENNA_NOT_READY },
                    )
                }
                if (!autoRenew) return@launch
                // Renew a minute before the end; after our own scan never, the peer may still scan this code.
                withTimeoutOrNull((current.expiresAt - RENEW_BEFORE_MS - now()).coerceAtLeast(1_000)) {
                    _state.first { it.scannedName != null }
                }
                if (_state.value.scannedName != null) return@launch
            }
        }
    }

    private fun usable(s: Session?): Boolean {
        val a = antenna.state.value
        return s != null && s.unlockCount == a.unlockCount && s.deviceId == a.deviceId && s.expiresAt - now() > RENEW_BEFORE_MS
    }

    private suspend fun currentSession(): Session = session.takeIf(::usable) ?: newSession()

    private suspend fun newSession(): Session {
        val result = antenna.call("pair.begin")
        val a = antenna.state.value
        return Session(result.str("qr")!!, now() + (result.int("expires_s") ?: 300) * 1_000L, a.unlockCount, a.deviceId)
            .also { session = it }
    }

    /**
     * Completes with the scanned [qrText]. When the antenna has no session any more (`E_NO_PAIRING`: expired,
     * locked, or the firmware ended it after an earlier completion), it gets a fresh one, the screen shows the new
     * code, and the completion is tried once more. A second refusal means the antenna does not take the same peer
     * code twice: the peer has to open a new code. [replace] re-pairs a contact the antenna already has; both sides
     * have to do that (link spec 5).
     */
    fun complete(qrText: String, replace: Boolean = false) {
        scope.launch {
            val qr = qrText.trim()
            if (!looksLikeOffGridQr(qr)) {
                _state.update { it.copy(error = PairingError.INVALID_QR) }
                return@launch
            }
            if (!antenna.state.value.ready) {
                _state.update { it.copy(error = PairingError.ANTENNA_NOT_READY) }
                return@launch
            }
            val args = buildJsonObject {
                put("qr", qr)
                if (replace) put("replace", true)
            }
            try {
                val result = try {
                    antenna.call("pair.complete", args)
                } catch (e: LinkCommandException) {
                    if (e.error.code != "E_NO_PAIRING") throw e
                    renewSession()
                    try {
                        antenna.call("pair.complete", args)
                    } catch (again: LinkCommandException) {
                        if (again.error.code == "E_NO_PAIRING") {
                            _state.update { it.copy(error = PairingError.CODE_USED) }
                            return@launch
                        }
                        throw again
                    }
                }
                val contactJson = result.obj("contact")!!
                val deviceId = antenna.state.value.deviceId!!
                val id = store.save(deviceId, contactJson)
                _state.update {
                    it.copy(error = null, replaceCode = null, scannedName = contactJson.str("name"), scannedContactId = id, pingSent = false)
                }
                watchConfirmation(id)
                // The contact is stored at this point; the ping only speeds up activation. Firmware that
                // rejects it (the team image up to ed9fd57 refuses every reaction, see docs/hardware.md)
                // must not turn a successful pairing into an error. The peer's own ping activates us anyway.
                val pinged = try {
                    antenna.call("msg.send", Messaging.pingArgs(contactJson.int("contact_id")!!))
                    true
                } catch (_: LinkCommandException) {
                    false
                }
                _state.update { it.copy(pingSent = pinged) }
            } catch (e: LinkCommandException) {
                _state.update {
                    it.copy(
                        error = when (e.error.code) {
                            "E_INVALID_QR" -> PairingError.INVALID_QR
                            "E_CONTACT_EXISTS" -> PairingError.CONTACT_EXISTS
                            "E_SELF_PAIR" -> PairingError.SELF_PAIR
                            else -> PairingError.FAILED
                        },
                        replaceCode = qr.takeIf { e.error.code == "E_CONTACT_EXISTS" },
                    )
                }
            } catch (_: LinkClosedException) {
                _state.update { it.copy(error = PairingError.ANTENNA_NOT_READY) }
            } catch (_: LinkTimeoutException) {
                _state.update { it.copy(error = PairingError.FAILED) }
            }
        }
    }

    /** `pair.cancel` and `pair.begin`: a new salt, shown at once; whoever scanned the old code must scan again. */
    private suspend fun renewSession() {
        runCatching { antenna.call("pair.cancel") }
        val shown = _state.value.qr
        val fresh = newSession()
        _state.update {
            it.copy(qr = fresh.qr, expiresAt = fresh.expiresAt, codeVersion = it.codeVersion + 1, codeRenewed = shown != null && shown != fresh.qr)
        }
    }

    private fun watchConfirmation(contactId: Long) {
        watchJob?.cancel()
        watchJob = scope.launch {
            store.contactState(contactId).first { it == "active" }
            _state.update { it.copy(confirmedContactId = contactId) }
            track(AnalyticsEvent.PairingCompleted)
        }
    }

    /**
     * Leaves the screen without a finished pairing. The session stays open on the antenna (it ends by itself after
     * 5 minutes), so opening the screen again shows the same code to someone who already scanned it.
     */
    fun leave() {
        refreshJob?.cancel()
        watchJob?.cancel()
        _state.value = PairingState()
    }

    /**
     * Ends the session on the antenna (`pair.cancel`): after both sides scanned and the peer's packet arrived, or
     * when the user aborts the pairing. The next [begin] gets a new code.
     */
    fun finish() {
        leave()
        session = null
        cancelJob = scope.launch { runCatching { antenna.call("pair.cancel") } }
    }

    fun clearError() = _state.update { it.copy(error = null, replaceCode = null) }

    /** Where paired contacts go: [Messaging.upsertFromDevice] and the contacts table in the app. */
    interface Store {
        /** Stores the `pair.complete` contact object and returns the local contact id. */
        suspend fun save(deviceId: String, contact: JsonObject): Long

        /** The local contact's state (`pending`, `active`, …), null while it does not exist. */
        fun contactState(contactId: Long): Flow<String?>
    }

    companion object {
        /** A code is renewed when less than this is left, like the lab image's salt window. */
        const val RENEW_BEFORE_MS = 60_000L

        /** Cheap local check before bothering the device: Base64 of "OG1" + 32 B key + 16 B salt + name. */
        fun looksLikeOffGridQr(text: String): Boolean {
            val raw = runCatching { OgCrypto.unb64(text) }.getOrNull() ?: return false
            return raw.size >= 51 && raw[0] == 'O'.code.toByte() && raw[1] == 'G'.code.toByte() && raw[2] == '1'.code.toByte()
        }
    }
}
