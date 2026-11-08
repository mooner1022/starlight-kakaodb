package dev.mooner.kakaodb

import dev.mooner.kakaodb.core.DecoderOptions
import dev.mooner.kakaodb.core.sqlite.ISqliteSession
import dev.mooner.kakaodb.core.sqlite.Row
import dev.mooner.kakaodb.core.sqlite.SQL_IDENTIFIER
import dev.mooner.kakaodb.core.sqlite.SessionException
import dev.mooner.kakaodb.core.sqlite.SessionFactory
import dev.mooner.kakaodb.core.sqlite.requiringFiles
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/** The device's Android SQLite session, played by JDBC on the JVM (as KakaoDBRouter's session did). */
class JdbcSqliteSession(path: String) : ISqliteSession {

    private val connection: Connection = wrap {
        SQLiteConfig().apply { setReadOnly(true); busyTimeout = 5_000 }.createConnection("jdbc:sqlite:$path")
    }

    private val statements = hashMapOf<String, PreparedStatement>()

    override fun attach(name: String, path: String) = wrap {
        require(SQL_IDENTIFIER.matches(name)) { "Illegal schema name: $name" }
        connection.prepareStatement("ATTACH DATABASE ? AS $name").use { it.setString(1, path); it.execute() }
        Unit
    }

    override fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = wrap {
        prepare(sql, args).executeQuery().use { rs ->
            val row = ResultSetRow(rs)
            buildList { while (rs.next()) add(mapper(row)) }
        }
    }

    override fun <T> queryFirst(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = wrap {
        prepare(sql, args).executeQuery().use { rs -> if (rs.next()) mapper(ResultSetRow(rs)) else null }
    }

    override fun dataVersion(schema: String): Long =
        queryFirst("PRAGMA $schema.data_version") { it.long(0) } ?: 0L

    override fun columnsOf(schema: String, table: String): Set<String> =
        query("PRAGMA $schema.table_info($table)") { it.value("name").toString().lowercase() }.toSet()

    override fun close() {
        statements.values.forEach { runCatching { it.close() } }
        connection.close()
    }

    private fun Row.long(index: Int): Long? = (value(index) as? Number)?.toLong()

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val statement = statements.getOrPut(sql) { connection.prepareStatement(sql) }
        statement.clearParameters()
        for ((i, arg) in args.withIndex())
            statement.setObject(i + 1, arg)
        return statement
    }

    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: SQLException) {
            throw SessionException(e.message ?: e.toString(), e)
        }

    private class ResultSetRow(private val rs: ResultSet) : Row {

        private val indices: Map<String, Int> =
            rs.metaData.let { meta -> (1..meta.columnCount).associateBy { meta.getColumnLabel(it).lowercase() } }

        override fun has(column: String): Boolean = column.lowercase() in indices

        override fun value(column: String): Any? = indices[column.lowercase()]?.let(rs::getObject)

        override fun value(index: Int): Any? = rs.getObject(index + 1)
    }

    companion object {
        val factory = SessionFactory { main, sub -> JdbcSqliteSession(main).also { it.attach("db2", sub) } }
            .requiringFiles()

        /**
         * [factory], reading crypto_user_database too. The real one is SQLCipher-encrypted, which only the root helper
         * reads; here it is a plain SQLite file, to test what the decoder does with it.
         */
        val factoryWithUserDb = object : SessionFactory {

            override fun open(mainPath: String, subPath: String): ISqliteSession =
                factory.open(mainPath, subPath)

            override fun openUserDatabase(dbDir: String): ISqliteSession {
                val file = File(dbDir, DecoderOptions.USER_DB)
                if (!file.exists())
                    throw SessionException("$file doesn't exist")
                return JdbcSqliteSession(file.path)
            }
        }
    }
}
