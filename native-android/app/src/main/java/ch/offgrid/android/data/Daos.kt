package ch.offgrid.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class UnreadCount(val contactId: Long?, val groupId: Long?, val n: Int)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun observe(id: Long): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun byId(id: Long): ContactEntity?

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId AND deviceContactId = :slot")
    suspend fun bySlot(deviceId: String, slot: Int): ContactEntity?

    @Query("SELECT * FROM contacts WHERE publicKey = :publicKey")
    suspend fun byPublicKey(publicKey: String): ContactEntity?

    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>

    @Insert
    suspend fun insert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY sentAt, id")
    fun observeForContact(contactId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY sentAt, id")
    fun observeForGroup(groupId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** Newest message per conversation, for the contact list preview. */
    @Query(
        """SELECT m.* FROM messages m WHERE m.id IN (
             SELECT (SELECT id FROM messages i WHERE
                       (i.contactId IS c.contactId AND i.groupId IS c.groupId)
                     ORDER BY i.sentAt DESC, i.id DESC LIMIT 1)
             FROM (SELECT DISTINCT contactId, groupId FROM messages) c)""",
    )
    fun observeLatest(): Flow<List<MessageEntity>>

    @Query(
        """SELECT contactId, groupId, COUNT(*) AS n FROM messages
           WHERE direction = 'in' AND readAt IS NULL GROUP BY contactId, groupId""",
    )
    fun observeUnread(): Flow<List<UnreadCount>>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    /** The open message the antenna knows as [ref] in the current link [session] (statuses 1, 2 and 5). */
    @Query("SELECT * FROM messages WHERE msgRef = :ref AND refSession = :session AND status IN (1, 2, 5) ORDER BY id DESC LIMIT 1")
    suspend fun byMsgRef(ref: Int, session: String): MessageEntity?

    /** Outgoing messages the antenna had and that have no final state yet. */
    @Query("SELECT * FROM messages WHERE direction = 'out' AND msgRef IS NOT NULL AND status IN (1, 2, 5) ORDER BY id")
    suspend fun openOutgoing(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 0 AND direction = 'out' ORDER BY sentAt, id")
    suspend fun localQueue(): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE contactId = :contactId AND direction = 'out' AND ctr IS NOT NULL
           AND (ctr & 65535) = :ref ORDER BY id DESC LIMIT 1""",
    )
    suspend fun ownByRef(contactId: Long, ref: Int): MessageEntity?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET peerReaction = :emoji WHERE id = :id")
    suspend fun setReaction(id: Long, emoji: String?)

    @Query("UPDATE messages SET readAt = :at WHERE contactId = :contactId AND direction = 'in' AND readAt IS NULL")
    suspend fun markContactRead(contactId: Long, at: Long)

    @Query("UPDATE messages SET readAt = :at WHERE groupId = :groupId AND direction = 'in' AND readAt IS NULL")
    suspend fun markGroupRead(groupId: Long, at: Long)

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: Long)

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observe(id: Long): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun byId(id: Long): GroupEntity?

    @Query("SELECT * FROM groups WHERE deviceId = :deviceId AND deviceGroupId = :groupId")
    suspend fun byDeviceGroup(deviceId: String, groupId: Int): GroupEntity?

    @Query("SELECT * FROM group_members")
    fun observeMembers(): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY member")
    suspend fun membersOf(groupId: Long): List<GroupMemberEntity>

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMembers(members: List<GroupMemberEntity>)

    @Update
    suspend fun updateMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: Long)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: Long)

    /** Replaces the member list of a group in one step, so a reader never sees a half-written list. */
    @Transaction
    suspend fun replaceMembers(groupId: Long, members: List<GroupMemberEntity>) {
        deleteMembers(groupId)
        putMembers(members)
    }

    /** The member whose open `group.sync` the antenna knows as [ref] in the current link [session]. */
    @Query("SELECT * FROM group_members WHERE syncRef = :ref AND syncSession = :session AND keyState = 'pending'")
    suspend fun bySyncRef(ref: Int, session: String): GroupMemberEntity?

    /** Syncs the antenna accepted that have no final state yet, of the groups held on [deviceId]. */
    @Query(
        """SELECT m.* FROM group_members m JOIN groups g ON g.id = m.groupId
           WHERE m.keyState = 'pending' AND m.syncRef IS NOT NULL AND g.deviceId = :deviceId ORDER BY m.groupId, m.member""",
    )
    suspend fun openSyncs(deviceId: String): List<GroupMemberEntity>

    /** Syncs that still have to be handed to the antenna, oldest group first. */
    @Query("SELECT * FROM group_members WHERE keyState = 'queued' ORDER BY groupId, member")
    suspend fun queuedSyncs(): List<GroupMemberEntity>

    @Query("SELECT * FROM groups")
    suspend fun all(): List<GroupEntity>

    @Query("SELECT * FROM group_members")
    suspend fun allMembers(): List<GroupMemberEntity>
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun remove(key: String)
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM contacts")
    suspend fun clearContacts()

    @Query("DELETE FROM group_members")
    suspend fun clearMembers()

    @Query("DELETE FROM groups")
    suspend fun clearGroups()

    @Insert
    suspend fun insertContacts(items: List<ContactEntity>)

    @Insert
    suspend fun insertMessages(items: List<MessageEntity>)

    @Insert
    suspend fun insertGroups(items: List<GroupEntity>)

    @Insert
    suspend fun insertMembers(items: List<GroupMemberEntity>)

    @Transaction
    suspend fun clearAll() {
        clearMessages()
        clearMembers()
        clearGroups()
        clearContacts()
    }

    /** Import replaces all contacts and messages (screen text "Der Import ersetzt alle …"). */
    @Transaction
    suspend fun replaceAll(
        contacts: List<ContactEntity>,
        messages: List<MessageEntity>,
        groups: List<GroupEntity>,
        members: List<GroupMemberEntity>,
    ) {
        clearAll()
        insertContacts(contacts)
        insertGroups(groups)
        insertMembers(members)
        insertMessages(messages)
    }
}

@Database(
    entities = [ContactEntity::class, MessageEntity::class, GroupEntity::class, GroupMemberEntity::class, SettingEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class OffGridDatabase : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun messages(): MessageDao
    abstract fun groups(): GroupDao
    abstract fun settings(): SettingDao
    abstract fun maintenance(): MaintenanceDao
}
