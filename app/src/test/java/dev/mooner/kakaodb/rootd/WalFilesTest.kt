package dev.mooner.kakaodb.rootd

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SQLite creates a WAL database's missing -wal/-shm even for a read-only connection: those databases are refused. */
class WalFilesTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("walfiles").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a WAL database is safe only while it has both files`() {
        val db = File(dir, "KakaoTalk.db")
        val writer = DriverManager.getConnection("jdbc:sqlite:$db")
        writer.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("CREATE TABLE t (x INTEGER)")
            it.execute("INSERT INTO t VALUES (1)")
        }
        // KakaoTalk has it open.
        assertNull(WalFiles.missing(db))

        // Closed cleanly: SQLite removed both.
        writer.close()
        assertEquals("-wal, -shm", WalFiles.missing(db))

        val wal = File("$db-wal").apply { writeBytes(ByteArray(0)) }
        assertEquals("-shm", WalFiles.missing(db))
        wal.delete()
        File("$db-shm").writeBytes(ByteArray(0))
        assertEquals("-wal", WalFiles.missing(db))
    }

    @Test
    fun `a rollback journal database needs neither`() {
        val db = File(dir, "KakaoTalk2.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE t (x INTEGER)") }
        }
        assertNull(WalFiles.missing(db))
    }

    @Test
    fun `a header that isn't plain SQLite counts as WAL`() {
        // What SQLCipher's encrypted crypto_user_database looks like from outside.
        val db = File(dir, "crypto_user_database").apply { writeBytes(ByteArray(4096) { 0x5a }) }
        assertEquals("-wal, -shm", WalFiles.missing(db))
        File("$db-wal").writeBytes(ByteArray(0))
        File("$db-shm").writeBytes(ByteArray(0))
        assertNull(WalFiles.missing(db))
    }
}
