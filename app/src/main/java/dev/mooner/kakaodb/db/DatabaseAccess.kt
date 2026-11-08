package dev.mooner.kakaodb.db

import dev.mooner.kakaodb.core.DecoderOptions
import dev.mooner.kakaodb.core.sqlite.SessionFactory
import dev.mooner.kakaodb.rootd.Protocol
import dev.mooner.kakaodb.rootd.WalFiles
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException

/**
 * How the plugin gets to read KakaoTalk's database files.
 *
 * KakaoTalk keeps them in its private data directory, so StarLight can't read them as they are. Each mode is a way
 * around that; [prepare] runs before every connection, off the main thread.
 */
interface DatabaseAccess {

    val mode: AccessMode

    /** Gets the database files in [dir] ready to be opened with [sessionFactory], or tells why they can't be. */
    fun prepare(dir: File): AccessResult

    /** Opens the sessions the decoder reads through. */
    val sessionFactory: SessionFactory

    /** Signals as the database files in [dir] change. May stay silent when watching isn't possible: polling covers that. */
    fun fileChanges(dir: File): Flow<Unit>

    companion object {
        /** [rootHelperApk] is where StarLight extracted the asset [RootAccess.HELPER_ASSET]. */
        fun of(mode: AccessMode, rootHelperApk: File): DatabaseAccess =
            when (mode) {
                AccessMode.DIRECT -> DirectAccess
                AccessMode.ROOT   -> RootAccess(rootHelperApk)
                AccessMode.SHARED -> SharedAccess()
            }
    }
}

enum class AccessMode(val key: String) {
    /** The files are already readable: permissions set by hand, a copy, or a system without SELinux (redroid). */
    DIRECT("direct"),
    /** A helper started with su opens the files where they are and the plugin queries it (see [RootAccess]). */
    ROOT("root"),
    SHARED("shared");

    companion object {
        fun of(key: String?): AccessMode =
            entries.firstOrNull { it.key == key } ?: DIRECT
    }
}

/** Why [name] isn't opened while its [missing] side files ("-wal", "-shm" or both) are gone: see [WalFiles]. */
internal fun walFilesMissing(name: String, missing: String): String =
    tr(
        "$name has no $missing, and opening it would create them in KakaoTalk's place: open KakaoTalk once, then connect again.",
        "$name 의 $missing 파일이 없어요. 지금 열면 카카오톡 대신 이 파일을 만들게 돼요. 카카오톡을 한 번 실행한 뒤 다시 연결하세요.",
    )

sealed class AccessResult {

    /** The files can be read. [warnings] are things that may still make the connection fail. */
    data class Ready(val warnings: List<String> = emptyList()) : AccessResult()

    data class Failed(val reason: String) : AccessResult()
}

/** Reads the files as they are; only checks that this process can. */
object DirectAccess : DatabaseAccess {

    override val mode: AccessMode = AccessMode.DIRECT

    override val sessionFactory: SessionFactory = AndroidSqliteSession.factory

    override fun prepare(dir: File): AccessResult =
        checkReadable(dir)

    override fun fileChanges(dir: File): Flow<Unit> =
        DatabaseWatcher.localChanges(dir)

