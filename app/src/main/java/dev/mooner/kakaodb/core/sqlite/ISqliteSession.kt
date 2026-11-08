package dev.mooner.kakaodb.core.sqlite

import java.io.File
import java.io.FileNotFoundException

/**
 * A long-lived, read-only connection to the KakaoTalk database.
 *
 * Not thread-safe: callers serialize access on one thread (the decoder's dispatcher).
 * Implementations wrap their driver's errors in [SessionException], which the decoder answers by reconnecting.
 */
interface ISqliteSession : AutoCloseable {

    fun attach(name: String, path: String)

    fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T>

    fun <T> queryFirst(sql: String, vararg args: Any?, mapper: (Row) -> T): T?

    /**
     * `PRAGMA <schema>.data_version`. Changes whenever another connection (KakaoTalk) commits to that file.
     * The value is per connection, so it only means something while the same connection is kept open.
     */
    fun dataVersion(schema: String = "main"): Long

    /** Lower-case names of the columns the table actually has; empty if the table doesn't exist. */
    fun columnsOf(schema: String, table: String): Set<String>
}

/** A database error from a session's driver (JDBC, Android SQLite...). */
class SessionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Opens a session on KakaoTalk.db with KakaoTalk2.db attached as `db2`. */
fun interface SessionFactory {

    fun open(mainPath: String, subPath: String): ISqliteSession

    /**
     * Opens a session on crypto_user_database in [dbDir] (as `main`), where newer KakaoTalk versions keep the friend
     * list. It is encrypted with SQLCipher: null when this driver can't read it. Throws when it can but fails.
     */
    fun openUserDatabase(dbDir: String): ISqliteSession? = null
}

/**
 * Checks from this process that both files exist before opening: for drivers that open the files themselves, which
 * would otherwise fail less clearly, or create an empty database. Not for sessions opened by someone who can see
 * files this process can't (the root helper).
 */
fun SessionFactory.requiringFiles(): SessionFactory {
    val base = this
    return object : SessionFactory {

        override fun open(mainPath: String, subPath: String): ISqliteSession {
            if (!File(mainPath).exists() || !File(subPath).exists())
                throw FileNotFoundException("${File(mainPath).name} / ${File(subPath).name} not found in ${File(mainPath).parent}")
            return base.open(mainPath, subPath)
        }

        override fun openUserDatabase(dbDir: String): ISqliteSession? =
            base.openUserDatabase(dbDir)
    }
}

val SQL_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
