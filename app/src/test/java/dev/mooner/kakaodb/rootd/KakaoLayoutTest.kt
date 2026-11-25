package dev.mooner.kakaodb.rootd

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KakaoTalk 26.10 moved the account's databases and DataStore files into a folder per account. */
class KakaoLayoutTest {

    private lateinit var app: File
    private lateinit var databases: File

    @BeforeTest
    fun setUp() {
        app = Files.createTempDirectory("com.kakao.talk").toFile()
        databases = File(app, "databases").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        app.deleteRecursively()
    }

    private fun file(parent: File, path: String, modified: Long? = null): File =
        File(parent, path).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(0))
            if (modified != null)
                setLastModified(modified)
        }

    @Test
    fun `before 26-10 the databases folder holds the databases and is the account`() {
        file(databases, "KakaoTalk.db")
        file(databases, "KakaoTalk2.db")

        assertEquals(emptyList(), KakaoLayout.accountDirs(databases))
        assertNull(KakaoLayout.accountOf(databases))
        assertEquals(app, KakaoLayout.appDataDir(databases))
    }

    @Test
    fun `account folders are the ones holding KakaoTalk_db, most recently written first`() {
        file(databases, "a1/KakaoTalk.db", modified = 1_000_000L)
        file(databases, "b2/KakaoTalk.db", modified = 2_000_000L)
        // What 26.10 leaves directly in databases: neither is an account folder.
        file(databases, "emoticon_common.db")
        file(databases, "c3/other.db")

        assertEquals(listOf("b2", "a1"), KakaoLayout.accountDirs(databases).map(File::getName))

        // Commits go to the -wal: KakaoTalk.db itself may be older.
        file(databases, "a1/KakaoTalk.db-wal", modified = 3_000_000L)
        assertEquals(listOf("a1", "b2"), KakaoLayout.accountDirs(databases).map(File::getName))
    }

    @Test
    fun `account folders written at the same time come by name`() {
        file(databases, "f/KakaoTalk.db", modified = 1_000_000L)
        file(databases, "e/KakaoTalk.db", modified = 1_000_000L)
        assertEquals(listOf("e", "f"), KakaoLayout.accountDirs(databases).map(File::getName))
    }

    @Test
    fun `a folder that can't be listed has no account folders`() {
        assertEquals(emptyList(), KakaoLayout.accountDirs(File(app, "missing")))
    }

    @Test
    fun `an account folder belongs to the data directory above databases`() {
        val account = File(databases, "0123456789abcdef")
        assertEquals("0123456789abcdef", KakaoLayout.accountOf(account))
        assertEquals(app, KakaoLayout.appDataDir(account))
    }

    @Test
    fun `a folder laid out otherwise keeps its parent as the data directory`() {
        val copy = File(app, "copy").apply { mkdirs() }
        assertNull(KakaoLayout.accountOf(copy))
        assertEquals(app, KakaoLayout.appDataDir(copy))
    }

    @Test
    fun `the seed is looked for in the account's Features file first, never in another account's`() {
        val account = File(databases, "acct")
        val datastore = File(app, "files/datastore")
        val features = file(datastore, "acct/Features.preferences_pb")
        val localUser = file(datastore, "acct/LocalUser.preferences_pb")
        file(datastore, "acct/notes.txt")
        file(datastore, "other/Features.preferences_pb")
        val legacy = file(datastore, "Feature_DataStore.pref.preferences_pb")
        val accounts = file(datastore, "Accounts.preferences_pb")

        assertEquals(listOf(features, legacy, localUser, accounts), KakaoLayout.seedFiles(account))
    }

    @Test
    fun `before 26-10 the seed is in the files earlier versions used`() {
        val datastore = File(app, "files/datastore")
        val legacy = file(datastore, "Feature_DataStore.pref.preferences_pb")
        val older = file(app, "files/Feature_DataStore.pref.preferences_pb")
        val device = file(datastore, "Device.preferences_pb")
        // Not this layout's account.
        file(datastore, "acct/Features.preferences_pb")

        assertEquals(listOf(legacy, older, device), KakaoLayout.seedFiles(databases))
    }

    @Test
    fun `large DataStore files aren't searched, but a known seed file is read whatever its size`() {
        val account = File(databases, "acct")
        val datastore = File(app, "files/datastore")
        val features = file(datastore, "acct/Features.preferences_pb")
        val big = file(datastore, "acct/Big.preferences_pb")
        for (large in listOf(features, big))
            RandomAccessFile(large, "rw").use { it.setLength(KakaoLayout.MAX_SCANNED_SEED_FILE + 1) }

        assertEquals(listOf(features), KakaoLayout.seedFiles(account))
    }

    @Test
    fun `the error names the account's seed file first`() {
        val known = KakaoLayout.knownSeedPaths(File(databases, "acct"))
        assertEquals("files/datastore/acct/Features.preferences_pb", known.first())
        assertTrue("files/datastore/Feature_DataStore.pref.preferences_pb" in known)
        assertEquals(
            listOf(
                "files/datastore/Feature_DataStore.pref.preferences_pb",
                "files/Feature_DataStore.pref.preferences_pb",
                "datastore/Feature_DataStore.pref.preferences_pb",
            ),
            KakaoLayout.knownSeedPaths(databases),
        )
    }
}
