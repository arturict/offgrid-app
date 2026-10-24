package ch.offgrid.android.domain

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import ch.offgrid.android.data.ContactDao
import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.GroupDao
import ch.offgrid.android.data.GroupEntity
import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.KeyState
import ch.offgrid.android.data.MaintenanceDao
import ch.offgrid.android.data.MessageDao
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.OffGridDatabase
import ch.offgrid.android.data.SettingDao
import ch.offgrid.android.data.SettingEntity
import ch.offgrid.android.data.UnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory DAOs behind an [OffGridDatabase], so [Messaging] runs against the simulated antenna without Room or a
 * device. Only the queries [Messaging] uses are modelled with their SQL semantics; the rest throw.
 */
class FakeDatabase : OffGridDatabase() {
    val contactDao = FakeContactDao()
    val messageDao = FakeMessageDao()
    val groupDao = FakeGroupDao()
    val settingDao = FakeSettingDao()

    override fun contacts(): ContactDao = contactDao
    override fun messages(): MessageDao = messageDao
    override fun groups(): GroupDao = groupDao
    override fun settings(): SettingDao = settingDao
    override fun maintenance(): MaintenanceDao = throw UnsupportedOperationException()

    override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = throw UnsupportedOperationException()
    override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, "contacts")
    override fun clearAllTables() = throw UnsupportedOperationException()
}

class FakeContactDao : ContactDao {
    val rows = MutableStateFlow<List<ContactEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<ContactEntity>> = rows
    override fun observe(id: Long): Flow<ContactEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun bySlot(deviceId: String, slot: Int) = rows.value.firstOrNull { it.deviceId == deviceId && it.deviceContactId == slot }
    override suspend fun byPublicKey(publicKey: String) = rows.value.firstOrNull { it.publicKey == publicKey }
    override suspend fun all() = rows.value
    override suspend fun insert(contact: ContactEntity): Long = nextId++.also { rows.value += contact.copy(id = it) }
    override suspend fun update(contact: ContactEntity) {
        rows.value = rows.value.map { if (it.id == contact.id) contact else it }
    }
    override suspend fun delete(id: Long) {
        rows.value = rows.value.filter { it.id != id }
    }
}

class FakeMessageDao : MessageDao {
    val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
    private var nextId = 1L

    override fun observeForContact(contactId: Long) = rows.map { l -> l.filter { it.contactId == contactId } }
    override fun observeForGroup(groupId: Long) = rows.map { l -> l.filter { it.groupId == groupId } }
    override fun observe(id: Long): Flow<MessageEntity?> = rows.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override fun observeLatest(): Flow<List<MessageEntity>> = rows.map { l ->
        l.groupBy { it.contactId to it.groupId }.values.map { g -> g.maxWith(compareBy({ it.sentAt }, { it.id })) }
    }
    override fun observeUnread(): Flow<List<UnreadCount>> = rows.map { l ->
        l.filter { it.direction == "in" && it.readAt == null }.groupBy { it.contactId to it.groupId }
            .map { (k, v) -> UnreadCount(k.first, k.second, v.size) }
    }
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun byMsgRef(ref: Int, session: String) =
        rows.value.filter { it.msgRef == ref && it.refSession == session && it.status in listOf(1, 2, 5) }.maxByOrNull { it.id }
    override suspend fun openOutgoing() = rows.value.filter { it.direction == "out" && it.msgRef != null && it.status in listOf(1, 2, 5) }.sortedBy { it.id }
    override suspend fun localQueue() = rows.value.filter { it.status == 0 && it.direction == "out" }.sortedWith(compareBy({ it.sentAt }, { it.id }))
    override suspend fun ownByRef(contactId: Long, ref: Int) =
        rows.value.filter { it.contactId == contactId && it.direction == "out" && it.ctr != null && (it.ctr!! and 65535) == ref }.maxByOrNull { it.id }
    override suspend fun insert(message: MessageEntity): Long = nextId++.also { rows.value += message.copy(id = it) }
    override suspend fun update(message: MessageEntity) {
        rows.value = rows.value.map { if (it.id == message.id) message else it }
    }
    override suspend fun setReaction(id: Long, emoji: String?) {
        rows.value = rows.value.map { if (it.id == id) it.copy(peerReaction = emoji) else it }
    }
    override suspend fun markContactRead(contactId: Long, at: Long) = throw UnsupportedOperationException()
    override suspend fun markGroupRead(groupId: Long, at: Long) = throw UnsupportedOperationException()
    override suspend fun deleteForContact(contactId: Long) {
        rows.value = rows.value.filter { it.contactId != contactId }
    }
    override suspend fun deleteForGroup(groupId: Long) {
        rows.value = rows.value.filter { it.groupId != groupId }
    }
    override suspend fun all() = rows.value
}

class FakeGroupDao : GroupDao {
    val groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<GroupEntity>> = groups
    override fun observe(id: Long): Flow<GroupEntity?> = groups.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun byId(id: Long) = groups.value.firstOrNull { it.id == id }
    override suspend fun byDeviceGroup(deviceId: String, groupId: Int) =
        groups.value.firstOrNull { it.deviceId == deviceId && it.deviceGroupId == groupId }
    override fun observeMembers(): Flow<List<GroupMemberEntity>> = members
    override suspend fun membersOf(groupId: Long) = members.value.filter { it.groupId == groupId }.sortedBy { it.member }
    override suspend fun insert(group: GroupEntity): Long = nextId++.also { groups.value += group.copy(id = it) }
    override suspend fun update(group: GroupEntity) {
        groups.value = groups.value.map { if (it.id == group.id) group else it }
    }
    override suspend fun putMembers(members: List<GroupMemberEntity>) {
        this.members.value = this.members.value.filter { old -> members.none { it.groupId == old.groupId && it.member == old.member } } + members
    }
    override suspend fun updateMember(member: GroupMemberEntity) {
        members.value = members.value.map { if (it.groupId == member.groupId && it.member == member.member) member else it }
    }
    override suspend fun deleteMembers(groupId: Long) {
        members.value = members.value.filter { it.groupId != groupId }
    }
    override suspend fun delete(id: Long) {
        groups.value = groups.value.filter { it.id != id }
    }
    override suspend fun bySyncRef(ref: Int, session: String) =
        members.value.firstOrNull { it.syncRef == ref && it.syncSession == session && it.keyState == KeyState.PENDING }
    override suspend fun openSyncs(deviceId: String) = members.value.filter { m ->
        m.keyState == KeyState.PENDING && m.syncRef != null && groups.value.any { it.id == m.groupId && it.deviceId == deviceId }
    }
    override suspend fun queuedSyncs() = members.value.filter { it.keyState == KeyState.QUEUED }.sortedWith(compareBy({ it.groupId }, { it.member }))
    override suspend fun all() = groups.value
    override suspend fun allMembers() = members.value
}

class FakeSettingDao : SettingDao {
    private val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun observeAll(): Flow<List<SettingEntity>> = map.map { m -> m.map { SettingEntity(it.key, it.value) } }
    override suspend fun get(key: String): String? = map.value[key]
    override suspend fun put(setting: SettingEntity) { map.value = map.value + (setting.key to setting.value) }
    override suspend fun remove(key: String) { map.value = map.value - key }
}
