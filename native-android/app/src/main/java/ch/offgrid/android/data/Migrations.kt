package ch.offgrid.android.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 to 2: `messages.refSession` for the reconnect reconciliation (see `OutboxReconciler`).
 *
 * Version 1 cleared the refs of sent messages whenever the antenna restarted, which left them at
 * "gesendet" for good although nobody knew whether they arrived. Those direct messages become
 * "Nicht zugestellt" (reason `lost`) so they can be sent again. Group messages keep "gesendet": without
 * an end-to-end ACK that is their final state.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN refSession TEXT")
        db.execSQL(
            "UPDATE messages SET status = 4, failReason = 'lost' " +
                "WHERE direction = 'out' AND groupId IS NULL AND msgRef IS NULL AND status IN (1, 2)",
        )
    }
}

/** 2 to 3: delivery and signal details per message for the details sheet, and "zugestellt, während offline". */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("snr REAL", "hops INTEGER", "retry INTEGER", "rttMs INTEGER", "compressed INTEGER", "bytesOnAir INTEGER",
            "airtimeMs INTEGER", "deviceState TEXT", "deliveredOffline INTEGER NOT NULL DEFAULT 0")
            .forEach { db.execSQL("ALTER TABLE messages ADD COLUMN $it") }
    }
}

/**
 * The statements of [MIGRATION_3_4], separate so a unit test can run them on a plain SQLite database.
 *
 * Groups follow the firmware's `group.*` API (link spec 6): a group has a `gid`, an `epoch` and an owner,
 * a member is identified by its index and NodeID and may have no contact, and the key state of every
 * member is kept for the owner's `group.sync`. The old `group_members` table had (groupId, contactId) as its
 * key, so it is rebuilt; the old members get consecutive indexes 1, 2, ... in contact order. Groups from
 * version 3 were made with the earlier proposal for the API and are all owned by this phone.
 */
val MIGRATION_3_4_SQL = listOf(
    "ALTER TABLE groups ADD COLUMN gid TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE groups ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE groups ADD COLUMN owner INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE messages ADD COLUMN senderNodeId TEXT",
    "CREATE TABLE IF NOT EXISTS group_members_v4 (`groupId` INTEGER NOT NULL, `member` INTEGER NOT NULL, `contactId` INTEGER, " +
        "`nodeId` TEXT NOT NULL, `keyState` TEXT NOT NULL DEFAULT 'none', `syncRef` INTEGER, `syncSession` TEXT, `syncReason` TEXT, " +
        "PRIMARY KEY(`groupId`, `member`))",
    "INSERT INTO group_members_v4 (groupId, member, contactId, nodeId) " +
        "SELECT m.groupId, (SELECT COUNT(*) FROM group_members o WHERE o.groupId = m.groupId AND o.contactId <= m.contactId), " +
        "m.contactId, COALESCE((SELECT c.nodeId FROM contacts c WHERE c.id = m.contactId), '') FROM group_members m",
    "DROP TABLE group_members",
    "ALTER TABLE group_members_v4 RENAME TO group_members",
)

/** 3 to 4: groups on the firmware's `group.*` API. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}
