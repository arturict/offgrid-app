package ch.offgrid.android.domain

import ch.offgrid.android.analytics.AnalyticsEvent
import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.GroupEntity
import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.MessageStatus
import ch.offgrid.android.data.OffGridDatabase
import ch.offgrid.android.link.Incoming
import ch.offgrid.android.link.LinkClosedException
import ch.offgrid.android.link.LinkCommandException
import ch.offgrid.android.link.LinkTimeoutException
import ch.offgrid.android.link.bool
import ch.offgrid.android.link.dbm
import ch.offgrid.android.link.double
import ch.offgrid.android.link.int
import ch.offgrid.android.link.isNull
import ch.offgrid.android.link.obj
import ch.offgrid.android.link.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One row in the contact list: a contact or a group. */
data class ConversationItem(
    val contact: ContactEntity? = null,
    val group: GroupEntity? = null,
    val memberCount: Int = 0,
    val last: MessageEntity? = null,
    val unread: Int = 0,
) {
    val activity: Long get() = last?.sentAt ?: contact?.createdAt ?: group?.createdAt ?: 0
}

/** Reaction emoji table, protocol spec appendix B (proposal). */
val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Chat history and message delivery. History lives only in the app (link spec 1.1); the device
 * gets each message once through `msg.send` and reports progress with `msg.status` events.
 */
