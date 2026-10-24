package ch.offgrid.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [MIGRATION_3_4_SQL] on a plain SQLite database built from the exported Room schemas. Room's own
 * `MigrationTestHelper` needs a device; this checks the same two things: the data survives, and the result has
 * exactly the tables `4.json` describes (columns, types, null rules, defaults, keys).
 */
class MigrationTest {
    private lateinit var old: Connection
    private lateinit var fresh: Connection

    @Before
    fun open() {
        old = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        createSchema(old, 3)
        createSchema(fresh, 4)
    }

    @After
    fun close() {
        old.close()
        fresh.close()
    }

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/ch.offgrid.android.data.OffGridDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun createSchema(db: Connection, version: Int) {
        schema(version)["entities"]!!.jsonArray.forEach { entity ->
            val e = entity.jsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.createStatement().use { it.execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            (e["indices"] as? JsonArray).orEmpty().forEach { index ->
                db.createStatement().use { it.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
        }
    }

    private fun exec(db: Connection, sql: String) = db.createStatement().use { it.execute(sql) }

    private fun columns(db: Connection, table: String): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) {
                    // name, type, notnull, default, pk position; the column order is not part of the contract.
                    add(listOf(rs.getString("name"), rs.getString("type"), rs.getString("notnull"), rs.getString("dflt_value"), rs.getString("pk")))
                }
            }.sortedBy { it[0] }
        }
    }

    @Test
    fun migrationKeepsGroupsAndMembersAndGivesTheNewShape() {
        exec(old, "INSERT INTO contacts VALUES (1,'D1',3,'Anna',NULL,NULL,'E841','pkA','fp','active',NULL,NULL,NULL,1)")
        exec(old, "INSERT INTO contacts VALUES (2,'D1',4,'Luca',NULL,NULL,'5C07','pkL','fp','active',NULL,NULL,NULL,1)")
        exec(old, "INSERT INTO groups VALUES (1,'D1',2,'Gipfeltour',100)")
        exec(old, "INSERT INTO group_members VALUES (1,2)")
        exec(old, "INSERT INTO group_members VALUES (1,1)")
        exec(
            old,
            "INSERT INTO messages (groupId, direction, kind, text, status, senderContactId, senderName, sentAt) " +
                "VALUES (1,'in','text','Hoi',3,1,'Anna',200)",
        )

        MIGRATION_3_4_SQL.forEach { exec(old, it) }

        old.createStatement().use { st ->
            st.executeQuery("SELECT gid, epoch, owner, name, deviceGroupId FROM groups").use { rs ->
                assertEquals(true, rs.next())
                assertEquals("", rs.getString("gid"))
                assertEquals(1, rs.getInt("epoch"))
                assertEquals(1, rs.getInt("owner"))
                assertEquals("Gipfeltour", rs.getString("name"))
                assertEquals(2, rs.getInt("deviceGroupId"))
            }
            // Old members get indexes 1, 2 in contact order, with the NodeID of their contact.
            st.executeQuery("SELECT member, contactId, nodeId, keyState FROM group_members ORDER BY member").use { rs ->
                val rows = buildList { while (rs.next()) add(listOf(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4))) }
                assertEquals(listOf(listOf(1, 1, "E841", "none"), listOf(2, 2, "5C07", "none")), rows)
            }
            st.executeQuery("SELECT text, senderNodeId FROM messages").use { rs ->
                assertEquals(true, rs.next())
                assertEquals("Hoi", rs.getString("text"))
                assertEquals(null, rs.getString("senderNodeId"))
            }
        }
    }

    @Test
    fun migratedTablesMatchTheExportedVersion4Schema() {
        MIGRATION_3_4_SQL.forEach { exec(old, it) }
        for (table in listOf("groups", "group_members", "messages", "contacts")) {
            assertEquals(table, columns(fresh, table), columns(old, table))
        }
    }

    @Test
    fun anOldGroupWithoutMembersMigrates() {
        exec(old, "INSERT INTO groups VALUES (1,'D1',1,'Leer',100)")
        MIGRATION_3_4_SQL.forEach { exec(old, it) }
        old.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM group_members").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }
}
