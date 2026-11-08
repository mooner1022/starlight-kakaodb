package dev.mooner.kakaodb.db

import dev.mooner.kakaodb.core.sqlite.ISqliteSession
import dev.mooner.kakaodb.core.sqlite.Row
import dev.mooner.kakaodb.core.sqlite.SQL_IDENTIFIER
import dev.mooner.kakaodb.core.sqlite.SessionException
import dev.mooner.kakaodb.core.sqlite.SessionFactory
import dev.mooner.kakaodb.rootd.Protocol
import java.io.DataOutputStream
import java.io.IOException

/**
 * A read-only session held by the root helper ([RootHelper]): the helper opens KakaoTalk's files where they are and
 * this side sends it SQL. Each session has its own socket, and the helper its own connection for it, so every query,
 * and `PRAGMA data_version` in particular, runs on the same connection, as the decoder requires.
 *
 * [openOp] says what the helper opens for it: KakaoTalk.db ([Protocol.OP_OPEN], with a path), or crypto_user_database
 * ([Protocol.OP_OPEN_USER_DB], with the database directory).
 *
 * Any failure, the helper's errors included, is a [SessionException]: the decoder then opens a new session, which
 * starts the helper again if it died.
 */
internal class RootSqliteSession(connect: () -> RootChannel, openOp: Int, target: String) : ISqliteSession {

    private val channel: RootChannel = try {
        connect()
    } catch (e: Exception) {
        throw SessionException(e.message ?: e.toString(), e)
    }

    init {
        try {
            call(openOp) { writeString(target) }
        } catch (e: SessionException) {
            channel.close()
            throw e
        }
    }

    override fun attach(name: String, path: String) {
        require(SQL_IDENTIFIER.matches(name)) { "Illegal schema name: $name" }
        call(Protocol.OP_ATTACH) {
            writeString(name)
            writeString(path)
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> =
        fetch(sql, args, maxRows = 0, mapper)

    override fun <T> queryFirst(sql: String, vararg args: Any?, mapper: (Row) -> T): T? =
        fetch(sql, args, maxRows = 1, mapper).firstOrNull()

    override fun dataVersion(schema: String): Long {
        require(SQL_IDENTIFIER.matches(schema)) { "Illegal schema name: $schema" }
        return queryFirst("PRAGMA $schema.data_version") { (it.value(0) as? Number)?.toLong() } ?: 0L
    }

    override fun columnsOf(schema: String, table: String): Set<String> {
        require(SQL_IDENTIFIER.matches(schema) && SQL_IDENTIFIER.matches(table)) { "Illegal name: $schema.$table" }
        return query("PRAGMA $schema.table_info($table)") { it.value("name").toString().lowercase() }.toSet()
    }

    /** Closing the socket is enough: the helper closes the database when it sees the connection end. */
    override fun close() =
        channel.close()

    private fun call(op: Int, args: DataOutputStream.() -> Unit) = io {
        channel.send(op, args)
        channel.expectOk()
    }

    /**
     * Reads the whole result before mapping it: a mapper that throws must not leave the rest of the rows unread on the
     * socket. Arguments keep their types (see Protocol.writeValue), as AndroidSqliteSession binds them.
     */
    private fun <T> fetch(sql: String, args: Array<out Any?>, maxRows: Int, mapper: (Row) -> T): List<T> {
        val (columns, rows) = io {
            channel.send(Protocol.OP_QUERY) {
                writeString(sql)
                writeInt(maxRows)
                writeInt(args.size)
                for (arg in args)
                    Protocol.writeValue(this, arg)
            }
            channel.expectOk()
            val input = channel.input
            val columns = Array(input.readInt()) { Protocol.readString(input) }
            val rows = ArrayList<Array<Any?>>()
            while (true) {
                when (val marker = input.readUnsignedByte()) {
                    Protocol.ROW       -> rows += Array(columns.size) { Protocol.readValue(input) }
                    Protocol.END       -> break
                    Protocol.ROW_ERROR -> throw HelperError(Protocol.readString(input))
                    else               -> throw IOException("Unexpected marker from the root helper: $marker")
                }
            }
            columns to rows
        }
        val row = RemoteRow(columns)
        return rows.map { values ->
            row.values = values
            mapper(row)
        }
    }

    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw SessionException(e.message ?: e.toString(), e)
        }

    private class RemoteRow(columns: Array<String>) : Row {

        private val indices: Map<String, Int> =
            columns.withIndex().associate { (i, name) -> name.lowercase() to i }

        var values: Array<Any?> = emptyArray()

        override fun has(column: String): Boolean =
            column.lowercase() in indices

        override fun value(column: String): Any? =
            indices[column.lowercase()]?.let(::value)

        override fun value(index: Int): Any? =
            values[index]
    }

    companion object {
        fun factory(apk: HelperApk) = factory { RootHelper.connect(apk, RootHelper.SESSION_TIMEOUT_MILLIS) }

        fun factory(connect: () -> RootChannel) = object : SessionFactory {

            override fun open(mainPath: String, subPath: String): ISqliteSession =
                RootSqliteSession(connect, Protocol.OP_OPEN, mainPath).also { session ->
                    try {
                        session.attach("db2", subPath)
                    } catch (e: Exception) {
                        session.close()
                        throw e
                    }
                }

            override fun openUserDatabase(dbDir: String): ISqliteSession =
                RootSqliteSession(connect, Protocol.OP_OPEN_USER_DB, dbDir)
        }
    }
}
