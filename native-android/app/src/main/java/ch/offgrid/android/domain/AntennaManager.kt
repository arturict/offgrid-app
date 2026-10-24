package ch.offgrid.android.domain

import ch.offgrid.android.BuildConfig
import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.crypto.TokenWrap
import ch.offgrid.android.crypto.UnlockTokenSource
import ch.offgrid.android.crypto.WrapPurpose
import ch.offgrid.android.link.Incoming
import ch.offgrid.android.link.Link
import ch.offgrid.android.link.LinkClient
import ch.offgrid.android.link.LinkClosedException
import ch.offgrid.android.link.LinkCommandException
import ch.offgrid.android.link.LinkError
import ch.offgrid.android.link.LinkKind
import ch.offgrid.android.link.LinkOpenException
import ch.offgrid.android.link.LinkTimeoutException
import ch.offgrid.android.link.OpenError
import ch.offgrid.android.link.bool
import ch.offgrid.android.link.double
import ch.offgrid.android.link.int
import ch.offgrid.android.link.obj
import ch.offgrid.android.link.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class AntennaPhase { DISCONNECTED, SEARCHING, DEVICE_FOUND, CONNECTING, READY, ERROR }

/** What a [AntennaPhase.CONNECTING] session is doing, shown under "Verbindung wird aufgebaut". */
enum class AntennaActivity {
    /** `sys.hello` is being retried while the antenna boots. */
    HELLO,

    /** The antenna has no vault (`unprovisioned`): `sec.provision` sets it up for this phone. */
    PROVISIONING,

    /** The antenna is `locked`: `sec.unlock` with this phone's token. */
    UNLOCKING,

    /** It locked itself while connected (heartbeat timeout, restart, error): unlock again. */
    RELOCKING,

    /** `sys.factory_reset` was accepted; waiting for the restart and setting it up again. */
    RESETTING,
}

/** UI error codes, texts in the screen spec ("Fehlertexte") and `AntennaError.text()`. */
enum class AntennaError {
    NO_DEVICE, PERMISSION_DENIED, OPEN_FAILED, NO_INFO, PROTO_MISMATCH, LOST,

    /** `E_UNLOCK_FAILED`: the antenna's vault belongs to another phone's token. */
    UNLOCK_FAILED,

    /** `E_RATE_LIMITED`: too many failed unlock or reset attempts, the antenna waits (2 s doubling, at most 60 s). */
    RATE_LIMITED,

    /** `E_NO_SESSION` twice in a row: the `sys.hello` session expired or was used up. */
    NO_SESSION,

    /** `E_INTERNAL` during unlock: the antenna could not read or write its vault. */
    VAULT_ERROR,

    /** `sys.hello.mock` is true: an old image without real authentication (link spec 14, item 2). */
    MOCK_FIRMWARE,

    /** The link is open but three heartbeats in a row got no answer. */
    UNRESPONSIVE,

    /** Any other error code; [AntennaState.errorCode] has it. */
    REJECTED,
}

/** A `sec.state` "locked" event: why and when (wall clock of the phone). */
data class LockEvent(val reason: String, val at: Long)

/**
 * What the antenna says about groups in `sys.hello` (link spec 6, "Groups"): `features` lists `"groups"` and
 * `limits` carries the slots, the members per group (the owner included) and the text size. Firmware without
 * the feature has no `group.*` commands, so the app shows no groups for it.
 */
