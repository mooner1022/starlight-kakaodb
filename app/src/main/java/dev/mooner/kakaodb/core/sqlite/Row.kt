package dev.mooner.kakaodb.core.sqlite

/**
 * Read-only view of the row under the cursor.
 *
 * Columns are looked up by name. A column missing from the result (because the table of this KakaoTalk version
 * doesn't have it, so it wasn't selected) reads as null instead of throwing.
 */
interface Row {

    fun has(column: String): Boolean

    fun value(column: String): Any?

    fun value(index: Int): Any?
}

fun Row.string(column: String): String? =
    when (val v = value(column)) {
        null         -> null
        is String    -> v
        is ByteArray -> String(v, Charsets.UTF_8)
        else         -> v.toString()
    }

fun Row.long(column: String): Long? =
    value(column).toLongValue()

fun Row.long(index: Int): Long? =
    value(index).toLongValue()

fun Row.int(column: String): Int? =
    long(column)?.toInt()

fun Row.bool(column: String): Boolean? =
    when (val v = value(column)) {
        null       -> null
        is Number  -> v.toLong() != 0L
        is String  -> v == "1" || v.equals("true", ignoreCase = true)
        else       -> null
    }

private fun Any?.toLongValue(): Long? =
    when (this) {
        null      -> null
        is Number -> toLong()
        is String -> toLongOrNull()
        else      -> null
    }
