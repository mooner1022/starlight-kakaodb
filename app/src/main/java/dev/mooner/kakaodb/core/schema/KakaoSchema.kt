package dev.mooner.kakaodb.core.schema

import dev.mooner.kakaodb.core.CoreLogger
import dev.mooner.kakaodb.core.sqlite.ISqliteSession

/** Which session a table is read through. */
enum class KakaoDatabase {
    /** KakaoTalk.db, with KakaoTalk2.db attached as `db2`. */
    MAIN,
    /** crypto_user_database (SQLCipher), its own session: the friend list of newer KakaoTalk versions. */
    USER,
}

/**
 * Known schema of the Android KakaoTalk databases.
 *
 * Columns come and go between app versions, so [KakaoSchema.resolve] keeps only the columns the database has and
 * builds the SELECT lists from them. Columns that don't exist read as null from [dev.mooner.kakaodb.core.sqlite.Row].
 */
enum class KakaoTable(
    val schema: String,
    val table: String,
    val knownColumns: List<String>,
    val database: KakaoDatabase = KakaoDatabase.MAIN,
) {
    // KakaoTalk.db
    // thread_id and scope came with open chat threads; older versions don't have them.
    CHAT_LOGS("main", "chat_logs", listOf(
        "_id", "id", "type", "chat_id", "user_id", "message", "attachment", "created_at", "deleted_at",
        "client_message_id", "prev_id", "referer", "supplement", "v", "thread_id", "scope",
    )),
    CHAT_ROOMS("main", "chat_rooms", listOf(
        "_id", "id", "type", "members", "active_member_ids", "last_log_id", "last_message", "last_updated_at",
        "unread_count", "watermarks", "temporary_message", "v", "ext", "last_read_log_id", "last_update_seen_id",
        "active_members_count", "meta", "is_hint", "private_meta", "last_chat_log_type", "schat_token",
        "last_skey_token", "last_pk_tokens", "link_id", "moim_meta", "invite_info", "blinded_member_ids",
        "mute_until_at", "last_joined_log_id",
    )),

    // KakaoTalk2.db (db2)
    FRIENDS("db2", "friends", listOf(
        "_id", "contact_id", "id", "type", "uuid", "phone_number", "raw_phone_number", "name", "phonetic_name",
        "profile_image_url", "full_profile_image_url", "original_profile_image_url", "status_message", "chat_id",
        "brand_new", "blocked", "favorite", "position", "v", "board_v", "ext", "nick_name", "user_type",
        "story_user_id", "accout_id", "linked_services", "hidden", "purged", "suspended", "member_type",
        "involved_chat_ids", "contact_name", "enc", "created_at", "new_badge_updated_at", "new_badge_seen_at",
        "status_action_token", "mute_until_at",
    )),
    OPEN_LINK("db2", "open_link", listOf(
        "id", "user_id", "token", "name", "url", "image_url", "type", "member_limit", "direct_chat_limit",
        "active", "expired", "created_at", "view_type", "push_alert", "icon_url", "v", "searchable", "description",
    )),
    OPEN_PROFILE("db2", "open_profile", listOf(
        "link_id", "user_id", "nickname", "profile_image_url", "f_profile_image_url", "o_profile_image_url",
        "link_member_type", "profile_type", "profile_link_id", "enc", "v",
    )),
    // Newer versions key members by (user_id, link_id), with a unique index, and leave involved_chat_id 0 for most.
    OPEN_CHAT_MEMBER("db2", "open_chat_member", listOf(
        "_id", "link_id", "user_id", "type", "profile_type", "link_member_type", "nickname", "profile_image_url",
        "full_profile_image_url", "original_profile_image_url", "profile_link_id", "involved_chat_id",
        "privilege", "report", "enc", "v",
    )),

    // crypto_user_database, where newer KakaoTalk versions moved db2.friends. Only `id`, `nickname` and
    // `profile_image_url` of `user` are known for sure (Iris reads them); the rest are guesses after db2.friends.
    USER("main", "user", listOf(
        "id", "nickname", "name", "nick_name", "contact_name", "status_message", "profile_image_url",
        "full_profile_image_url", "original_profile_image_url", "board_v", "user_type", "uuid", "phone_number",
        "account_id", "accout_id", "favorite", "hidden", "blocked", "purged", "suspended", "created_at", "enc",
    ), KakaoDatabase.USER),
    /** Kakao channels (plus friends) the user talks to, which have no `user` row. */
    TALK_CHANNEL("main", "talk_channel", listOf(
        "id", "name", "profile_image_url",
    ), KakaoDatabase.USER);

    /** What SQL names the table by, on the session of its [database]. */
    val qualifiedName: String
        get() = "$schema.$table"

    /** What logs and warnings call it. */
    val label: String
        get() = when (database) {
            KakaoDatabase.MAIN -> qualifiedName
            KakaoDatabase.USER -> "crypto_user_database.$table"
        }
}

class ResolvedTable(
    val spec: KakaoTable,
    val columns: Set<String>,
) {
    val available: Boolean
        get() = selectList.isNotEmpty()

    /** The known columns the table has, comma-separated, for SELECT. */
    val selectList: String =
        spec.knownColumns.filter { it in columns }.joinToString(", ")

    val from: String
        get() = spec.qualifiedName

    fun has(column: String): Boolean =
        column in columns

    fun select(where: String = "", suffix: String = ""): String = buildString {
        append("SELECT ").append(selectList).append(" FROM ").append(from)
        if (where.isNotEmpty())
            append(" WHERE ").append(where)
        if (suffix.isNotEmpty())
            append(' ').append(suffix)
    }
}

class KakaoSchema private constructor(
    private val tables: Map<KakaoTable, ResolvedTable>,
) {
    operator fun get(table: KakaoTable): ResolvedTable =
        tables.getValue(table)

    /** Tables the database doesn't have; the features that need them are unavailable. */
    val missingTables: List<KakaoTable>
        get() = tables.values.filterNot(ResolvedTable::available).map(ResolvedTable::spec)

    companion object {
        /** Resolves the tables of [database], which [session] reads. */
        fun resolve(
            session: ISqliteSession,
            logger: CoreLogger = CoreLogger.None,
            database: KakaoDatabase = KakaoDatabase.MAIN,
        ): KakaoSchema {
            val tables = KakaoTable.entries.filter { it.database == database }.associateWith { spec ->
                val columns = runCatching { session.columnsOf(spec.schema, spec.table) }
                    .onFailure { logger.warn(it) { "Failed to read schema of ${spec.label}" } }
                    .getOrDefault(emptySet())
                ResolvedTable(spec, columns).also { resolved ->
                    val missing = spec.knownColumns.filterNot(resolved::has)
                    when {
                        !resolved.available ->
                            logger.warn { "Table ${spec.label} is not available" }
                        missing.isNotEmpty() ->
                            logger.debug { "Table ${spec.label}: missing known columns $missing" }
                    }
                }
            }
            return KakaoSchema(tables)
        }
    }
}
