package ch.offgrid.android.link.sim

import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.crypto.TokenWrap
import ch.offgrid.android.crypto.WrapPurpose
import ch.offgrid.android.domain.MessageLimits
import ch.offgrid.android.link.FramedLine
import ch.offgrid.android.link.LineFramer
import ch.offgrid.android.link.LinkCodec
import ch.offgrid.android.link.Request
import ch.offgrid.android.link.bool
import ch.offgrid.android.link.double
import ch.offgrid.android.link.int
import ch.offgrid.android.link.long
import ch.offgrid.android.link.obj
import ch.offgrid.android.link.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

private class CommandError(val code: String, message: String, val field: String? = null) : Exception(message)

/** Mesh timing of the simulated antenna. Defaults follow the lab.3 contract; tests shorten them. */
data class SimTiming(
    val firstTxMs: Long = 350,
    val ackWaitMs: Long = 900,
    val retryMs: Long = 2_000,
    val retries: Int = 3,
    /** Lab.3 item 5: after the last retry a message waits and is sent again this often ... */
    val resendMs: Long = 60_000,
    /** ... or at once when its contact sends anything, until this long after it was queued; then `failed timeout`. */
    val parkMs: Long = 30 * 60_000L,
    /** A `group.sync` takes this long to reach the member, and a simulated invitation to arrive, after the send. */
    val groupSyncMs: Long = 1_500,
)

/** A message in the device's pending queue (`msg.pending` item). */
private class SimPending(val ref: Int, val to: Int, val type: String, val ctr: Int?, val createdAt: Long, val group: Int? = null) {
    var state = "queued"
    var retry = 0
    var job: Job? = null

    /** A packet from the contact arrived: a waiting message is sent again at once. */
    val wake = Channel<Unit>(Channel.CONFLATED)
}

/** A final outcome kept for `msg.pending.recent` (lab.3 item 5). */
private data class SimRecent(val ref: Int, val state: String, val reason: String?)

/**
 * In-process stand-in for the Heltec V3 firmware. It speaks the JSON Lines link protocol
 * byte-for-byte (requests in, responses and events out) and models the device states, the
 * vault unlock, pairing, the pending queue with ACKs and retries, and a few virtual peers.
 *
 * With [labRadio] it behaves like our lab image 0.3.0-lab.3 (pairing salt kept per window, `waiting`,
 * `recent`, `lab.*` with OGZ1 and the ignore list); without it like Nils' team firmware 0.3.0 (a new salt on
 * every `pair.begin`, `failed timeout` after the retries, no `lab.*`).
 *
 * It does not model the radio bytes (AES-CCM, flooding); those belong to the firmware. Peers are
 * resolved logically, which is enough to exercise every app path without hardware. A peer whose NodeID is
 * on the ignore list counts as out of range, as there are no relays in the simulation.
 */
