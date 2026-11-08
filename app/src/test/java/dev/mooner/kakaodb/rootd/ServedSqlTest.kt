package dev.mooner.kakaodb.rootd

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the root helper runs. DatabaseDecoderTest checks every statement the decoder makes against it too. */
class ServedSqlTest {

    @Test
    fun `serves selects and the two pragmas`() {
        listOf(
            "SELECT 1",
            "  select _id, id FROM main.chat_logs WHERE _id > ? ORDER BY _id LIMIT ?  ",
            "SELECT MAX(_id) FROM main.chat_logs",
            "PRAGMA db2.data_version",
            "PRAGMA data_version;",
            "PRAGMA main.table_info(chat_logs)",
            "pragma db2 . table_info ( open_chat_member )",
        ).forEach { assertTrue(ServedSql.isServed(it), it) }
    }

    @Test
    fun `refuses everything else`() {
        listOf(
            "ATTACH DATABASE '/data/data/other.app/databases/x.db' AS x",
            "SELECT 1; ATTACH DATABASE 'x' AS y",
            "/* comment */ ATTACH 'x' AS y",
            "VACUUM INTO '/sdcard/copy.db'",
            "DELETE FROM chat_logs",
            "PRAGMA writable_schema = 1",
            "PRAGMA journal_mode=DELETE",
            "PRAGMA main.table_info(chat_logs); PRAGMA x",
            "WITH x AS (SELECT 1) SELECT * FROM x",
            "SELECT",
            "",
        ).forEach { assertFalse(ServedSql.isServed(it), it) }
    }
}
