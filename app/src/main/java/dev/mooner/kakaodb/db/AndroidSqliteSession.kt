package dev.mooner.kakaodb.db

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteQuery
import dev.mooner.kakaodb.core.sqlite.ISqliteSession
import dev.mooner.kakaodb.core.sqlite.Row
import dev.mooner.kakaodb.core.sqlite.SQL_IDENTIFIER
import dev.mooner.kakaodb.core.sqlite.SessionException
import dev.mooner.kakaodb.core.sqlite.SessionFactory
import dev.mooner.kakaodb.core.sqlite.requiringFiles
import dev.mooner.kakaodb.rootd.WalFiles
import java.io.File

/**
 * A read-only session on the framework SQLite.
 *
 * A read-only database that isn't opened with WAL enabled keeps a single (primary) connection, so every query, and
 * `PRAGMA data_version` in particular, runs on the same connection, as the decoder requires.
 */
class AndroidSqliteSession(path: String) : ISqliteSession {

    private val db: SQLiteDatabase = wrap {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    override fun attach(name: String, path: String) = wrap {
        require(SQL_IDENTIFIER.matches(name)) { "Illegal schema name: $name" }
        db.execSQL("ATTACH DATABASE ? AS $name", arrayOf(path))
    }

    override fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = wrap {
        cursor(sql, args).use { cursor ->
            val row = CursorRow(cursor)
            buildList { while (cursor.moveToNext()) add(mapper(row)) }
        }
    }

    override fun <T> queryFirst(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = wrap {
        cursor(sql, args).use { cursor -> if (cursor.moveToFirst()) mapper(CursorRow(cursor)) else null }
    }

    override fun dataVersion(schema: String): Long {
        require(SQL_IDENTIFIER.matches(schema)) { "Illegal schema name: $schema" }
        return queryFirst("PRAGMA $schema.data_version") { (it.value(0) as? Number)?.toLong() } ?: 0L
    }

    override fun columnsOf(schema: String, table: String): Set<String> {
        require(SQL_IDENTIFIER.matches(schema) && SQL_IDENTIFIER.matches(table)) { "Illegal name: $schema.$table" }
        return query("PRAGMA $schema.table_info($table)") { it.value("name").toString().lowercase() }.toSet()
    }

    override fun close() =
        db.close()

    /** Binds [args] with their own types: rawQuery would bind them all as strings, which breaks integer compares. */
    private fun cursor(sql: String, args: Array<out Any?>): Cursor =
        db.rawQueryWithFactory({ _, driver, editTable, query ->
            for ((i, arg) in args.withIndex())
                query.bind(i + 1, arg)
            SQLiteCursor(driver, editTable, query)
        }, sql, NO_ARGS, "")

    private fun SQLiteQuery.bind(index: Int, arg: Any?) =
        when (arg) {
            null         -> bindNull(index)
            is Boolean   -> bindLong(index, if (arg) 1L else 0L)
            is Long      -> bindLong(index, arg)
            is Int       -> bindLong(index, arg.toLong())
            is Short     -> bindLong(index, arg.toLong())
            is Byte      -> bindLong(index, arg.toLong())
            is Double    -> bindDouble(index, arg)
            is Float     -> bindDouble(index, arg.toDouble())
            is ByteArray -> bindBlob(index, arg)
            else         -> bindString(index, arg.toString())
        }

    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: SQLiteException) {
            throw SessionException(e.message ?: e.toString(), e)
        }

    private class CursorRow(private val cursor: Cursor) : Row {

        private val indices: Map<String, Int> =
            cursor.columnNames.withIndex().associate { (i, name) -> name.lowercase() to i }

        override fun has(column: String): Boolean =
            column.lowercase() in indices

        override fun value(column: String): Any? =
            indices[column.lowercase()]?.let(::value)

        override fun value(index: Int): Any? =
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT   -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_STRING  -> cursor.getString(index)
                Cursor.FIELD_TYPE_BLOB    -> cursor.getBlob(index)
                else                      -> null
            }
    }

    companion object {
        /** rawQuery's string arguments: none, the factory binds them typed. */
        private val NO_ARGS = emptyArray<String>()

        /** Checked on every open, reconnects included: the files may be gone since [DirectAccess.prepare] looked. */
        val factory = SessionFactory { main, sub ->
            for (path in listOf(main, sub)) {
                val file = File(path)
                WalFiles.missing(file)?.let { throw IllegalStateException(walFilesMissing(file.name, it)) }
            }
            AndroidSqliteSession(main).also { it.attach("db2", sub) }
        }.requiringFiles()
    }
}