class SimulatedHeltec(
    private val store: SimFlashStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timing: SimTiming = SimTiming(),
    val labRadio: Boolean = true,
) {
    private var flash: SimFlash = store.load() ?: SimFlash(deviceId = OgCrypto.hex(OgCrypto.randomBytes(6)))
        .also(store::save)

    private var state: String = if (flash.provisioned) "locked" else "unprovisioned"
    private var bootAt = clock()
    private var identityPrivate: ByteArray? = null
    private var session: Pair<ByteArray, ByteArray>? = null // device_eph_private, nonce
    private var pairing: Pair<ByteArray, Long>? = null // salt, expiry
    /** Lab: peer codes completed in the current window, with the time they may be used again. */
    private val usedPeerCodes = HashMap<String, Long>()
    private var failedUnlocks = 0
    private var rateLimitedUntil = 0L
    private var lastActivity = clock()
    /** Lab.3: `msg_ref` starts at a random value per boot, so a ref from an earlier boot never matches. */
    private var nextRef = randomRef()
    private val pending = LinkedHashMap<Int, SimPending>()
    private val recent = ArrayDeque<SimRecent>()
    private var txPackets = 0
    private var groupRxCtr = 0
    private var rxPackets = 0
    private var acksReceived = 0
    private var retriesScheduled = 0
    private var resends = 0
    private var resetAuthorized = false

    /** Events of a command that the firmware writes after its response line. */
    private val afterResponseEvents = mutableListOf<ByteArray>()
    private var output: Channel<ByteArray>? = null
    private val framer = LineFramer(dropFirstPartialLine = false)
    private var watchdog: Job? = null
    private val lock = Any()

    val deviceIdHex: String get() = flash.deviceId

    // ---------------------------------------------------------------- transport side

    /** Called when the app opens the port. The device sends an empty line and `sys.boot` (spec 3.3, 8). */
    fun attach(): Channel<ByteArray> = synchronized(lock) {
        output?.close()
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        output = channel
        channel.trySend("\n".toByteArray())
        emitEvent("sys.boot", bootData("power_on"))
        startWatchdog()
        channel
    }

    fun detach() = synchronized(lock) {
        output?.close()
        output = null
    }

    /** Simulates pulling the cable: the app sees the stream end with an error. */
    fun unplug() = synchronized(lock) {
        output?.close(java.io.IOException("USB device detached"))
        output = null
    }

    fun receive(bytes: ByteArray) {
        if (hung) return
        val lines = synchronized(lock) { framer.feed(bytes) }
        for (line in lines) {
            if (line !is FramedLine.Json) {
                if (line is FramedLine.TooLong) emitEvent("error", buildJsonObject {
                    put("code", "E_LINE_TOO_LONG"); put("message", "line exceeded 2048 bytes and was dropped")
                })
                continue
            }
            val request = LinkCodec.decodeRequest(line.text)
            if (request == null) {
                send(LinkCodec.encodeResponseError(null, "E_PARSE", "invalid JSON"))
                continue
            }
            synchronized(lock) { lastActivity = clock() }
            val reply = try {
                LinkCodec.encodeResponseOk(request.id, synchronized(lock) { execute(request) })
            } catch (e: CommandError) {
                LinkCodec.encodeResponseError(request.id, e.code, e.message ?: e.code, e.field)
            }
            send(reply)
            synchronized(lock) {
                afterResponseEvents.forEach(::send)
                afterResponseEvents.clear()
                afterResponse(request)
            }
        }
    }

    private fun send(bytes: ByteArray) {
        output?.trySend(bytes)
    }

    private fun uptime(): Long = clock() - bootAt

    private fun emitEvent(name: String, data: JsonObject) = send(LinkCodec.encodeEvent(name, uptime(), data))

    private fun emitAfterResponse(name: String, data: JsonObject) {
        afterResponseEvents += LinkCodec.encodeEvent(name, uptime(), data)
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                synchronized(lock) {
                    val timeout = flash.lockTimeoutS
                    if (state == "unlocked" && timeout > 0 && clock() - lastActivity > timeout * 1_000L) {
                        lockVault("heartbeat_timeout")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- command dispatch

    private fun execute(r: Request): JsonObject {
        val a = r.args
        return when (r.cmd) {
            "sys.hello" -> hello()
            "sys.status" -> status()
            "sys.ping" -> buildJsonObject { put("uptime_ms", uptime()) }
            "sys.reboot" -> buildJsonObject { put("rebooting", true) }
            "sys.factory_reset" -> factoryReset(a)
            "sys.set_log_level" -> buildJsonObject { put("level", a.str("level") ?: "info") }
            "sec.provision" -> provision(a)
            "sec.unlock" -> unlock(a)
            "sec.lock" -> { requireUnlocked(); lockVault("lock"); buildJsonObject { put("state", "locked") } }
            "sec.rotate_token" -> rotate(a)
            "sec.get_policy" -> { requireUnlocked(); buildJsonObject { put("lock_timeout_s", flash.lockTimeoutS) } }
            "sec.set_policy" -> {
                requireUnlocked()
                val t = a.int("lock_timeout_s") ?: throw CommandError("E_INVALID_ARG", "lock_timeout_s missing", "lock_timeout_s")
                if (t !in 0..3600) throw CommandError("E_INVALID_ARG", "lock_timeout_s out of range", "lock_timeout_s")
                save(flash.copy(lockTimeoutS = t))
                buildJsonObject { put("lock_timeout_s", t) }
            }
            "id.get" -> { requireUnlocked(); identity() }
            "id.set_name" -> {
                requireUnlocked()
                val name = validName(a.str("name"))
                save(flash.copy(name = name))
                identity()
            }
            "pair.begin" -> pairBegin()
            "pair.cancel" -> { requireUnlocked(); pairing = null; buildJsonObject { put("cancelled", true) } }
            "pair.complete" -> pairComplete(a)
            "contact.list" -> contactList(a)
            "contact.get" -> { requireUnlocked(); buildJsonObject { put("contact", contactJson(contact(a))) } }
            "contact.rename" -> {
                requireUnlocked()
                val c = contact(a)
                val name = validName(a.str("name"))
                updateContact(c.copy(name = name))
                buildJsonObject { put("contact", contactJson(c.copy(name = name))) }
            }
            "contact.delete" -> {
                requireUnlocked()
                val c = contact(a)
                if (a.bool("confirm") != true) throw CommandError("E_CONFIRM_REQUIRED", "confirm must be true", "confirm")
                pending.values.filter { it.to == c.contactId }
                    .forEach { finish(it, "failed", "contact_deleted", afterResponse = true, stop = true) }
                save(flash.copy(contacts = flash.contacts.filter { it.contactId != c.contactId }))
                buildJsonObject { put("deleted", true) }
            }
            "msg.send" -> msgSend(a)
            "msg.cancel" -> {
                requireUnlocked()
                val ref = a.long("msg_ref")?.takeIf { it in 1..0xFFFFFFFFL }
                    ?: throw CommandError("E_INVALID_ARG", "msg_ref must be a positive integer", "msg_ref")
                val p = pending[ref.toInt()] ?: throw CommandError("E_INVALID_ARG", "message is not pending", "msg_ref")
                finish(p, "cancelled", null, afterResponse = true, stop = true)
                buildJsonObject { put("state", "cancelled") }
            }
            "msg.pending" -> { requireUnlocked(); pendingJson() }
            "radio.get" -> radioJson()
            "radio.set" -> { requireUnlocked(); radioSet(a) }
            "radio.stats" -> { requireUnlocked(); radioStats(a.bool("reset") == true) }
            "lab.get" -> { requireLab(); requireUnlocked(); labJson() }
            "lab.set" -> { requireLab(); requireUnlocked(); labSet(a) }
            "mesh.get" -> meshJson()
            "mesh.set" -> { requireUnlocked(); meshSet(a) }
            "led.get" -> buildJsonObject { put("enabled", true); put("brightness", 128) }
            "group.create" -> groupCreate(a)
            "group.list" -> groupList()
            "group.get" -> { requireUnlocked(); buildJsonObject { put("group", groupJson(group(a), withMembers = true)) } }
            "group.sync" -> groupSync(a)
            "group.add" -> groupAdd(a)
            "group.remove" -> groupRemove(a)
            "group.send" -> groupSend(a)
            "group.delete" -> groupDelete(a)
            else -> throw CommandError("E_UNKNOWN_CMD", "unknown command ${r.cmd}", "cmd")
        }
    }

    /** Side effects that must happen after the response line (spec: response first, then events). */
    private fun afterResponse(r: Request) {
        when (r.cmd) {
            "sys.reboot" -> reboot("software")
            "sys.factory_reset" -> if (resetAuthorized) eraseAndRestart()
        }
    }

    // ---------------------------------------------------------------- system and security

    private val fwVersion: String get() = if (labRadio) FW_VERSION else TEAM_FW_VERSION

    private fun bootData(reason: String) = buildJsonObject {
        put("device_id", flash.deviceId); put("fw", fwVersion); put("proto", PROTO); put("state", state)
        put("mock", false); put("radio_diagnostics", false); put("reset_reason", reason)
    }

    /** A restart loses the RAM queue without any event; the app learns it from `sys.boot` and `msg.pending`. */
    private fun reboot(reason: String) {
        pending.values.forEach { it.job?.cancel() }
        pending.clear()
        recent.clear()
        nextRef = randomRef()
        state = if (flash.provisioned) "locked" else "unprovisioned"
        identityPrivate = null
        session = null
        pairing = null
        usedPeerCodes.clear()
        resetAuthorized = false
        bootAt = clock()
        emitEvent("sys.boot", bootData(reason))
    }

    /**
     * Link spec 6.11 with `method: "app"`: the reset needs this phone's token wrapped with the reset label.
     * The firmware locks, answers `{pending: true}`, erases `og_vault` and `og_cfg` and restarts unprovisioned.
     */
    private fun factoryReset(a: JsonObject): JsonObject {
        if (a.str("confirm") != "ERASE") throw CommandError("E_CONFIRM_REQUIRED", "confirm must be ERASE", "confirm")
        val method = a.str("method")
        if (a.containsKey("method") && method != "app" && method != "master") {
            throw CommandError("E_INVALID_ARG", "method must be app or master", "method")
        }
        if (method == "master") throw CommandError("E_UNLOCK_FAILED", "the simulator has no master key")
        authenticate(a, WrapPurpose.RESET)
        lockVault("factory_reset")
        resetAuthorized = true
        return buildJsonObject { put("pending", true) }
    }

    private fun eraseAndRestart() {
        store.wipe()
        // The virtual peers are people around the antenna, not part of its flash.
        save(SimFlash(deviceId = flash.deviceId, peers = flash.peers))
        failedUnlocks = 0
        rateLimitedUntil = 0
        reboot("software")
    }

    private fun hello(): JsonObject {
        val ephPrivate = OgCrypto.x25519PrivateKey()
        val nonce = OgCrypto.randomBytes(16)
        session = ephPrivate to nonce
        return buildJsonObject {
            put("device", "heltec_wifi_lora_32_v3"); put("chip", "ESP32-S3"); put("device_id", flash.deviceId)
            putJsonObject("fw") { put("version", fwVersion); put("build", "simulator") }
            put("proto", PROTO); put("state", state); put("mock", mockImage); put("radio_diagnostics", false)
            putJsonArray("features") { add(JsonPrimitive("groups")) }
            put("authentication", "x25519-aes256gcm"); put("config_storage", "nvs")
            if (labRadio) {
                put("lab_radio", true); put("text_codec", flash.textCodec)
            }
            putJsonObject("session") {
                put("device_eph_pub", OgCrypto.b64(OgCrypto.x25519PublicKey(ephPrivate)))
                put("nonce", OgCrypto.b64(nonce))
            }
            putJsonObject("limits") {
                put("max_line", 2048); put("max_contacts", 32)
                put("max_text_bytes", MessageLimits.DEFAULT_MAX_TEXT_BYTES); put("pending_queue", 8)
                put("max_groups", MAX_GROUPS); put("max_group_members", MAX_GROUP_MEMBERS)
                put("max_group_text_bytes", MessageLimits.DEFAULT_MAX_TEXT_BYTES)
                if (labRadio) put("max_text_input_bytes", maxTextInputBytes())
            }
            putJsonObject("recovery") { put("master", "ECDSA-P256-SHA256"); put("app_wrap_label", "og-reset-wrap-v1") }
        }
    }

    /** `sys.status` in every state (link spec 3). */
    private fun status() = buildJsonObject {
        put("state", state); put("uptime_ms", uptime())
        put("heap_free", 172304); put("battery_mv", JsonNull); put("mock", false)
        putJsonObject("radio") {
            val relayOnly = state == "locked" && flash.relayEnabled && flash.relayWhenLocked
            put("running", state == "unlocked" || relayOnly); put("relay_only", relayOnly); put("initialized", true); put("last_error", 0)
        }
        putJsonObject("storage") {
            put("config_status", "loaded"); put("writable", true)
            put("vault_status", if (flash.provisioned) "ready" else "missing"); put("generation", 1)
        }
        putJsonObject("security") { put("secure_boot", false); put("flash_encryption", false) }
        if (state == "unlocked") {
            putJsonObject("contacts") { put("used", flash.contacts.size); put("capacity", 32) }
            putJsonObject("groups") { put("used", flash.groups.size); put("capacity", MAX_GROUPS) }
            putJsonObject("queue") { put("pending", pending.size); put("capacity", 8); put("dropped_events", 0); put("dropped_acks", 0) }
        }
    }

    /** Consumes the `sys.hello` session and returns the unwrapped token (spec 6.7: single use). */
    private fun consumeToken(a: JsonObject, purpose: WrapPurpose = WrapPurpose.UNLOCK): ByteArray {
        val (ephPrivate, nonce) = session ?: throw CommandError("E_NO_SESSION", "call sys.hello first")
        session = null
        val appPub = a.str("app_eph_pub") ?: throw CommandError("E_INVALID_ARG", "app_eph_pub missing", "app_eph_pub")
        val wrapped = a.str("wrapped_token") ?: throw CommandError("E_INVALID_ARG", "wrapped_token missing", "wrapped_token")
        return try {
            TokenWrap.unwrap(OgCrypto.unb64(wrapped), OgCrypto.unhex(flash.deviceId), ephPrivate, OgCrypto.unb64(appPub), nonce, purpose)
        } catch (_: Exception) {
            throw CommandError("E_UNLOCK_FAILED", "unlock failed")
        }
    }

    /**
     * `Security::authenticate` for unlock and reset: every attempt consumes the session, the rate limit is
     * checked before the envelope, and a wrong envelope or token counts as a failure.
     */
    private fun authenticate(a: JsonObject, purpose: WrapPurpose) {
        if (session == null) throw CommandError("E_NO_SESSION", "call sys.hello first")
        if (clock() < rateLimitedUntil) {
            session = null
            throw CommandError("E_RATE_LIMITED", "too many failed attempts")
        }
        val token = try {
            consumeToken(a, purpose)
        } catch (e: CommandError) {
            if (e.code == "E_UNLOCK_FAILED") registerFailure()
            throw e
        }
        if (!flash.provisioned || vaultCheck(token, OgCrypto.unb64(flash.vaultSalt!!)) != flash.vaultCheck) {
            registerFailure()
            throw CommandError("E_UNLOCK_FAILED", "unlock failed")
        }
        failedUnlocks = 0
    }

    private fun vaultCheck(token: ByteArray, salt: ByteArray): String =
        OgCrypto.b64(OgCrypto.sha256(OgCrypto.hkdfSha256(token, salt, "og-vault-v1".toByteArray(), 32)))

    private fun provision(a: JsonObject): JsonObject {
        if (state != "unprovisioned") throw CommandError("E_ALREADY_PROVISIONED", "device already has a vault")
        val token = consumeToken(a)
        val name = a.str("name")?.let { validName(it) } ?: "Offgrid-${flash.deviceId.takeLast(4)}"
        val salt = OgCrypto.randomBytes(16)
        val identity = OgCrypto.x25519PrivateKey()
        save(flash.copy(provisioned = true, vaultSalt = OgCrypto.b64(salt), vaultCheck = vaultCheck(token, salt),
            identityPrivate = OgCrypto.b64(identity), name = name))
        identityPrivate = identity
        state = "unlocked"
        emitLater("sec.state", buildJsonObject { put("state", "unlocked"); put("reason", "provision") })
        return openedJson()
    }

    /** Result of `sec.provision` and `sec.unlock` (link spec 4). */
    private fun openedJson() = buildJsonObject {
        put("state", state); identity().forEach { (k, v) -> put(k, v) }; put("contacts", flash.contacts.size)
        putJsonObject("radio") { put("running", true); put("relay_only", false); put("last_error", 0) }
    }

    private fun unlock(a: JsonObject): JsonObject {
        when (state) {
            "unprovisioned" -> throw CommandError("E_NOT_PROVISIONED", "device has no vault")
            "unlocked" -> throw CommandError("E_ALREADY_UNLOCKED", "already unlocked")
        }
        authenticate(a, WrapPurpose.UNLOCK)
        identityPrivate = OgCrypto.unb64(flash.identityPrivate!!)
        state = "unlocked"
        lastActivity = clock()
        emitLater("sec.state", buildJsonObject { put("state", "unlocked"); put("reason", "unlock") })
        return openedJson()
    }

    private fun registerFailure() {
        failedUnlocks++
        if (failedUnlocks >= 3) {
            val backoff = (2_000L shl (failedUnlocks - 3)).coerceAtMost(60_000L)
            rateLimitedUntil = clock() + backoff
        }
    }

    private fun rotate(a: JsonObject): JsonObject {
        requireUnlocked()
        val token = consumeToken(a)
        val salt = OgCrypto.randomBytes(16)
        save(flash.copy(vaultSalt = OgCrypto.b64(salt), vaultCheck = vaultCheck(token, salt)))
        return buildJsonObject { put("state", state); identity().forEach { (k, v) -> put(k, v) } }
    }

    private fun lockVault(reason: String) {
        if (state != "unlocked") return
        failAllPending("locked")
        pairing = null
        identityPrivate = null
        state = "locked"
        emitEvent("sec.state", buildJsonObject { put("state", "locked"); put("reason", reason) })
    }

    /** Lab commands exist only on the lab image; the team firmware answers `E_UNKNOWN_CMD` (link spec 12). */
    private fun requireLab() {
        if (!labRadio) throw CommandError("E_UNKNOWN_CMD", "unknown command", "cmd")
    }

    private fun requireUnlocked() {
        when (state) {
            "unprovisioned" -> throw CommandError("E_NOT_PROVISIONED", "device has no vault")
            "locked" -> throw CommandError("E_LOCKED", "device is locked, call sec.unlock")
        }
    }

    private fun publicKey(): ByteArray = OgCrypto.x25519PublicKey(identityPrivate!!)
    private fun nodeId(): String = OgCrypto.nodeId(publicKey())

    private fun identity() = buildJsonObject {
        put("node_id", nodeId()); put("public_key", OgCrypto.b64(publicKey())); put("name", flash.name)
    }

    private fun validName(name: String?): String {
        val n = name?.trim() ?: throw CommandError("E_INVALID_ARG", "name missing", "name")
        if (n.isEmpty() || MessageLimits.utf8Bytes(n) > MessageLimits.MAX_NAME_BYTES) {
            throw CommandError("E_INVALID_ARG", "name must be 1 to 24 bytes", "name")
        }
        return n
    }

    // ---------------------------------------------------------------- pairing and contacts

    /**
     * `pair.begin`. The lab image keeps one salt per 5-minute window and draws a new one only when less than 60 s
     * are left, so opening the pairing screen again shows the same code; a completion ends it. Nils' firmware 0.3.0 draws a new salt on
     * every call (link spec 5, "Starts/replaces the device's five-minute pairing session").
     */
    private fun pairBegin(): JsonObject {
        requireUnlocked()
        val current = pairing
        val now = clock()
        val (salt, expiry) = if (labRadio && current != null && current.second - now > PAIR_RENEW_MS) {
            current
        } else {
            OgCrypto.randomBytes(16) to now + PAIR_WINDOW_MS
        }
        pairing = salt to expiry
        val payload = "OG1".toByteArray() + publicKey() + salt + (flash.name ?: "").toByteArray()
        return buildJsonObject {
            put("qr", OgCrypto.b64(payload)); put("qr_bytes", payload.size); put("expires_s", ((expiry - now) / 1000).coerceAtLeast(1))
        }
    }

    /** Salt of the open pairing session, for tests that check which code a completion used. */
    fun pairingSalt(): ByteArray? = synchronized(lock) { pairing?.first?.copyOf() }

    private fun pairComplete(a: JsonObject): JsonObject {
        requireUnlocked()
        val (_, expiry) = pairing ?: throw CommandError("E_NO_PAIRING", "call pair.begin first")
        if (clock() > expiry) throw CommandError("E_NO_PAIRING", "pairing expired")
        val qr = a.str("qr") ?: throw CommandError("E_INVALID_ARG", "qr missing", "qr")
        val parsed = parseQr(qr) ?: throw CommandError("E_INVALID_QR", "bad QR payload", "qr")
        // Lab: the same peer code is completed only once per window, so keys and counters are never reused.
        usedPeerCodes.entries.removeAll { it.value < clock() }
        if (labRadio && qr.trim() in usedPeerCodes) throw CommandError("E_NO_PAIRING", "peer code already used")
        val (peerPub, _, qrName) = parsed
        if (peerPub.contentEquals(publicKey())) throw CommandError("E_SELF_PAIR", "QR contains my own key")
        val pubB64 = OgCrypto.b64(peerPub)
        val existing = flash.contacts.firstOrNull { it.publicKey == pubB64 }
        if (existing != null && a.bool("replace") != true) throw CommandError("E_CONTACT_EXISTS", "already paired")
        if (existing == null && flash.contacts.size >= 32) throw CommandError("E_STORAGE_FULL", "no free contact slot")
        val name = a.str("name")?.let(::validName) ?: qrName.ifBlank { "Offgrid-${OgCrypto.nodeId(peerPub)}" }
        val id = existing?.contactId ?: ((1..32).first { slot -> flash.contacts.none { it.contactId == slot } })
        val peer = flash.peers.firstOrNull { it.publicKey == pubB64 }
        val contact = SimContact(
            contactId = id, name = name, nodeId = OgCrypto.nodeId(peerPub), publicKey = pubB64, state = "pending",
            fingerprint = OgCrypto.fingerprint(publicKey(), peerPub),
        )
        save(flash.copy(contacts = flash.contacts.filter { it.contactId != id } + contact))
        // Success ends the pairing session (the lab image too since its salt fix of 2026-10-24).
        pairing = null
        usedPeerCodes[qr.trim()] = clock() + PAIR_WINDOW_MS
        val warnings = if (flash.contacts.count { it.nodeId == contact.nodeId } > 1) listOf("node_id_collision") else emptyList()
        if (peer != null && peer.scansBack && peer.inRange) {
            // The peer scans our code too and its app pings us; the first valid packet activates the contact.
            scope.launch {
                delay(1_500)
                synchronized(lock) { peerPacket(peer, id) }
            }
        }
        return buildJsonObject {
            put("contact", contactJson(contact))
            putJsonArray("warnings") { warnings.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /** QR text = Base64("OG1" || public_key(32) || salt(16) || name). */
    private fun parseQr(qr: String): Triple<ByteArray, ByteArray, String>? {
        val raw = runCatching { OgCrypto.unb64(qr.trim()) }.getOrNull() ?: return null
        if (raw.size < 51 || raw[0] != 'O'.code.toByte() || raw[1] != 'G'.code.toByte() || raw[2] != '1'.code.toByte()) return null
        return Triple(raw.copyOfRange(3, 35), raw.copyOfRange(35, 51), raw.copyOfRange(51, raw.size).toString(Charsets.UTF_8))
    }

    private fun contactList(a: JsonObject): JsonObject {
        requireUnlocked()
        val offset = a.int("offset") ?: 0
        val limit = (a.int("limit") ?: 10).coerceIn(1, 10)
        val sorted = flash.contacts.sortedBy { it.contactId }
        return buildJsonObject {
            put("total", sorted.size); put("offset", offset)
            putJsonArray("contacts") { sorted.drop(offset).take(limit).forEach { add(contactJson(it)) } }
        }
    }

    private fun contact(a: JsonObject): SimContact {
        val id = a.int("contact_id") ?: throw CommandError("E_INVALID_ARG", "contact_id missing", "contact_id")
        return flash.contacts.firstOrNull { it.contactId == id } ?: throw CommandError("E_NO_SUCH_CONTACT", "no contact $id")
    }

    private fun updateContact(c: SimContact) {
        save(flash.copy(contacts = flash.contacts.map { if (it.contactId == c.contactId) c else it }))
    }

    private fun contactJson(c: SimContact) = buildJsonObject {
        put("contact_id", c.contactId); put("name", c.name); put("node_id", c.nodeId); put("public_key", c.publicKey)
        put("state", c.state); put("fingerprint", c.fingerprint); put("tx_ctr", c.txCtr)
        // The firmware reports RSSI as a float (`Contacts::View::rssi`), so the simulator does too.
        put("rx_highest_ctr", c.rxHighestCtr); put("last_rssi", c.lastRssi?.toDouble()); put("last_snr", c.lastSnr)
    }

    // ---------------------------------------------------------------- messages

    private fun msgSend(a: JsonObject): JsonObject {
        requireUnlocked()
        val to = a.int("to") ?: throw CommandError("E_INVALID_ARG", "to missing", "to")
        val c = flash.contacts.firstOrNull { it.contactId == to } ?: throw CommandError("E_NO_SUCH_CONTACT", "no contact $to")
        val type = a.str("type") ?: throw CommandError("E_INVALID_ARG", "type missing", "type")
        val bodyBytes = when (type) {
            "text" -> {
                val text = a.str("text") ?: throw CommandError("E_INVALID_ARG", "text missing", "text")
                val n = MessageLimits.utf8Bytes(text)
                if (n == 0) throw CommandError("E_INVALID_ARG", "text is empty", "text")
                // Lab.3: with ogz1 up to 160 bytes are accepted; only texts over 83 bytes are compressed.
                val max = maxTextInputBytes()
                val onAir = MessageLimits.onAirTextBytes(text, codec(), max)
                if (n > max || onAir > MessageLimits.DEFAULT_MAX_TEXT_BYTES) {
                    throw CommandError("E_TOO_LONG", "text is $n bytes, max is ${MessageLimits.DEFAULT_MAX_TEXT_BYTES} on air", "text")
                }
                onAir
            }
            "reaction" -> {
                if (a.obj("target") == null) throw CommandError("E_INVALID_ARG", "target missing", "target")
                if (!a.containsKey("emoji")) throw CommandError("E_INVALID_ARG", "emoji missing", "emoji")
                3
            }
            "geo" -> {
                val lat = a.double("lat") ?: throw CommandError("E_INVALID_ARG", "lat missing", "lat")
                val lon = a.double("lon") ?: throw CommandError("E_INVALID_ARG", "lon missing", "lon")
                if (lat !in -90.0..90.0) throw CommandError("E_INVALID_ARG", "lat out of range", "lat")
                if (lon !in -180.0..180.0) throw CommandError("E_INVALID_ARG", "lon out of range", "lon")
                6
            }
            else -> throw CommandError("E_INVALID_ARG", "unknown type", "type")
        }
        if (pending.size >= 8) throw CommandError("E_QUEUE_FULL", "pending queue is full")
        val ack = a.bool("ack") ?: (type == "text")
        val hops = a.int("hops") ?: flash.hopDefault
        if (hops !in 0..7) throw CommandError("E_INVALID_ARG", "hops must be 0 to 7", "hops")
        val ctr = c.txCtr + 1
        updateContact(c.copy(txCtr = ctr))
        val p = enqueue(c.contactId, type, ctr)
        val onAir = 17 + bodyBytes
        p.job = scope.launch { deliver(p, ack, a) }
        return buildJsonObject {
            put("msg_ref", p.ref); put("ctr", ctr); put("ack_requested", ack); put("hops", hops); put("state", "queued")
            put("bytes_on_air", onAir); put("est_airtime_ms", 120 + onAir * 4)
            if (labRadio && type == "text") put("compressed", MessageLimits.utf8Bytes(a.str("text")!!) > MessageLimits.DEFAULT_MAX_TEXT_BYTES)
        }
    }

    private fun enqueue(to: Int, type: String, ctr: Int?, group: Int? = null): SimPending {
        val p = SimPending(nextRef++, to, type, ctr, clock(), group)
        pending[p.ref] = p
        return p
    }

    private fun statusData(p: SimPending, reason: String?, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("msg_ref", p.ref)
        if (p.to > 0) put("to", p.to)
        if (p.group != null) put("group_id", p.group)
        put("state", p.state); put("retry", p.retry)
        if (reason != null) put("reason", reason)
        extra()
    }

    /** Moves a pending message to a non-final state and reports it (`msg.status`). */
    private fun progress(p: SimPending, state: String, reason: String? = null): Boolean {
        if (pending[p.ref] !== p) return false
        p.state = state
        if (state == "sent") txPackets++
        if (state == "retrying") {
            txPackets++
            retriesScheduled++
        }
        emitEvent("msg.status", statusData(p, reason))
        return true
    }

    /**
     * Ends a pending message: `msg.status`, then it leaves the queue and enters `recent` (newest first, 16).
     * Callers outside the message's own coroutine pass [stop] so its delivery loop ends.
     */
    private fun finish(
        p: SimPending,
        state: String,
        reason: String?,
        afterResponse: Boolean = false,
        stop: Boolean = false,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) {
        if (pending.remove(p.ref) !== p) return
        if (stop) p.job?.cancel()
        p.state = state
        val data = statusData(p, reason, extra)
        if (afterResponse) emitAfterResponse("msg.status", data) else emitEvent("msg.status", data)
        recent.addFirst(SimRecent(p.ref, state, reason))
        while (recent.size > RECENT_CAPACITY) recent.removeLast()
    }

    private fun pendingJson() = buildJsonObject {
        putJsonArray("pending") {
            pending.values.forEach { p ->
                add(buildJsonObject {
                    put("msg_ref", p.ref); if (p.to > 0) put("to", p.to); if (p.group != null) put("group_id", p.group)
                    put("type", p.type); put("ctr", p.ctr)
                    put("state", p.state); put("retry", p.retry)
                })
            }
        }
        if (labRadio) {
            putJsonArray("recent") {
                recent.forEach { r ->
                    add(buildJsonObject { put("msg_ref", r.ref); put("state", r.state); if (r.reason != null) put("reason", r.reason) })
                }
            }
        }
    }

    /**
     * MeshEngine with the lab.3 lost-message fix: `sent`, retries, then `waiting` (reason `no_ack`) with the
     * same counter until the contact shows a sign of life or [SimTiming.resendMs] passed, and `failed timeout`
     * [SimTiming.parkMs] after it was queued.
     */
    private suspend fun deliver(p: SimPending, ack: Boolean, args: JsonObject) {
        delay(timing.firstTxMs)
        if (!synchronized(lock) { progress(p, "sent") }) return
        if (p.type == "group_sync") delay(timing.groupSyncMs)
        if (!ack) {
            val peer = synchronized(lock) {
                finish(p, "sent", null)
                peerFor(p.to)
            }
            if (peer != null && reachable(peer)) reactTo(peer, p.to, args, p.type)
            return
        }
        for (retry in 0..timing.retries) {
            if (retry > 0) {
                val stillPending = synchronized(lock) {
                    p.retry = retry
                    progress(p, "retrying")
                }
                if (!stillPending) return
            }
            delay(if (retry == 0) timing.ackWaitMs else timing.retryMs)
            if (tryDeliver(p, args)) return
        }
        if (!labRadio) {
            // Team firmware: no ACK after the last retry is final.
            synchronized(lock) { finish(p, "failed", "timeout") }
            return
        }
        if (!synchronized(lock) { progress(p, "waiting", "no_ack") }) return
        while (true) {
            val left = p.createdAt + timing.parkMs - clock()
            if (left <= 0) {
                synchronized(lock) { finish(p, "failed", "timeout") }
                return
            }
            withTimeoutOrNull(minOf(timing.resendMs, left)) { p.wake.receive() }
            synchronized(lock) {
                if (pending[p.ref] === p) {
                    txPackets++
                    resends++
                }
            }
            if (tryDeliver(p, args)) return
        }
    }

    /** One transmission reached the contact when its peer is in range, has us paired and we are unlocked. */
    private suspend fun tryDeliver(p: SimPending, args: JsonObject): Boolean {
        val peer = synchronized(lock) {
            val peer = peerFor(p.to)
            if (pending[p.ref] !== p || peer == null || !reachable(peer) || !peer.scansBack || state != "unlocked") return false
            acksReceived++
            rxPackets++
            finish(p, "delivered", null) { put("hops", 0); put("rtt_ms", (clock() - p.createdAt).coerceAtLeast(1)) }
            activate(p.to, peer)
            peer
        }
        reactTo(peer, p.to, args, p.type)
        return true
    }

    /** Virtual peers answer questions and share their position back. */
    private suspend fun reactTo(peer: VirtualPeer, contactId: Int, args: JsonObject, type: String) {
        when {
            type == "geo" && peer.lat != null && peer.lon != null -> {
                delay(2_500)
                synchronized(lock) {
                    incoming(peer, contactId, buildJsonObject {
                        put("type", "geo"); put("lat", peer.lat); put("lon", peer.lon); put("alt_m", JsonNull); put("age_s", 10)
                    })
                }
            }
            type == "text" && peer.replies.isNotEmpty() && args.str("text")?.contains('?') == true -> {
                delay(3_000)
                synchronized(lock) {
                    val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: return
                    val text = peer.replies[(c.rxHighestCtr ?: 0) % peer.replies.size]
                    incoming(peer, contactId, buildJsonObject { put("type", "text"); put("text", text) })
                }
            }
        }
    }

    /** A valid packet from [peer] arrived: deliver it to the app and activate the contact. */
    private fun incoming(peer: VirtualPeer, contactId: Int, body: JsonObject) {
        if (state != "unlocked" || !reachable(peer)) return
        val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: return
        rxPackets++
        val ctr = (c.rxHighestCtr ?: 0) + 1
        updateContact(c.copy(rxHighestCtr = ctr, lastRssi = peer.rssi, lastSnr = peer.snr))
        emitEvent("msg.received", buildJsonObject {
            put("from", contactId)
            body.forEach { (k, v) -> put(k, v) }
            if (labRadio && body.str("type") == "text") put("compressed", false)
            put("ctr", ctr); put("hops", 0); put("retry", 0); put("rssi", peer.rssi.toDouble()); put("snr", peer.snr)
        })
        activate(contactId, peer)
        // Lab.3 item 5: any authenticated packet from the contact sends its waiting messages again at once.
        pending.values.filter { it.to == contactId && it.state == "waiting" }.forEach { it.wake.trySend(Unit) }
    }

    /** First packet from the peer after it scanned our code: an empty "remove reaction" ping. */
    private fun peerPacket(peer: VirtualPeer, contactId: Int) {
        incoming(peer, contactId, buildJsonObject {
            put("type", "reaction")
            putJsonObject("target") { put("author", "me"); put("ref", 0) }
            put("emoji", JsonNull)
        })
    }

    private fun activate(contactId: Int, peer: VirtualPeer) {
        val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: return
        if (c.state == "active") return
        updateContact(c.copy(state = "active", lastRssi = peer.rssi, lastSnr = peer.snr))
        emitEvent("contact.state", buildJsonObject { put("contact_id", contactId); put("state", "active") })
    }

    /** In range and not on the lab ignore list (without relays an ignored node is out of reach). */
    private fun reachable(peer: VirtualPeer): Boolean =
        peer.inRange && OgCrypto.nodeId(OgCrypto.unb64(peer.publicKey)) !in flash.ignoreNodes

    private fun peerFor(contactId: Int): VirtualPeer? {
        val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: return null
        return flash.peers.firstOrNull { it.publicKey == c.publicKey }
    }

    private fun failAllPending(reason: String) {
        pending.values.toList().forEach { finish(it, "failed", reason, stop = true) }
    }

    // ---------------------------------------------------------------- radio

    private fun radioStats(reset: Boolean): JsonObject {
        val result = buildJsonObject {
            put("tx_packets", txPackets); put("rx_packets", rxPackets); put("rx_crc_errors", 0); put("rx_tag_failures", 0)
            put("rx_malformed", 0); put("rx_dedupe_drops", 0); put("relayed", 0); put("relay_suppressed", 0); put("relay_dropped", 0)
            put("rate_limit_drops", 0); put("acks_sent", rxPackets); put("acks_received", acksReceived); put("retries", retriesScheduled)
            put("tx_airtime_ms_total", txPackets * 206); put("cad_scans", txPackets); put("cad_busy", 0)
            put("radio_failures", 0); put("radio_timeouts", 0); put("dropped_events", 0); put("dropped_acks", 0)
            put("radio_dropped_events", 0); put("trace_dropped_events", 0); put("last_rssi", -71.0); put("last_snr", 9.5)
            put("tx_duty_cycle_max_percent", if (labRadio && flash.freqMhz in 869.40..869.65) 10 else 1)
            if (labRadio) {
                put("resends", resends); put("locked_buffered", 0); put("locked_expired", 0)
            }
        }
        if (reset) {
            txPackets = 0
            rxPackets = 0
            acksReceived = 0
            retriesScheduled = 0
            resends = 0
        }
        return result
    }

    private fun meshJson() = buildJsonObject {
        put("hop_default", flash.hopDefault); put("relay_enabled", flash.relayEnabled); put("relay_when_locked", flash.relayWhenLocked)
        put("cad_enabled", flash.cadEnabled); put("backoff_slots", 8); put("retries", flash.retries); put("retry_base_ms", 0)
        put("rate_limit_per_src", 20)
        putJsonObject("ack_default") { put("text", true); put("reaction", false); put("geo", false) }
    }

    /** `mesh.set`: validates the whole candidate first, then applies it (link spec 8). */
    private fun meshSet(a: JsonObject): JsonObject {
        fun boolArg(key: String, current: Boolean): Boolean {
            if (!a.containsKey(key)) return current
            return a.bool(key) ?: throw CommandError("E_INVALID_ARG", "$key must be a boolean", key)
        }
        fun intArg(key: String, current: Int, range: IntRange): Int {
            if (!a.containsKey(key)) return current
            return a.int(key)?.takeIf { it in range } ?: throw CommandError("E_INVALID_ARG", "invalid configuration candidate", key)
        }
        save(flash.copy(
            hopDefault = intArg("hop_default", flash.hopDefault, 0..7),
            retries = intArg("retries", flash.retries, 0..3),
            relayEnabled = boolArg("relay_enabled", flash.relayEnabled),
            relayWhenLocked = boolArg("relay_when_locked", flash.relayWhenLocked),
            cadEnabled = boolArg("cad_enabled", flash.cadEnabled),
        ))
        return meshJson()
    }

    /** Lab contract `lab.set {text_codec?, ignore_nodes?}`, both persisted. */
    private fun labSet(a: JsonObject): JsonObject {
        var next = flash
        if (a.containsKey("text_codec")) {
            val codec = a.str("text_codec")
            if (codec != MessageLimits.CODEC_RAW && codec != MessageLimits.CODEC_OGZ1) {
                throw CommandError("E_INVALID_ARG", "text_codec must be raw or ogz1", "text_codec")
            }
            next = next.copy(textCodec = codec)
        }
        if (a.containsKey("ignore_nodes")) {
            val list = (a["ignore_nodes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (list == null || list.size > 8 || list.any { it == null || !NODE_ID.matches(it) }) {
                throw CommandError("E_INVALID_ARG", "ignore_nodes must be up to 8 four-hex-digit NodeIDs", "ignore_nodes")
            }
            next = next.copy(ignoreNodes = list.filterNotNull().map { it.uppercase() }.distinct())
        }
        save(next)
        return labJson()
    }

    private fun radioJson() = buildJsonObject {
        put("freq_mhz", flash.freqMhz); put("bw_khz", flash.bwKhz); put("sf", flash.sf); put("cr", 5)
        put("tx_power_dbm", flash.txPowerDbm); put("preamble", 8); put("sync_word", 79)
        put("est_airtime_ms_23b", 206); put("max_text_bytes", MessageLimits.DEFAULT_MAX_TEXT_BYTES)
    }

    private fun radioSet(a: JsonObject): JsonObject {
        val freq = a.double("freq_mhz") ?: flash.freqMhz
        if (freq !in 150.0..960.0) throw CommandError("E_INVALID_ARG", "freq_mhz out of range", "freq_mhz")
        val sf = a.int("sf") ?: flash.sf
        if (sf !in 5..12) throw CommandError("E_INVALID_ARG", "sf out of range", "sf")
        save(flash.copy(freqMhz = freq, sf = sf, txPowerDbm = a.int("tx_power_dbm") ?: flash.txPowerDbm))
        return buildJsonObject {
            radioJson().forEach { (k, v) -> put(k, v) }
            putJsonArray("warnings") { if (freq !in 863.0..870.0) add(JsonPrimitive("outside_eu868")) }
        }
    }

    // ---------------------------------------------------------------- groups (link spec 6, "Groups")

    private fun group(a: JsonObject): SimGroup {
        val id = a.int("group_id") ?: throw CommandError("E_INVALID_ARG", "group_id missing", "group_id")
        return flash.groups.firstOrNull { it.groupId == id } ?: throw CommandError("E_NO_SUCH_GROUP", "no group $id")
    }

    private fun ownedGroup(a: JsonObject): SimGroup =
        group(a).also { if (!it.owner) throw CommandError("E_INVALID_ARG", "only the owner changes the group", "group_id") }

    private fun updateGroup(g: SimGroup) {
        save(flash.copy(groups = flash.groups.map { if (it.groupId == g.groupId) g else it }))
    }

    private fun groupJson(g: SimGroup, withMembers: Boolean) = buildJsonObject {
        put("group_id", g.groupId); put("gid", g.gid); put("name", g.name); put("epoch", g.epoch); put("owner", g.owner)
        put("owner_contact_id", g.ownerContactId); put("member", g.ownMember); put("member_count", g.members.size + 1)
        if (withMembers) {
            putJsonArray("members") {
                add(buildJsonObject { put("member", g.ownMember); put("node_id", nodeId()); put("contact_id", JsonNull) })
                g.members.forEach { m ->
                    add(buildJsonObject { put("member", m.member); put("node_id", m.nodeId); put("contact_id", m.contactId) })
                }
            }
        }
    }

    private fun intList(a: JsonObject, key: String): List<Int> {
        val ids = (a[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toIntOrNull() }
            ?: throw CommandError("E_INVALID_ARG", "$key missing", key)
        if (ids.any { it == null } || ids.distinct().size != ids.size) throw CommandError("E_INVALID_ARG", "$key must be distinct integers", key)
        return ids.filterNotNull()
    }

    private fun membersFor(contactIds: List<Int>, firstIndex: Int): List<SimGroupMember> = contactIds.mapIndexed { i, id ->
        val c = flash.contacts.firstOrNull { it.contactId == id } ?: throw CommandError("E_NO_SUCH_CONTACT", "no contact $id")
        SimGroupMember(firstIndex + i, c.nodeId, c.contactId)
    }

    private fun freeGroupSlot(): Int? = (1..MAX_GROUPS).firstOrNull { s -> flash.groups.none { it.groupId == s } }

    private fun groupCreate(a: JsonObject): JsonObject {
        requireUnlocked()
        val name = a.str("name")?.trim().orEmpty()
        if (name.isEmpty() || MessageLimits.utf8Bytes(name) > MessageLimits.MAX_NAME_BYTES) {
            throw CommandError("E_INVALID_ARG", "name must be 1 to 24 bytes", "name")
        }
        val ids = intList(a, "members")
        if (ids.isEmpty() || ids.size > MAX_GROUP_MEMBERS - 1) throw CommandError("E_INVALID_ARG", "1 to 15 members", "members")
        val members = membersFor(ids, firstIndex = 1)
        val slot = freeGroupSlot() ?: throw CommandError("E_STORAGE_FULL", "all group slots are used")
        val g = SimGroup(slot, OgCrypto.hex(OgCrypto.randomBytes(4)).uppercase(), name, epoch = 1, owner = true, ownMember = 0, members = members)
        save(flash.copy(groups = flash.groups + g))
        return buildJsonObject { put("group", groupJson(g, withMembers = true)) }
    }

    private fun groupList(): JsonObject {
        requireUnlocked()
        return buildJsonObject {
            putJsonArray("groups") { flash.groups.sortedBy { it.groupId }.forEach { add(groupJson(it, withMembers = false)) } }
            put("total", flash.groups.size)
        }
    }

    /** `group.sync`: the current key and member list to one member as an acknowledged DM; its `msg.status` carries `to` and `group_id`. */
    private fun groupSync(a: JsonObject): JsonObject {
        requireUnlocked()
        val g = ownedGroup(a)
        val to = a.int("contact_id") ?: throw CommandError("E_INVALID_ARG", "contact_id missing", "contact_id")
        val c = flash.contacts.firstOrNull { it.contactId == to } ?: throw CommandError("E_NO_SUCH_CONTACT", "no contact $to")
        if (g.members.none { it.contactId == to }) throw CommandError("E_INVALID_ARG", "contact is not a member", "contact_id")
        if (pending.size >= 8) throw CommandError("E_QUEUE_FULL", "pending queue is full")
        val hops = a.int("hops") ?: flash.hopDefault
        if (hops !in 0..7) throw CommandError("E_INVALID_ARG", "hops must be 0 to 7", "hops")
        val ctr = c.txCtr + 1
        updateContact(c.copy(txCtr = ctr))
        val p = enqueue(to, "group_sync", ctr, group = g.groupId)
        p.job = scope.launch { deliver(p, ack = true, args = a) }
        val onAir = 17 + 33
        return buildJsonObject {
            put("msg_ref", p.ref); put("ctr", ctr); put("ack_requested", true); put("hops", hops); put("state", "queued")
            put("bytes_on_air", onAir); put("est_airtime_ms", 120 + onAir * 4)
        }
    }

    /** A member change starts a new epoch; syncs of the old one are cancelled (their `msg.status` follows the response). */
    private fun newEpoch(g: SimGroup, members: List<SimGroupMember>): SimGroup {
        if (g.epoch >= 255) throw CommandError("E_BUSY", "epoch 255 reached, create a new group")
        val next = g.copy(epoch = g.epoch + 1, members = members)
        pending.values.filter { it.type == "group_sync" && it.group == g.groupId }
            .forEach { finish(it, "cancelled", null, afterResponse = true, stop = true) }
        updateGroup(next)
        return next
    }

    private fun groupAdd(a: JsonObject): JsonObject {
        requireUnlocked()
        val g = ownedGroup(a)
        val ids = intList(a, "members")
        if (ids.isEmpty() || ids.any { id -> g.members.any { it.contactId == id } }) {
            throw CommandError("E_INVALID_ARG", "members must be new contacts", "members")
        }
        if (g.members.size + 1 + ids.size > MAX_GROUP_MEMBERS) throw CommandError("E_INVALID_ARG", "a group takes 16 members", "members")
        val firstIndex = (g.members.maxOfOrNull { it.member } ?: 0) + 1
        val next = newEpoch(g, g.members + membersFor(ids, firstIndex))
        return buildJsonObject { put("group", groupJson(next, withMembers = true)) }
    }

    private fun groupRemove(a: JsonObject): JsonObject {
        requireUnlocked()
        val g = ownedGroup(a)
        val indexes = intList(a, "members")
        if (indexes.isEmpty() || indexes.any { i -> i == 0 || g.members.none { it.member == i } }) {
            throw CommandError("E_INVALID_ARG", "members must be member indexes other than 0", "members")
        }
        val rest = g.members.filter { it.member !in indexes }
        if (rest.isEmpty()) throw CommandError("E_INVALID_ARG", "at least 2 members stay", "members")
        return buildJsonObject { put("group", groupJson(newEpoch(g, rest), withMembers = true)) }
    }

    private fun groupDelete(a: JsonObject): JsonObject {
        requireUnlocked()
        val g = group(a)
        if (a.bool("confirm") != true) throw CommandError("E_CONFIRM_REQUIRED", "confirm must be true", "confirm")
        pending.values.filter { it.group == g.groupId }.forEach { finish(it, "cancelled", null, afterResponse = true, stop = true) }
        save(flash.copy(groups = flash.groups.filter { it.groupId != g.groupId }))
        return buildJsonObject { put("deleted", true) }
    }

    /** `group.send`: no receipt and no retries, `sent` is final; `ack: true` is refused. */
    private fun groupSend(a: JsonObject): JsonObject {
        requireUnlocked()
        val g = group(a)
        val type = a.str("type") ?: throw CommandError("E_INVALID_ARG", "type missing", "type")
        if (a.bool("ack") == true) throw CommandError("E_INVALID_ARG", "group messages have no receipts", "ack")
        val body = when (type) {
            "text" -> {
                val text = a.str("text") ?: throw CommandError("E_INVALID_ARG", "text missing", "text")
                val n = MessageLimits.utf8Bytes(text)
                if (n == 0) throw CommandError("E_INVALID_ARG", "text is empty", "text")
                if (n > MessageLimits.DEFAULT_MAX_TEXT_BYTES) throw CommandError("E_TOO_LONG", "text is $n bytes", "text")
                n
            }
            "geo" -> {
                if (a.double("lat") == null || a.double("lon") == null) throw CommandError("E_INVALID_ARG", "lat and lon missing", "lat")
                6
            }
            else -> throw CommandError("E_INVALID_ARG", "type must be text or geo", "type")
        }
        if (pending.size >= 8) throw CommandError("E_QUEUE_FULL", "pending queue is full")
        val hops = a.int("hops") ?: flash.hopDefault
        if (hops !in 0..7) throw CommandError("E_INVALID_ARG", "hops must be 0 to 7", "hops")
        val ctr = g.txCtr + 1
        updateGroup(g.copy(txCtr = ctr))
        val p = enqueue(0, type, ctr, group = g.groupId)
        p.job = scope.launch {
            delay(timing.firstTxMs)
            synchronized(lock) {
                txPackets++
                finish(p, "sent", null)
            }
            if (type == "text" && a.str("text")!!.contains('?')) {
                val replier = synchronized(lock) {
                    g.members.firstNotNullOfOrNull { m -> m.contactId?.let { id -> peerFor(id)?.takeIf { reachable(it) }?.let { m to it } } }
                }
                if (replier != null) {
                    delay(3_000)
                    synchronized(lock) { groupIncoming(g.groupId, replier.first, replier.second.replies.firstOrNull() ?: "Ok") }
                }
            }
        }
        val bytes = 17 + body
        return buildJsonObject {
            put("msg_ref", p.ref); put("ctr", ctr); put("ack_requested", false); put("hops", hops); put("state", "queued")
            put("bytes_on_air", bytes); put("est_airtime_ms", 120 + bytes * 4)
        }
    }

    /** A text from [from] arrived in a group: `group.received`, with `from` null when the board has no contact for the NodeID. */
    private fun groupIncoming(groupId: Int, from: SimGroupMember, text: String) {
        if (state != "unlocked") return
        if (flash.groups.none { it.groupId == groupId }) return
        rxPackets++
        emitEvent("group.received", buildJsonObject {
            put("type", "text"); put("text", text); put("group_id", groupId); put("member", from.member); put("node_id", from.nodeId)
            put("from", from.contactId); put("ctr", ++groupRxCtr); put("retry", 0); put("hops", 0); put("rssi", -72.0); put("snr", 9.0)
        })
    }

    // ---------------------------------------------------------------- test harness API (not part of the protocol)

    /** A hung board: the port stays open but nothing is answered. */
    @Volatile
    var hung: Boolean = false

    /** An old image that still reports `mock: true` in `sys.hello`. */
    @Volatile
    var mockImage: Boolean = false

    /** Creates a virtual peer and returns the QR text its app would show. */
    fun addVirtualPeer(
        name: String,
        rssi: Int,
        scansBack: Boolean = true,
        inRange: Boolean = true,
        replies: List<String> = emptyList(),
        lat: Double? = null,
        lon: Double? = null,
    ): String = synchronized(lock) {
        val private = OgCrypto.x25519PrivateKey()
        val public = OgCrypto.x25519PublicKey(private)
        val salt = OgCrypto.randomBytes(16)
        val peer = VirtualPeer(OgCrypto.b64(public), OgCrypto.b64(private), OgCrypto.b64(salt), name, rssi,
            snr = if (rssi > -100) 7.5 else 2.0, scansBack = scansBack, inRange = inRange, replies = replies, lat = lat, lon = lon)
        save(flash.copy(peers = flash.peers + peer))
        OgCrypto.b64("OG1".toByteArray() + public + salt + name.toByteArray())
    }

    /** Lets a virtual peer send a text right now (used by the demo seed and tests). */
    fun peerSays(contactId: Int, text: String) = synchronized(lock) {
        val peer = peerFor(contactId) ?: return@synchronized
        incoming(peer, contactId, buildJsonObject { put("type", "text"); put("text", text) })
    }

    /**
     * A virtual peer (a paired contact) owns a group and invites us: after [SimTiming.groupSyncMs] the board
     * applies the sync and reports `group.state joined`, as the firmware does once the owner's key arrives.
     * [others] are further members, given by NodeID, that the board holds no contact for. Returns the slot.
     */
    fun peerInvites(contactId: Int, name: String, others: List<String> = emptyList()): Int = synchronized(lock) {
        val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: error("no contact $contactId")
        val slot = freeGroupSlot() ?: error("all group slots are used")
        val members = listOf(SimGroupMember(0, c.nodeId, c.contactId)) +
            others.mapIndexed { i, node -> SimGroupMember(i + 2, node.uppercase(), null) }
        val g = SimGroup(slot, OgCrypto.hex(OgCrypto.randomBytes(4)).uppercase(), name, epoch = 1, owner = false,
            ownMember = 1, members = members, ownerContactId = c.contactId)
        scope.launch {
            delay(timing.groupSyncMs)
            synchronized(lock) {
                if (state != "unlocked") return@synchronized
                save(flash.copy(groups = flash.groups + g))
                emitEvent("group.state", buildJsonObject { put("group_id", slot); put("state", "joined"); put("epoch", 1) })
            }
        }
        slot
    }

    /** Member [member] of group [groupId] says [text] (the owner is member 0). */
    fun memberSays(groupId: Int, member: Int, text: String) = synchronized(lock) {
        val m = flash.groups.firstOrNull { it.groupId == groupId }?.members?.firstOrNull { it.member == member } ?: return@synchronized
        groupIncoming(groupId, m, text)
    }

    fun setPeerInRange(contactId: Int, inRange: Boolean) = synchronized(lock) {
        val c = flash.contacts.firstOrNull { it.contactId == contactId } ?: return@synchronized
        save(flash.copy(peers = flash.peers.map { if (it.publicKey == c.publicKey) it.copy(inRange = inRange) else it }))
    }

    /** Restarts the simulated board as a power cut would: RAM queue and `recent` are gone, the vault is locked. */
    fun powerCycle() = synchronized(lock) { reboot("power_on") }

    private fun codec(): String = if (labRadio) flash.textCodec else MessageLimits.CODEC_RAW

    private fun maxTextInputBytes(): Int =
        if (codec() == MessageLimits.CODEC_OGZ1) OGZ1_MAX_TEXT_INPUT_BYTES else MessageLimits.DEFAULT_MAX_TEXT_BYTES

    private fun labJson() = buildJsonObject {
        put("text_codec", flash.textCodec); put("max_text_input_bytes", maxTextInputBytes()); put("node_id", nodeId())
        put("fw", fwVersion)
        putJsonArray("ignore_nodes") { flash.ignoreNodes.forEach { add(JsonPrimitive(it)) } }
    }

    private fun emitLater(name: String, data: JsonObject) {
        scope.launch {
            delay(20)
            synchronized(lock) { emitEvent(name, data) }
        }
    }

    private fun save(newFlash: SimFlash) {
        flash = newFlash
        store.save(newFlash)
    }

    companion object {
        /** Behaves like the lab image 0.3.0-lab.3 (lab contract of 2026-10-24). */
        const val FW_VERSION = "0.3.0-lab.3-sim"

        /** Behaves like Nils' team firmware 0.3.0 (`feature/firmware-hardening`). */
        const val TEAM_FW_VERSION = "0.3.0-sim"
        private const val PAIR_WINDOW_MS = 300_000L
        private const val PAIR_RENEW_MS = 60_000L
        private val NODE_ID = Regex("^[0-9A-Fa-f]{4}$")
        const val PROTO = "1.1"
        private const val MAX_GROUPS = 8
        private const val MAX_GROUP_MEMBERS = 16
        private const val RECENT_CAPACITY = 16
        private const val OGZ1_MAX_TEXT_INPUT_BYTES = 160

        /** 1 .. 2^30, like the lab image's first `msg_ref` after boot. */
        private fun randomRef(): Int = Random.nextInt(1 shl 30) + 1
    }
}