    /**
     * Checks what SQLite needs for a read-only connection to a WAL database: the main files readable, the -wal files
     * readable, and the -shm files writable (readers take their locks there).
     */
    fun checkReadable(dir: File): AccessResult {
        for (name in listOf(DecoderOptions.MAIN_DB, DecoderOptions.SUB_DB)) {
            val file = File(dir, name)
            if (!file.exists())
                return AccessResult.Failed(
                    if (dir.canExecute()) tr("${file.path} doesn't exist", "${file.path} 파일이 없어요")
                    else tr("Can't access ${dir.path}: permission denied", "${dir.path}에 접근할 권한이 없어요")
                )
            if (!file.canRead())
                return AccessResult.Failed(tr("Can't read ${file.path}: permission denied", "${file.path}을 읽을 권한이 없어요"))
            WalFiles.missing(file)?.let { return AccessResult.Failed(walFilesMissing(name, it)) }
        }
        val warnings = buildList {
            for (name in listOf(DecoderOptions.MAIN_DB, DecoderOptions.SUB_DB)) {
                val wal = File(dir, "$name-wal")
                val shm = File(dir, "$name-shm")
                if (wal.exists() && !wal.canRead())
                    add(tr("Can't read ${wal.name}: recent messages will be missing", "${wal.name}을 읽을 수 없어 최근 메시지가 누락돼요"))
                if (shm.exists() && !shm.canWrite())
                    add(tr("${shm.name} isn't writable: SQLite may fail to open the database", "${shm.name}에 쓸 수 없어 DB 열기에 실패할 수 있어요"))
            }
            if (!dir.canRead())
                add(tr("Can't list ${dir.path}: changes are found by polling only", "${dir.path} 목록을 읽을 수 없어 주기적 확인으로만 변경을 감지해요"))
        }
        return AccessResult.Ready(warnings)
    }
}

/**
 * Root mode, for Android 10+.
 *
 * Opening up file modes with su isn't enough there: SELinux MLS categories keep this app from reading another app's
 * data whatever the modes say, and relabelling KakaoTalk's files could lock KakaoTalk out of them. So a helper started
 * with su under app_process ([RootHelper], running dev.mooner.kakaodb.rootd.RootDaemon) opens the files where they
 * are, read-only, and the plugin queries it over a local socket. Nothing is copied: commits are seen as KakaoTalk
 * makes them, and the helper's inotify reports file changes. It also opens crypto_user_database (SQLCipher), where
 * newer KakaoTalk versions keep the friend list.
 */
class RootAccess(helperApk: File) : DatabaseAccess {

    private val apk = HelperApk(helperApk)

    override val mode: AccessMode = AccessMode.ROOT

    override val sessionFactory: SessionFactory = RootSqliteSession.factory(apk)

    /** Starts the helper (su may prompt the first time) and has it check the files. */
    override fun prepare(dir: File): AccessResult {
        val masks = try {
            RootHelper.connect(apk, RootHelper.SESSION_TIMEOUT_MILLIS).use { channel ->
                channel.send(Protocol.OP_CHECK) {
                    writeString(dir.path)
                    writeNames(DB_FILES)
                }
                channel.expectOk()
                if (!channel.input.readBoolean())
                    return AccessResult.Failed(tr("${dir.path} doesn't exist", "${dir.path} 폴더가 없어요"))
                DB_FILES.associateWith { channel.input.readUnsignedByte() }
            }
        } catch (e: RootException) {
            return AccessResult.Failed(e.message ?: e.toString())
        } catch (e: IOException) {
            return AccessResult.Failed(
                tr("The root helper stopped answering: ${e.message}", "루트 도우미가 응답하지 않아요: ${e.message}")
            )
        }

        for ((name, mask) in masks) {
            if ((mask and Protocol.FILE_EXISTS) == 0)
                return AccessResult.Failed(tr("${File(dir, name).path} doesn't exist", "${File(dir, name).path} 파일이 없어요"))
            // The helper would have to create them, as root and with its own SELinux label: it refuses to.
            if ((mask and Protocol.FILE_UNSAFE) != 0) {
                val missing = listOfNotNull(
                    "-wal".takeIf { (mask and Protocol.FILE_WAL) == 0 },
                    "-shm".takeIf { (mask and Protocol.FILE_SHM) == 0 },
                ).joinToString(", ")
                return AccessResult.Failed(walFilesMissing(name, missing))
            }
        }
        return AccessResult.Ready()
    }

    override fun fileChanges(dir: File): Flow<Unit> =
        RootHelper.changes(apk, dir, DatabaseWatcher.WATCHED_FILES)

    companion object {
        /** The plugin asset holding the helper; pass StarlightPlugin.getAsset(HELPER_ASSET) to [DatabaseAccess.of]. */
        const val HELPER_ASSET = HelperApk.ASSET

        private val DB_FILES = listOf(DecoderOptions.MAIN_DB, DecoderOptions.SUB_DB)
    }
}