data class GroupSupport(
    val maxGroups: Int = DEFAULT_MAX_GROUPS,
    val maxMembers: Int = DEFAULT_MAX_MEMBERS,
    val maxTextBytes: Int = MessageLimits.DEFAULT_MAX_TEXT_BYTES,
) {
    /** Others a group of this phone can take: the app's cap [MessageLimits.MAX_GROUP_MEMBERS], less if the antenna allows fewer. */
    val maxOtherMembers: Int get() = minOf(MessageLimits.MAX_GROUP_MEMBERS, maxMembers - 1)

    companion object {
        const val FEATURE = "groups"
        const val DEFAULT_MAX_GROUPS = 8
        const val DEFAULT_MAX_MEMBERS = 16

        /** Null when [hello] does not list the feature. */
        fun parse(hello: JsonObject): GroupSupport? {
            val listed = (hello["features"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == FEATURE } == true
            if (!listed) return null
            val limits = hello.obj("limits")
            return GroupSupport(
                maxGroups = limits?.int("max_groups") ?: DEFAULT_MAX_GROUPS,
                maxMembers = limits?.int("max_group_members") ?: DEFAULT_MAX_MEMBERS,
                maxTextBytes = limits?.int("max_group_text_bytes") ?: MessageLimits.DEFAULT_MAX_TEXT_BYTES,
            )
        }
    }
}

data class AntennaState(
    val phase: AntennaPhase = AntennaPhase.DISCONNECTED,
    val kind: LinkKind = LinkKind.SIMULATED,
    val deviceLabel: String? = null,
    val fwVersion: String? = null,
    /** `fw.build` from `sys.hello` (compiler build date on the firmware). */
    val fwBuild: String? = null,
    val deviceId: String? = null,
    val nodeId: String? = null,
    /** Name of the antenna's own identity (`id.get`). */
    val deviceName: String? = null,
    val maxTextBytes: Int = MessageLimits.DEFAULT_MAX_TEXT_BYTES,
    /** TEXT codec of a lab radio image (`sys.hello.text_codec`); the team firmware always sends raw UTF-8. */
    val textCodec: String = MessageLimits.CODEC_RAW,
    /** UTF-8 input the antenna accepts; above [maxTextBytes] only with a codec (`limits.max_text_input_bytes`). */
    val maxTextInputBytes: Int = MessageLimits.DEFAULT_MAX_TEXT_BYTES,
    /** Null for firmware without the `groups` feature (`sys.hello.features`). */
    val groups: GroupSupport? = null,
    val error: AntennaError? = null,
    /** The link error code behind [error], for the note on the connect screen. */
    val errorCode: String? = null,
    /** Reason of the last `sec.state` "locked" (`heartbeat_timeout`, `lock`, `error`, …) until the antenna is open again. */
    val lockReason: String? = null,
    /** Device state from the last `sys.hello` or `sec.state`: `unprovisioned`, `locked` or `unlocked`. */
    val deviceState: String? = null,
    val activity: AntennaActivity? = null,
    /** `sys.hello.lab_radio`: our lab image with `lab.*`, OGZ1, `waiting` and `recent`. */
    val labRadio: Boolean = false,
    /** `sys.hello.radio_diagnostics`: the team's diagnostics profile. */
    val radioDiagnostics: Boolean = false,
    val radio: RadioConfig? = null,
    val mesh: MeshConfig? = null,
    val lab: LabConfig? = null,
    /** `sys.status.radio.running` after unlocking; RF can fail independently of the vault (link spec 4). */
    val radioRunning: Boolean? = null,
    /** `sys.status.radio.last_error` (RadioLib code), when not 0. */
    val radioError: Int? = null,
    /** `sys.status.storage.vault_status`: `ready`, `missing`, `corrupt`, … */
    val vaultStatus: String? = null,
    /** `sys.status.queue.dropped_events`: events the antenna could not hand to the phone since it booted. */
    val droppedEvents: Int? = null,
    val secureBoot: Boolean? = null,
    val flashEncryption: Boolean? = null,
    /** `sec.get_policy.lock_timeout_s`: the antenna locks after this long without a command (0 = never). */
    val lockTimeoutS: Int? = null,
    /**
     * With [AntennaError.RATE_LIMITED]: phone time when the antenna accepts the next attempt. The firmware does
     * not report it, so this is the app's estimate of `Security::failure`; the session retries on its own then.
     */
    val retryAt: Long? = null,
    /** The last time the antenna locked itself or was locked, kept after it was opened again. */
    val lastLock: LockEvent? = null,
    /** Code of the last unsolicited `error` event (storage, RF, lost events). */
    val lastDeviceError: String? = null,
    /**
     * Counts the times this app opened a locked or empty vault. A change means the antenna's RAM state (pairing
     * session, refs) is new; reconnecting to an antenna that stayed unlocked keeps it.
     */
    val unlockCount: Int = 0,
) {
    val ready: Boolean get() = phase == AntennaPhase.READY
    val freqMhz: Double? get() = radio?.freqMhz
    val busy: Boolean get() = phase == AntennaPhase.SEARCHING || phase == AntennaPhase.DEVICE_FOUND || phase == AntennaPhase.CONNECTING
}

/**
 * Outcome of [AntennaManager.factoryReset] and [Messaging.resetAntenna]. Everything except [DONE],
 * [DONE_NOT_RECONNECTED] and [NOT_CONNECTED] leaves the phone's data untouched.
 */
enum class ResetResult {
    /** The antenna accepted, restarted and was set up again (from [Messaging.resetAntenna]; the manager answers DONE on acceptance). */
    DONE,

    /** The antenna accepted the reset, but did not report back ready within the wait; it finishes on its own. */
    DONE_NOT_RECONNECTED,

    /** No antenna session to reset (`deleteAll` then only clears the phone). */
    NOT_CONNECTED,

    /** `E_UNLOCK_FAILED`: the antenna's vault does not open with this phone's token (or the old phone's from a backup). */
    WRONG_PHONE,

    /** `E_RATE_LIMITED`: the antenna's authentication backoff is running. */
    RATE_LIMITED,

    /** `E_UNKNOWN_CMD`: the firmware has no `sys.factory_reset`. */
    UNSUPPORTED,

    /** Any other refusal (`E_INTERNAL`, `E_NO_SESSION`, `E_LOCKED`, …) or no answer to `sys.hello`; the reset did not start. */
    FAILED,

    /** The link broke or timed out after the reset command was sent: the antenna may or may not have erased itself. */
    UNKNOWN,
}

/** Receives device events and the "ready" signal; implemented by [Messaging]. */
interface AntennaListener {
    suspend fun onReady(antenna: AntennaManager, deviceId: String)
    suspend fun onEvent(event: Incoming.Event)
    suspend fun onDeviceRebooted()
}

/** Opening the vault failed for a reason the user has to see. */
private class UnlockFailure(val error: AntennaError, val code: String?) : Exception(code ?: error.name)

/**
 * The firmware's authentication backoff (`Security::failure`): from the third failure in a row it refuses every
 * attempt for 2 s, doubling up to 60 s, and success clears it. `E_RATE_LIMITED` carries no remaining time, so the
 * app estimates it from its own failures; failures by another phone are unknown to it, so each further
 * `E_RATE_LIMITED` doubles the guess from 2 s instead.
 */
object AuthBackoff {
    const val MAX_MS = 60_000L

    fun delayAfter(failures: Int): Long = if (failures < 3) 0 else minOf(MAX_MS, 2_000L shl (failures - 3).coerceAtMost(5))

    /** When the antenna accepts the next attempt, seen at [now] after [rateLimited] `E_RATE_LIMITED` answers in a row. */
    fun retryAt(now: Long, failures: Int, lastFailureAt: Long, rateLimited: Int): Long {
        val known = lastFailureAt + delayAfter(failures)
        return if (known > now) known else now + minOf(MAX_MS, 2_000L shl (rateLimited - 1).coerceIn(0, 5))
    }
}

/**
 * Owns the connection to the antenna (link spec 2, 3 and 14): open the link, `sys.hello`, provision or unlock
 * the vault with the Keystore token, heartbeat every second, notice auto-locks and restarts and unlock again,
 * and keep what the antenna reports about itself (firmware, radio, mesh, lab settings) in [state].
 */
class AntennaManager(
    private val scope: CoroutineScope,
    private val linkProvider: () -> Link,
    private val tokenSource: UnlockTokenSource,
    private val settings: Settings,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AntennaState())
    val state: StateFlow<AntennaState> = _state.asStateFlow()

    lateinit var listener: AntennaListener

    private var sessionJob: Job? = null
    private var retryJob: Job? = null
    private var client: LinkClient? = null
    private val unlockMutex = Mutex()
    private val relockMutex = Mutex()

    /** Authentication failures of this app in a row (`E_UNLOCK_FAILED`), for the [AuthBackoff] estimate. */
    private var authFailures = 0
    private var lastAuthFailureAt = 0L
    private var rateLimitedInRow = 0

    /** Set while a factory reset runs: its lock and restart are expected and must not trigger a re-unlock. */
    @Volatile
    private var resetBoot: CompletableDeferred<Unit>? = null

    fun connect() {
        if (state.value.busy || state.value.ready) return
        retryJob?.cancel()
        val previous = sessionJob
        sessionJob = scope.launch {
            previous?.cancelAndJoin()
            runSession()
        }
    }

    fun disconnect() {
        retryJob?.cancel()
        sessionJob?.cancel()
        client?.close()
        client = null
        _state.update { it.copy(phase = AntennaPhase.DISCONNECTED, error = null, errorCode = null, activity = null, retryAt = null) }
    }

    /**
     * Sends a command on the open link. Throws [LinkClosedException] when the antenna is not ready. An `E_LOCKED`
     * answer while the app thinks it is open means a `sec.state` event was lost: the vault is opened again.
     */
    suspend fun call(cmd: String, args: JsonObject? = null): JsonObject {
        val c = client ?: throw LinkClosedException()
        if (!state.value.ready && !cmd.startsWith("sys.")) throw LinkClosedException()
        try {
            return c.call(cmd, args)
        } catch (e: LinkCommandException) {
            if (e.error.code in LOCKED_CODES && state.value.ready && resetBoot == null) scope.launch { relock() }
            throw e
        }
    }

    private suspend fun runSession() {
        val link = linkProvider()
        val previous = state.value
        _state.value = AntennaState(phase = AntennaPhase.SEARCHING, kind = link.kind, lastLock = previous.lastLock,
            lastDeviceError = previous.lastDeviceError, unlockCount = previous.unlockCount)
        val found = link.find() ?: return fail(AntennaError.NO_DEVICE)
        _state.update { it.copy(phase = AntennaPhase.DEVICE_FOUND, deviceLabel = found.label) }
        val session = try {
            link.open(found)
        } catch (e: LinkOpenException) {
            return fail(
                when (e.error) {
                    OpenError.NO_DEVICE -> AntennaError.NO_DEVICE
                    OpenError.PERMISSION_DENIED -> AntennaError.PERMISSION_DENIED
                    OpenError.OPEN_FAILED -> AntennaError.OPEN_FAILED
                },
            )
        }
        _state.update { it.copy(phase = AntennaPhase.CONNECTING, activity = AntennaActivity.HELLO) }
        val c = LinkClient(session, scope, log)
        client = c
        val eventJob = scope.launch { for (event in c.events) handleEvent(event) }
        try {
            val hello = helloWithRetry(c)
            val proto = hello.str("proto") ?: "0.0"
            if (proto.substringBefore('.') != PROTO_MAJOR) return fail(AntennaError.PROTO_MISMATCH)
            hello.str("device_id") ?: return fail(AntennaError.NO_INFO)
            // Link spec 14 item 2: images that still report a mock device have no real authentication.
            if (hello.bool("mock") == true) return fail(AntennaError.MOCK_FIRMWARE)
            bringUp(c, hello)
            val heartbeat = scope.launch { heartbeat(c) }
            val cause = c.closed.await()
            heartbeat.cancel()
            // Somebody else may already have ended the session with a reason (a failed re-unlock); keep it.
            if (state.value.phase != AntennaPhase.ERROR) fail(if (cause != null) AntennaError.LOST else null)
        } catch (e: UnlockFailure) {
            log("unlock failed: ${e.code}")
            failUnlock(c, e)
        } catch (_: LinkTimeoutException) {
            fail(AntennaError.NO_INFO)
        } catch (_: LinkClosedException) {
            fail(AntennaError.LOST)
        } catch (e: LinkCommandException) {
            log("session failed: ${e.error.code}")
            fail(if (e.error.code == "E_PROTO_MISMATCH") AntennaError.PROTO_MISMATCH else AntennaError.REJECTED, e.error.code)
        } finally {
            eventJob.cancel()
        }
    }

    /**
     * From a `sys.hello` answer to "ready": open the vault, apply the text codec, read identity, radio, mesh,
     * status and lab settings, then tell the listener. Used on connect, after an auto-lock or restart, and after a
     * factory reset.
     */
    private suspend fun bringUp(c: LinkClient, hello: JsonObject) {
        val deviceId = hello.str("device_id")!!
        val maxText = hello.obj("limits")?.int("max_text_bytes") ?: MessageLimits.DEFAULT_MAX_TEXT_BYTES
        val chip = hello.str("chip")?.substringBefore('-')
        val deviceState = hello.str("state")
        val labRadio = hello.bool("lab_radio") == true
        _state.update {
            it.copy(deviceId = deviceId, maxTextBytes = maxText, groups = GroupSupport.parse(hello), fwVersion = hello.obj("fw")?.str("version"),
                fwBuild = hello.obj("fw")?.str("build"), deviceLabel = chip ?: it.deviceLabel,
                textCodec = hello.str("text_codec") ?: MessageLimits.CODEC_RAW,
                maxTextInputBytes = hello.obj("limits")?.int("max_text_input_bytes") ?: maxText,
                deviceState = deviceState, labRadio = labRadio, radioDiagnostics = hello.bool("radio_diagnostics") == true,
                activity = when (deviceState) {
                    "unprovisioned" -> AntennaActivity.PROVISIONING
                    "locked" -> if (it.activity == AntennaActivity.RELOCKING) AntennaActivity.RELOCKING else AntennaActivity.UNLOCKING
                    else -> it.activity
                })
        }
        unlock(c, hello)
        if (labRadio) selectTextCodec(c)
        val radio = c.call("radio.get")
        val identity = c.call("id.get")
        identity.str("public_key")?.let { settings.put(Settings.OWN_PUBLIC_KEY, it) }
        // Optional reads: older images may not know them, and none of them is needed to send messages.
        val status = optional(c, "sys.status")
        val mesh = optional(c, "mesh.get")
        val policy = optional(c, "sec.get_policy")
        val lab = if (labRadio) optional(c, "lab.get")?.let(LabConfig::parse) else null
        _state.update {
            it.copy(phase = AntennaPhase.READY, radio = RadioConfig.parse(radio), nodeId = identity.str("node_id"),
                deviceName = identity.str("name"), deviceState = "unlocked", activity = null,
                mesh = mesh?.let(MeshConfig::parse), lab = lab,
                textCodec = lab?.textCodec ?: it.textCodec, maxTextInputBytes = lab?.maxTextInputBytes ?: it.maxTextInputBytes,
                radioRunning = status?.obj("radio")?.bool("running"),
                radioError = status?.obj("radio")?.int("last_error")?.takeIf { e -> e != 0 },
                vaultStatus = status?.obj("storage")?.str("vault_status"),
                droppedEvents = status?.obj("queue")?.int("dropped_events"),
                secureBoot = status?.obj("security")?.bool("secure_boot"),
                flashEncryption = status?.obj("security")?.bool("flash_encryption"),
                lockTimeoutS = policy?.int("lock_timeout_s"),
                error = null, errorCode = null, lockReason = null, retryAt = null,
                unlockCount = if (deviceState == "unlocked") it.unlockCount else it.unlockCount + 1)
        }
        listener.onReady(this, deviceId)
    }

    private suspend fun optional(c: LinkClient, cmd: String, args: JsonObject? = null): JsonObject? = try {
        c.call(cmd, args)
    } catch (e: LinkCommandException) {
        log("$cmd: ${e.error.code}")
        null
    } catch (_: LinkTimeoutException) {
        null
    }

    private fun fail(error: AntennaError?, code: String? = null, retryAt: Long? = null) {
        client?.close()
        client = null
        _state.update {
            it.copy(phase = if (error == null) AntennaPhase.DISCONNECTED else AntennaPhase.ERROR, error = error, errorCode = code,
                activity = null, retryAt = retryAt)
        }
        if (retryAt != null) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay((retryAt - now()).coerceAtLeast(0))
                if (state.value.error == AntennaError.RATE_LIMITED) connect()
            }
        }
    }

    /**
     * Ends the session after a failed unlock. A rate limit gets its estimated end and an automatic retry then; an
     * unreadable vault gets the antenna's `vault_status` (`sys.status` works in every state) for the note.
     */
    private suspend fun failUnlock(c: LinkClient, e: UnlockFailure) {
        when (e.error) {
            AntennaError.RATE_LIMITED -> {
                rateLimitedInRow++
                fail(e.error, e.code, AuthBackoff.retryAt(now(), authFailures, lastAuthFailureAt, rateLimitedInRow))
            }
            AntennaError.VAULT_ERROR -> {
                val vault = optional(c, "sys.status")?.obj("storage")?.str("vault_status")
                _state.update { it.copy(vaultStatus = vault ?: it.vaultStatus) }
                fail(e.error, e.code)
            }
            else -> fail(e.error, e.code)
        }
    }

    /** Link spec 3.3: commands sent during boot are lost, so retry `sys.hello` every 500 ms. */
    private suspend fun helloWithRetry(c: LinkClient): JsonObject {
        repeat(HELLO_ATTEMPTS) {
            try {
                return c.call("sys.hello", HELLO_ARGS, timeoutMs = 500)
            } catch (_: LinkTimeoutException) {
                // device still booting
            }
        }
        throw LinkTimeoutException("sys.hello")
    }

    /**
     * `sys.ping` about once per second (link spec 2, "Heartbeat and lock"): handled commands keep the antenna
     * from locking itself after `lock_timeout_s`. Three unanswered pings in a row while the antenna is open end
     * the session; during an unlock or a factory-reset restart missing answers are expected.
     */
    private suspend fun heartbeat(c: LinkClient) {
        var missed = 0
        while (true) {
            delay(HEARTBEAT_MS)
            try {
                c.call("sys.ping", timeoutMs = PING_TIMEOUT_MS)
                missed = 0
            } catch (_: LinkTimeoutException) {
                log("heartbeat timeout")
                if (state.value.ready && resetBoot == null && ++missed >= MAX_MISSED_PINGS) {
                    fail(AntennaError.UNRESPONSIVE)
                    return
                }
            } catch (_: LinkCommandException) {
                missed = 0 // any answer means it is alive
            } catch (_: LinkClosedException) {
                return
            }
        }
    }

    /**
     * Opens the vault (link spec 4). Each `sys.hello` session allows one attempt, so a state that changed in
     * between (`E_NO_SESSION`, `E_NOT_PROVISIONED`, `E_ALREADY_PROVISIONED`) gets one more try with a fresh
     * `sys.hello`; `E_ALREADY_UNLOCKED` means the vault is open. Throws [UnlockFailure].
     */
    private suspend fun unlock(c: LinkClient, firstHello: JsonObject) = unlockMutex.withLock {
        var hello = firstHello
        for (attempt in 1..2) {
            try {
                openVault(c, hello)
                return@withLock
            } catch (e: LinkCommandException) {
                val code = e.error.code
                if (code == "E_ALREADY_UNLOCKED") return@withLock
                if (attempt == 1 && code in RETRY_WITH_NEW_HELLO) {
                    hello = c.call("sys.hello", HELLO_ARGS)
                    continue
                }
                throw UnlockFailure(errorFor(code), code)
            }
        }
    }

    /**
     * Provision a fresh antenna, unlock with this phone's Keystore token, or, after a backup import,
     * unlock once with the old phone's token and rotate to ours (link spec 4, `sec.rotate_token`).
     */
    private suspend fun openVault(c: LinkClient, hello: JsonObject) {
        val deviceId = hello.str("device_id")!!
        val deviceBytes = OgCrypto.unhex(deviceId)
        when (hello.str("state")) {
            "unlocked" -> return
            "unprovisioned" -> return provision(c, hello, deviceId, deviceBytes)
        }
        try {
            authCall(c, "sec.unlock", wrapArgs(hello, tokenSource.token(deviceBytes), deviceBytes))
            // A rotation whose answer was lost: the antenna already has our token, the old phone's is done with.
            if (transferTokenFor(deviceId) != null) clearTransferToken()
            return
        } catch (e: LinkCommandException) {
            if (e.error.code != "E_UNLOCK_FAILED") throw e
        }
        val transfer = transferTokenFor(deviceId) ?: throw UnlockFailure(AntennaError.UNLOCK_FAILED, "E_UNLOCK_FAILED")
        // The backup carries the old phone's token for exactly this device, not its Keystore key.
        authCall(c, "sec.unlock", wrapArgs(c.call("sys.hello", HELLO_ARGS), transfer, deviceBytes))
        // Both tokens stay known until the antenna confirmed the rotation: if the link drops in between, the next
        // connect tries ours first and falls back to the old phone's.
        c.call("sec.rotate_token", wrapArgs(c.call("sys.hello", HELLO_ARGS), tokenSource.token(deviceBytes), deviceBytes))
        clearTransferToken()
    }

    /** An authenticating command: counts `E_UNLOCK_FAILED` for the [AuthBackoff] estimate, success clears it. */
    private suspend fun authCall(c: LinkClient, cmd: String, args: JsonObject): JsonObject {
        try {
            val result = c.call(cmd, args)
            authFailures = 0
            rateLimitedInRow = 0
            return result
        } catch (e: LinkCommandException) {
            if (e.error.code == "E_UNLOCK_FAILED") {
                authFailures++
                lastAuthFailureAt = now()
                rateLimitedInRow = 0
            }
            throw e
        }
    }

    /**
     * Sets up an antenna without a vault: a new board, a reflashed one or one after a factory reset.
     * The vault then only opens with this phone's token. The app never stores that token (it is derived
     * from the Keystore key and the device_id each time), but a token from a backup import for this
     * antenna can never open the new vault, so it goes. The contacts the antenna held are gone with the old
     * vault; [Messaging] marks them for pairing again when it syncs the contact list.
     */
    private suspend fun provision(c: LinkClient, hello: JsonObject, deviceId: String, deviceBytes: ByteArray) {
        val name = settings.get(Settings.DISPLAY_NAME)?.takeIf(MessageLimits::isValidName)
        authCall(c, "sec.provision", wrapArgs(hello, tokenSource.token(deviceBytes), deviceBytes, name))
        if (settings.get(Settings.TRANSFER_DEVICE) == deviceId) clearTransferToken()
    }

    private suspend fun transferTokenFor(deviceId: String): ByteArray? {
        val transfer = settings.get(Settings.TRANSFER_TOKEN) ?: return null
        return if (settings.get(Settings.TRANSFER_DEVICE) == deviceId) OgCrypto.unb64(transfer) else null
    }

    private suspend fun clearTransferToken() {
        settings.remove(Settings.TRANSFER_TOKEN)
        settings.remove(Settings.TRANSFER_DEVICE)
    }

    /**
     * Applies the build's TEXT codec flag to a lab radio image (`lab.set`, persisted on the antenna). The
     * antenna compresses; the app only needs the codec to count what fits (see [MessageLimits.onAirTextBytes]).
     */
    private suspend fun selectTextCodec(c: LinkClient) {
        val wanted = BuildConfig.TEXT_CODEC
        if (wanted.isEmpty() || wanted == state.value.textCodec) return
        try {
            val result = c.call("lab.set", buildJsonObject { put("text_codec", wanted) })
            _state.update {
                it.copy(textCodec = result.str("text_codec") ?: it.textCodec,
                    maxTextInputBytes = result.int("max_text_input_bytes") ?: it.maxTextInputBytes)
            }
        } catch (e: LinkCommandException) {
            log("text codec $wanted not applied: ${e.error.code}")
        }
    }

    // ------------------------------------------------------------------ settings on the antenna

    /** `mesh.set` with the fields in [changes] (persisted on the antenna); returns and keeps the whole object. */
    suspend fun setMesh(changes: JsonObject): MeshConfig {
        val mesh = MeshConfig.parse(call("mesh.set", changes))
        _state.update { it.copy(mesh = mesh) }
        return mesh
    }

    /** `lab.set` on a lab radio image (`text_codec`, `ignore_nodes`); returns and keeps the result. */
    suspend fun setLab(changes: JsonObject): LabConfig {
        val lab = LabConfig.parse(call("lab.set", changes))
        _state.update {
            it.copy(lab = lab, textCodec = lab.textCodec, maxTextInputBytes = lab.maxTextInputBytes ?: it.maxTextInputBytes)
        }
        return lab
    }

    /** `radio.stats`, cleared afterwards with [reset]. */
    suspend fun radioStats(reset: Boolean = false): JsonObject =
        call("radio.stats", if (reset) buildJsonObject { put("reset", true) } else null)

    /** Reads radio, mesh, status and lab settings again, for the advanced screen. */
    suspend fun refreshInfo() {
        val c = client?.takeIf { state.value.ready } ?: return
        val radio = optional(c, "radio.get")
        val mesh = optional(c, "mesh.get")
        val status = optional(c, "sys.status")
        val lab = if (state.value.labRadio) optional(c, "lab.get")?.let(LabConfig::parse) else null
        _state.update {
            it.copy(radio = radio?.let(RadioConfig::parse) ?: it.radio, mesh = mesh?.let(MeshConfig::parse) ?: it.mesh,
                lab = lab ?: it.lab,
                radioRunning = status?.obj("radio")?.bool("running") ?: it.radioRunning,
                radioError = if (status != null) status.obj("radio")?.int("last_error")?.takeIf { e -> e != 0 } else it.radioError,
                vaultStatus = status?.obj("storage")?.str("vault_status") ?: it.vaultStatus,
                droppedEvents = status?.obj("queue")?.int("dropped_events") ?: it.droppedEvents)
        }
    }

    private fun wrapArgs(
        hello: JsonObject,
        token: ByteArray,
        deviceId: ByteArray,
        name: String? = null,
        purpose: WrapPurpose = WrapPurpose.UNLOCK,
    ): JsonObject {
        val sessionInfo = hello.obj("session") ?: throw LinkCommandException("sys.hello", LinkError("E_NO_SESSION", null, null))
        val wrapped = TokenWrap.wrap(
            token = token,
            deviceId = deviceId,
            deviceEphPub = OgCrypto.unb64(sessionInfo.str("device_eph_pub")!!),
            nonce = OgCrypto.unb64(sessionInfo.str("nonce")!!),
            purpose = purpose,
        )
        return buildJsonObject {
            put("app_eph_pub", OgCrypto.b64(wrapped.appEphPub))
            put("wrapped_token", OgCrypto.b64(wrapped.wrapped))
            if (name != null) put("name", name)
        }
    }

    /** This phone's unlock token for a device, exported in a backup so a new phone can take over (spec 6.10). */
    fun tokenFor(deviceId: String): ByteArray = tokenSource.token(OgCrypto.unhex(deviceId))

    /**
     * Erases the connected antenna (link spec 11, `method: "app"`): vault, identity, contacts and settings
     * go, the device_id stays. This phone proves it is the owner with its token wrapped under the reset
     * label; after a backup import the old phone's token is tried too. On success the antenna locks,
     * restarts unprovisioned, and this manager sets it up again for this phone in the background.
     */
    suspend fun factoryReset(): ResetResult {
        val c = client ?: return ResetResult.NOT_CONNECTED
        val deviceId = state.value.deviceId?.takeIf { state.value.ready } ?: return ResetResult.NOT_CONNECTED
        val deviceBytes = OgCrypto.unhex(deviceId)
        val tokens = listOfNotNull(tokenSource.token(deviceBytes), transferTokenFor(deviceId))
        val boot = CompletableDeferred<Unit>()
        resetBoot = boot
        // From the moment the command is on the wire, a lost link no longer proves that nothing happened.
        var sent = false
        try {
            for (token in tokens) {
                val args = buildJsonObject {
                    put("confirm", "ERASE")
                    put("method", "app")
                    wrapArgs(c.call("sys.hello", HELLO_ARGS), token, deviceBytes, purpose = WrapPurpose.RESET)
                        .forEach { (k, v) -> put(k, v) }
                }
                try {
                    sent = true
                    authCall(c, "sys.factory_reset", args)
                } catch (e: LinkCommandException) {
                    sent = false
                    log("factory reset refused: ${e.error.code}")
                    when (e.error.code) {
                        "E_UNLOCK_FAILED" -> continue
                        "E_RATE_LIMITED" -> return ResetResult.RATE_LIMITED.also { resetBoot = null }
                        // Nils' firmware has no button reset (link spec 11); an image without the command is older.
                        "E_UNKNOWN_CMD" -> return ResetResult.UNSUPPORTED.also { resetBoot = null }
                        else -> return ResetResult.FAILED.also { resetBoot = null }
                    }
                }
                clearTransferToken()
                _state.update {
                    it.copy(phase = AntennaPhase.CONNECTING, activity = AntennaActivity.RESETTING, lockReason = "factory_reset")
                }
                scope.launch { setUpAfterReset(c, boot) }
                return ResetResult.DONE
            }
            resetBoot = null
            return ResetResult.WRONG_PHONE
        } catch (_: LinkTimeoutException) {
            resetBoot = null
            return if (sent) ResetResult.UNKNOWN else ResetResult.FAILED
        } catch (_: LinkClosedException) {
            resetBoot = null
            return if (sent) ResetResult.UNKNOWN else ResetResult.NOT_CONNECTED
        }
    }

    /**
     * After [factoryReset] answered DONE: waits until the antenna restarted and is set up again for this phone.
     * False when the session ended in an error or nothing happened within [SETUP_AFTER_RESET_TIMEOUT_MS]; the
     * antenna has accepted the reset then and finishes it on its own (link spec 11, the journal resumes it).
     */
    suspend fun awaitSetUp(): Boolean {
        val end = withTimeoutOrNull(SETUP_AFTER_RESET_TIMEOUT_MS) {
            state.first { it.phase == AntennaPhase.READY || it.phase == AntennaPhase.ERROR || it.phase == AntennaPhase.DISCONNECTED }
        }
        return end?.phase == AntennaPhase.READY
    }

    /** Waits for the restart after a reset (`sys.boot`), then provisions the empty antenna again. */
    private suspend fun setUpAfterReset(c: LinkClient, boot: CompletableDeferred<Unit>) {
        try {
            // The serial port of the Heltec V3 stays open over the restart; without sys.boot, sys.hello retries decide.
            withTimeoutOrNull(RESET_BOOT_TIMEOUT_MS) { boot.await() }
            resetBoot = null
            bringUp(c, helloWithRetry(c))
        } catch (e: UnlockFailure) {
            failUnlock(c, e)
        } catch (_: LinkTimeoutException) {
            fail(AntennaError.NO_INFO)
        } catch (_: LinkClosedException) {
            fail(AntennaError.LOST)
        } catch (e: LinkCommandException) {
            fail(AntennaError.REJECTED, e.error.code)
        } finally {
            resetBoot = null
        }
    }

    private suspend fun handleEvent(event: Incoming.Event) {
        val reset = resetBoot
        when (event.name) {
            "sys.boot" -> when {
                reset != null -> {
                    listener.onDeviceRebooted()
                    reset.complete(Unit)
                }
                state.value.ready -> {
                    // The device restarted under us: its queue is gone and it is locked again.
                    _state.update { it.copy(lastLock = LockEvent(REASON_RESTART, now())) }
                    listener.onDeviceRebooted()
                    relock()
                }
            }
            "sec.state" -> when (event.data.str("state")) {
                "locked" -> {
                    val reason = event.data.str("reason") ?: "lock"
                    _state.update { it.copy(lockReason = reason, lastLock = LockEvent(reason, now()), deviceState = "locked") }
                    if (reset == null && state.value.ready) relock()
                }
                "unlocked" -> _state.update { it.copy(deviceState = "unlocked") }
            }
            "error" -> {
                _state.update { it.copy(lastDeviceError = event.data.str("code")) }
                listener.onEvent(event)
            }
            else -> listener.onEvent(event)
        }
    }

    /**
     * Opens the vault again after an auto-lock, a restart or a lost `sec.state`; ends the session with the reason
     * when that fails. Runs once at a time, and not at all when a fresh `sys.hello` says the antenna is still open.
     */
    private suspend fun relock() {
        val c = client ?: return
        if (!relockMutex.tryLock()) return
        try {
            val hello = c.call("sys.hello", HELLO_ARGS)
            if (hello.str("state") == "unlocked" && state.value.ready) return
            _state.update { it.copy(phase = AntennaPhase.CONNECTING, activity = AntennaActivity.RELOCKING) }
            bringUp(c, hello)
        } catch (e: UnlockFailure) {
            log("re-unlock failed: ${e.code}")
            failUnlock(c, e)
        } catch (e: LinkCommandException) {
            log("re-unlock failed: ${e.error.code}")
            fail(AntennaError.REJECTED, e.error.code)
        } catch (_: LinkTimeoutException) {
            fail(AntennaError.NO_INFO)
        } catch (_: LinkClosedException) {
            // runSession reports the lost link
        } finally {
            relockMutex.unlock()
        }
    }

    companion object {
        /** [LockEvent.reason] for a restart noticed through `sys.boot` (not a `sec.state` reason). */
        const val REASON_RESTART = "restart"

        private const val PROTO_MAJOR = "1"
        // 8 s: an image that plays its OLED boot animation in setup() answers only after it ends.
        private const val HELLO_ATTEMPTS = 16
        private const val HEARTBEAT_MS = 1_000L
        private const val PING_TIMEOUT_MS = 2_000L
        private const val MAX_MISSED_PINGS = 3
        private const val RESET_BOOT_TIMEOUT_MS = 15_000L

        // sys.boot wait, up to 8 s of sys.hello retries, then sec.provision and the first reads.
        private const val SETUP_AFTER_RESET_TIMEOUT_MS = 40_000L
        private val HELLO_ARGS = buildJsonObject { put("app", "offgrid-android"); put("app_proto", "1.1") }
        private val RETRY_WITH_NEW_HELLO = setOf("E_NO_SESSION", "E_NOT_PROVISIONED", "E_ALREADY_PROVISIONED")
        private val LOCKED_CODES = setOf("E_LOCKED", "E_NOT_PROVISIONED")

        private fun errorFor(code: String): AntennaError = when (code) {
            "E_UNLOCK_FAILED" -> AntennaError.UNLOCK_FAILED
            "E_RATE_LIMITED" -> AntennaError.RATE_LIMITED
            "E_NO_SESSION" -> AntennaError.NO_SESSION
            "E_INTERNAL" -> AntennaError.VAULT_ERROR
            "E_PROTO_MISMATCH" -> AntennaError.PROTO_MISMATCH
            else -> AntennaError.REJECTED
        }
    }
}