class Messaging(
    private val db: OffGridDatabase,
    private val antenna: AntennaManager,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onIncoming: (MessageEntity) -> Unit = {},
    private val track: (AnalyticsEvent) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : AntennaListener {
    private val sendMutex = Mutex()

    /** Held while refs are handed out, matched or reconciled, so an event never sees half an update. */
    private val refMutex = Mutex()

    /** Serializes the two writers that mark contacts [UNPAIRED], so a reset is not counted twice in [Settings.REPAIR_NOTICE]. */
    private val contactMutex = Mutex()
    private var currentDevice: String? = null

    /** Link session whose refs `msg.status` events may update; null until the outbox was reconciled. */
    @Volatile
    private var currentSession: String? = null

    val conversations: Flow<List<ConversationItem>> = combine(
        db.contacts().observeAll(),
        db.groups().observeAll(),
        db.groups().observeMembers(),
        db.messages().observeLatest(),
        db.messages().observeUnread(),
    ) { contacts, groups, members, latest, unread ->
        val contactItems = contacts.map { c ->
            ConversationItem(
                contact = c,
                last = latest.firstOrNull { it.contactId == c.id && it.groupId == null },
                unread = unread.firstOrNull { it.contactId == c.id && it.groupId == null }?.n ?: 0,
            )
        }
        val groupItems = groups.map { g ->
            ConversationItem(
                group = g,
                memberCount = members.count { it.groupId == g.id } + 1,
                last = latest.firstOrNull { it.groupId == g.id },
                unread = unread.firstOrNull { it.groupId == g.id }?.n ?: 0,
            )
        }
        // Active conversations by last activity, then contacts to pair again ("Neu koppeln"), then pending
        // contacts ("Schlüssel ausstehend").
        (contactItems + groupItems).sortedWith(
            compareBy<ConversationItem> {
                when (it.contact?.state) {
                    UNPAIRED -> 1
                    "pending" -> 2
                    else -> 0
                }
            }.thenByDescending { it.activity },
        )
    }

    fun contact(id: Long) = db.contacts().observe(id)
    fun group(id: Long) = db.groups().observe(id)
    fun messages(contactId: Long) = db.messages().observeForContact(contactId)
    fun groupMessages(groupId: Long) = db.messages().observeForGroup(groupId)
    fun message(id: Long) = db.messages().observe(id)
    val members = db.groups().observeMembers()
    val contacts = db.contacts().observeAll()
    val messageCount = db.messages().observeCount()

    // ------------------------------------------------------------------ sending

    /** A text longer than one packet goes out as several messages, see [MessageLimits.split]. */
    fun sendText(contactId: Long, text: String) = enqueue(
        MessageLimits.split(text, antenna.state.value).map {
            MessageEntity(contactId = contactId, direction = "out", kind = "text", text = it, status = MessageStatus.LOCAL_QUEUED, sentAt = now())
        },
    )

    fun sendGroupText(groupId: Long, text: String) = enqueue(
        MessageLimits.split(text, antenna.state.value).map {
            MessageEntity(groupId = groupId, direction = "out", kind = "text", text = it, status = MessageStatus.LOCAL_QUEUED, sentAt = now())
        },
    )

    fun sendLocation(contactId: Long, fix: GeoFix) = enqueue(
        listOf(MessageEntity(contactId = contactId, direction = "out", kind = "geo", lat = fix.latitude, lon = fix.longitude,
            accuracyM = fix.accuracyM, ageS = ((now() - fix.at) / 1000).toInt().coerceIn(0, 2550),
            status = MessageStatus.LOCAL_QUEUED, sentAt = now())),
    )

    fun retry(messageId: Long) {
        scope.launch {
            val m = db.messages().byId(messageId) ?: return@launch
            // A new attempt: the antenna gives it a new ref and counter, the old outcome is forgotten.
            db.messages().update(
                m.copy(status = MessageStatus.LOCAL_QUEUED, failReason = null, msgRef = null, refSession = null, deviceState = null,
                    retry = null, hops = null, rttMs = null, compressed = null, bytesOnAir = null, airtimeMs = null),
            )
            flushQueue()
        }
    }

    fun markRead(contactId: Long) = scope.launch { db.messages().markContactRead(contactId, now()) }
    fun markGroupRead(groupId: Long) = scope.launch { db.messages().markGroupRead(groupId, now()) }

    /** Inserts in order (the queue sends by `sentAt`, then id), then hands them to the antenna. */
    private fun enqueue(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        track(AnalyticsEvent.MessageSent)
        scope.launch {
            messages.forEach { db.messages().insert(it) }
            flushQueue()
        }
    }

    /** Hands every locally queued message to the device, oldest first, once the outbox was reconciled. */
    suspend fun flushQueue() = sendMutex.withLock {
        if (!antenna.state.value.ready) return@withLock
        val session = currentSession ?: return@withLock
        for (m in db.messages().localQueue()) {
            if (!handOver(m, session)) break
        }
    }

    /** Returns false when the link went away; the message then stays queued locally. */
    private suspend fun handOver(m: MessageEntity, session: String): Boolean {
        val args = argsFor(m) ?: run {
            db.messages().update(m.copy(status = MessageStatus.FAILED, failReason = "no_contact"))
            return true
        }
        return try {
            val cmd = if (m.groupId != null) "group.send" else "msg.send"
            // Under refMutex: a msg.status event for the new ref waits until the ref is stored.
            refMutex.withLock {
                val result = antenna.call(cmd, args)
                db.messages().update(
                    m.copy(status = MessageStatus.DEVICE_QUEUED, msgRef = result.int("msg_ref"), refSession = session,
                        ctr = result.int("ctr") ?: m.ctr, deviceState = result.str("state") ?: "queued",
                        compressed = result.bool("compressed"), bytesOnAir = result.int("bytes_on_air"),
                        airtimeMs = result.int("est_airtime_ms")),
                )
            }
            true
        } catch (e: LinkCommandException) {
            when (e.error.code) {
                // Passing states: the antenna locked, or its 8 slots are taken (waiting messages hold theirs
                // up to 30 minutes). The message stays queued; a final msg.status or the next connect flushes again.
                // E_BUSY means the counter is exhausted (Commands.cpp: "re-pair"), so it fails like other refusals.
                "E_LOCKED", "E_QUEUE_FULL" -> false
                else -> {
                    db.messages().update(m.copy(status = MessageStatus.FAILED, failReason = e.error.code))
                    true
                }
            }
        } catch (_: LinkClosedException) {
            false
        } catch (_: LinkTimeoutException) {
            false
        }
    }

    private suspend fun argsFor(m: MessageEntity): JsonObject? {
        if (m.groupId != null) {
            val g = db.groups().all().firstOrNull { it.id == m.groupId } ?: return null
            return buildJsonObject { put("group_id", g.deviceGroupId); put("text", m.text) }
        }
        // A contact the antenna no longer holds has no slot to send to until it is paired again.
        val c = m.contactId?.let { db.contacts().byId(it) }?.takeIf { it.deviceContactId != NO_SLOT } ?: return null
        return when (m.kind) {
            "geo" -> buildJsonObject {
                put("to", c.deviceContactId); put("type", "geo"); put("lat", m.lat); put("lon", m.lon)
                put("age_s", m.ageS?.let { (it / 10) * 10 })
                // Protocol 8.4: one-off location shares request a receipt, periodic beacons do not.
                put("ack", true)
            }
            else -> buildJsonObject { put("to", c.deviceContactId); put("type", "text"); put("text", m.text); put("ack", true) }
        }
    }

    /**
     * Sends a reaction to a message the peer wrote (`target.author = "peer"`), or removes it with a
     * null [emoji]. Once the antenna accepted it, the reaction also shows under the bubble here.
     */
    fun react(message: MessageEntity, emoji: String?) {
        scope.launch {
            val c = message.contactId?.let { db.contacts().byId(it) } ?: return@launch
            val ref = message.ctr ?: return@launch
            runCatching {
                antenna.call("msg.send", buildJsonObject {
                    put("to", c.deviceContactId); put("type", "reaction")
                    putJsonObject("target") { put("author", "peer"); put("ref", ref and 0xFFFF) }
                    put("emoji", emoji)
                })
            }.onSuccess { db.messages().setReaction(message.id, emoji) }
        }
    }

    // ------------------------------------------------------------------ contacts

    suspend fun renameContact(id: Long, displayName: String, firstName: String?, lastName: String?) {
        val c = db.contacts().byId(id) ?: return
        val name = displayName.trim()
        db.contacts().update(c.copy(displayName = name, firstName = firstName?.trim()?.ifEmpty { null },
            lastName = lastName?.trim()?.ifEmpty { null }))
        if (c.deviceContactId != NO_SLOT) {
            runCatching {
                antenna.call("contact.rename", buildJsonObject { put("contact_id", c.deviceContactId); put("name", name) })
            }
        }
    }

    suspend fun deleteContact(id: Long) {
        val c = db.contacts().byId(id) ?: return
        if (c.deviceContactId != NO_SLOT) {
            runCatching {
                antenna.call("contact.delete", buildJsonObject { put("contact_id", c.deviceContactId); put("confirm", true) })
            }
        }
        db.messages().deleteForContact(id)
        db.contacts().delete(id)
    }

    /**
     * "Antenne auf Werkseinstellungen zurücksetzen" (link spec 11). The antenna loses its identity and all
     * contact keys, so once it accepted, the contacts that lived on it become [UNPAIRED] ("Neu koppeln") and
     * their open messages fail; chats and history stay. Nothing is sent. Then the antenna restarts and is set
     * up again for this phone. Any other result leaves the app data as it was. Returns [ResetResult.DONE] when the
     * antenna is ready again, [ResetResult.DONE_NOT_RECONNECTED] when it accepted but has not come back in time.
     */
    suspend fun resetAntenna(): ResetResult {
        val result = reset()
        if (result != ResetResult.DONE) return result
        return if (antenna.awaitSetUp()) ResetResult.DONE else ResetResult.DONE_NOT_RECONNECTED
    }

    /** Asks the antenna to reset and, if it accepted, marks the local fallout. Does not wait for the restart. */
    private suspend fun reset(): ResetResult {
        val deviceId = antenna.state.value.deviceId?.takeIf { antenna.state.value.ready }
        val result = antenna.factoryReset()
        if (result == ResetResult.DONE && deviceId != null) afterAntennaReset(deviceId)
        return result
    }

    private suspend fun afterAntennaReset(deviceId: String) = contactMutex.withLock {
        val effects = refMutex.withLock {
            AntennaReset.effects(db.contacts().all(), db.messages().openOutgoing(), db.messages().localQueue(), deviceId, currentSession)
        }
        effects.contacts.forEach { db.contacts().update(it) }
        effects.messages.forEach { db.messages().update(it) }
        addRepairNotice(effects.contacts.size)
    }

    private suspend fun addRepairNotice(n: Int) {
        if (n == 0) return
        val before = settings.get(Settings.REPAIR_NOTICE)?.toIntOrNull() ?: 0
        settings.put(Settings.REPAIR_NOTICE, (before + n).toString())
    }

    /**
     * "Alle Daten löschen". With the antenna connected it is erased too, with the authenticated factory
     * reset (link spec 6.11): contacts, keys and identity on the antenna go, and the app sets it up again
     * for this phone. The local data is only deleted when the antenna confirmed, so a refused reset does
     * not leave contacts on the antenna that the app no longer shows. Without an antenna only this phone
     * is cleared. Settings stay.
     */
    suspend fun deleteAll(): ResetResult {
        val result = if (antenna.state.value.ready) reset() else ResetResult.NOT_CONNECTED
        if (result == ResetResult.DONE || result == ResetResult.NOT_CONNECTED) {
            db.maintenance().clearAll()
            settings.remove(Settings.REPAIR_NOTICE)
        }
        return result
    }

    /** Proposed `group.create`; firmware without it answers `E_UNKNOWN_CMD`. */
    suspend fun createGroup(name: String, contactIds: List<Long>): Result<Long> = runCatching {
        val contacts = contactIds.mapNotNull { db.contacts().byId(it) }
        val result = antenna.call("group.create", buildJsonObject {
            put("name", name.trim())
            putJsonArray("members") { contacts.forEach { add(JsonPrimitive(it.deviceContactId)) } }
        })
        val deviceId = antenna.state.value.deviceId ?: error("no device")
        val id = db.groups().insert(
            GroupEntity(deviceId = deviceId, deviceGroupId = result.int("group_id")!!, name = name.trim(), createdAt = now()),
        )
        db.groups().insertMembers(contacts.map { GroupMemberEntity(id, it.id) })
        id
    }

    // ------------------------------------------------------------------ device callbacks

    override suspend fun onReady(antenna: AntennaManager, deviceId: String) {
        currentDevice = deviceId
        syncContacts(deviceId)
        reconcileOutbox(deviceId)
        flushQueue()
    }

    /**
     * The antenna restarted while connected: its queue is gone (link spec 7.5), so every message it had
     * is lost, and until the next [onReady] no ref is valid.
     */
    override suspend fun onDeviceRebooted() {
        val device = currentDevice ?: return
        refMutex.withLock {
            currentSession = null
            OutboxReconciler.reconcile(db.messages().openOutgoing(), device, PendingSnapshot.EMPTY, session = "")
                .forEach { db.messages().update(it) }
        }
    }

    /** Settles messages of earlier link sessions with `msg.pending` and starts a new session (see [OutboxReconciler]). */
    private suspend fun reconcileOutbox(deviceId: String) = refMutex.withLock {
        currentSession = null
        val session = OutboxReconciler.newSession(deviceId)
        val snapshot = try {
            PendingSnapshot.parse(antenna.call("msg.pending"))
        } catch (_: LinkCommandException) {
            // Without the queue nothing can be settled; the messages keep their state until the next connect.
            currentSession = session
            return@withLock
        } catch (_: LinkClosedException) {
            return@withLock
        } catch (_: LinkTimeoutException) {
            return@withLock
        }
        OutboxReconciler.reconcile(db.messages().openOutgoing(), deviceId, snapshot, session).forEach { db.messages().update(it) }
        currentSession = session
    }

    /**
     * Mirrors the device's contact slots (state, RSSI) into the local table. Local contacts of this
     * device that it no longer lists become [UNPAIRED]: the antenna was reset or reflashed (new vault,
     * new identity) or the contact was deleted there. They keep their history and must be paired again;
     * pairing the same key reuses the row. The count goes to [Settings.REPAIR_NOTICE] for the contact list.
     */
    private suspend fun syncContacts(deviceId: String) {
        var offset = 0
        val seen = mutableSetOf<String>()
        while (true) {
            val page = runCatching {
                antenna.call("contact.list", buildJsonObject { put("offset", offset); put("limit", 10) })
            }.getOrNull() ?: return
            val list = (page["contacts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            list.forEach { json ->
                upsertFromDevice(deviceId, json, keepName = true)
                json.str("public_key")?.let(seen::add)
            }
            offset += list.size
            if (list.isEmpty() || offset >= (page.int("total") ?: 0)) break
        }
        contactMutex.withLock {
            val gone = db.contacts().all().filter { it.deviceId == deviceId && it.publicKey !in seen && it.deviceContactId != NO_SLOT }
            gone.forEach { db.contacts().update(with(AntennaReset) { it.unpaired() }) }
            addRepairNotice(gone.size)
        }
    }

    /** Stores a device contact object (link spec 7.4). Returns the local contact id. */
    suspend fun upsertFromDevice(deviceId: String, json: JsonObject, keepName: Boolean): Long {
        val slot = json.int("contact_id")!!
        val publicKey = json.str("public_key")!!
        // The public key is the identity; slots are reused after a delete or a device reset.
        val existing = db.contacts().byPublicKey(publicKey)
        val base = existing ?: ContactEntity(
            deviceId = deviceId, deviceContactId = slot, displayName = json.str("name") ?: "?", nodeId = json.str("node_id") ?: "",
            publicKey = publicKey, fingerprint = json.str("fingerprint") ?: "", state = "pending", createdAt = now(),
        )
        val updated = base.copy(
            deviceId = deviceId,
            deviceContactId = slot,
            displayName = if (keepName && existing != null) existing.displayName else json.str("name") ?: base.displayName,
            fingerprint = json.str("fingerprint") ?: base.fingerprint,
            state = json.str("state") ?: base.state,
            lastRssi = json.dbm("last_rssi") ?: base.lastRssi,
            lastSnr = json.double("last_snr") ?: base.lastSnr,
        )
        return if (existing == null) db.contacts().insert(updated) else updated.id.also { db.contacts().update(updated) }
    }

    override suspend fun onEvent(event: Incoming.Event) {
        val d = event.data
        when (event.name) {
            "msg.status" -> onStatus(d)
            "msg.received" -> onReceived(d)
            "contact.state" -> {
                val c = contactForSlot(d.int("contact_id")) ?: return
                db.contacts().update(c.copy(state = d.str("state") ?: c.state, lastSeenAt = now()))
            }
        }
    }

    private suspend fun contactForSlot(slot: Int?): ContactEntity? {
        val device = currentDevice ?: return null
        return slot?.let { db.contacts().bySlot(device, it) }
    }

    private suspend fun onStatus(d: JsonObject) {
        val ref = d.int("msg_ref") ?: return
        val state = d.str("state") ?: return
        val updated = refMutex.withLock {
            // Only refs of the current session: a ref from an earlier boot must never touch a newer message.
            val session = currentSession ?: return
            val m = db.messages().byMsgRef(ref, session) ?: return
            OutboxReconciler.applyEvent(m, state, d.str("reason"), DeliveryDetails.parse(d))?.also { db.messages().update(it) } ?: return
        }
        if (updated.status == MessageStatus.DELIVERED) {
            updated.contactId?.let { db.contacts().byId(it) }?.let { db.contacts().update(it.copy(lastSeenAt = now())) }
        }
        // A final state frees a slot in the antenna's queue; messages that met E_QUEUE_FULL can go now.
        if (updated.msgRef == null) scope.launch { flushQueue() }
    }

    private suspend fun onReceived(d: JsonObject) {
        val c = contactForSlot(d.int("from")) ?: return
        val rssi = d.dbm("rssi")
        db.contacts().update(c.copy(lastRssi = rssi ?: c.lastRssi, lastSnr = d.double("snr") ?: c.lastSnr, lastSeenAt = now()))
        val groupSlot = d.int("group")
        val group = groupSlot?.let { gs -> currentDevice?.let { db.groups().byDeviceGroup(it, gs) } }
        val base = MessageEntity(
            contactId = if (group == null) c.id else null,
            groupId = group?.id,
            senderContactId = if (group != null) c.id else null,
            senderName = if (group != null) c.displayName else null,
            direction = "in", kind = "text", status = MessageStatus.DELIVERED, ctr = d.int("ctr"), rssi = rssi, sentAt = now(),
            snr = d.double("snr"), hops = d.int("hops"), retry = d.int("retry"), compressed = d.bool("compressed"),
        )
        val message = when (d.str("type")) {
            "text" -> base.copy(text = d.str("text") ?: return)
            "geo" -> base.copy(kind = "geo", lat = d.double("lat") ?: return, lon = d.double("lon") ?: return, ageS = d.int("age_s"))
            "reaction" -> {
                onReaction(c, d)
                return
            }
            else -> return
        }
        val id = db.messages().insert(message)
        onIncoming(message.copy(id = id))
    }

    /** A reaction targets my message (`author: "me"`) by the low 16 bits of its counter. */
    private suspend fun onReaction(c: ContactEntity, d: JsonObject) {
        val target = d.obj("target") ?: return
        val ref = target.int("ref") ?: return
        if (target.str("author") != "me" || ref == 0) return // ref 0 is the pairing ping, nothing to show
        val m = db.messages().ownByRef(c.id, ref) ?: return
        db.messages().update(m.copy(peerReaction = if (d.isNull("emoji")) null else d.str("emoji")))
    }

    companion object {
        /** Slot of a contact the device no longer holds (device slots are 1 to 32). */
        const val NO_SLOT = 0

        /** Local contact state: the antenna lost this contact's keys (reset, reflash); pair again. */
        const val UNPAIRED = "unpaired"

        /** Empty "remove my reaction" packet after pairing; the peer's first valid packet activates the contact. */
        fun pingArgs(slot: Int) = buildJsonObject {
            put("to", slot); put("type", "reaction")
            putJsonObject("target") { put("author", "peer"); put("ref", 0) }
            put("emoji", JsonNull)
            put("ack", true)
        }
    }
}
