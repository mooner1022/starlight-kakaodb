package dev.mooner.kakaodb.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.JsonObject

/** chat_logs.message of FEED rows: `feedType` in the JSON. */
enum class FeedType(val typeInt: Int) {
    LOCAL_LEAVE             (-1),
    INVITE                  (1),
    LEAVE                   (2),
    SECRET_LEAVE            (3),
    OPENLINK_JOIN           (4),
    OPENLINK_DELETE_LINK    (5),
    OPENLINK_KICKED         (6),
    CHANNEL_KICKED          (7),
    CHANNEL_DELETED         (8),
    RICH_CONTENT            (10),
    OPEN_MANAGER_GRANT      (11),
    OPEN_MANAGER_REVOKE     (12),
    /** An open chat host or manager hid messages ("가리기"). */
    OPENLINK_REWRITE_FEED   (13),
    DELETE_TO_ALL           (14),
    OPENLINK_HAND_OVER_HOST (15),
    TEAM_CHANNEL_EVENT      (18);

    companion object {
        private val byTypeInt: Map<Int, FeedType> = entries.associateBy(FeedType::typeInt)

        fun of(typeInt: Int): FeedType? = byTypeInt[typeInt]
    }
}

/**
 * The message of a FEED row: joins, leaves, kicks, manager changes, deletions and so on.
 * Which fields are set depends on the feed type; unknown fields are kept in [raw].
 */
@Serializable
data class FeedData(
    @SerialName("feedType")
    val feedTypeInt: Int = Int.MIN_VALUE,
    /** Members the feed is about (invited, joined, manager granted...). */
    val members: List<FeedMember> = emptyList(),
    /** The one member the feed is about (left, kicked...). */
    val member: FeedMember? = null,
    /** Who invited [members]. */
    val inviter: FeedMember? = null,
    /** Open chat host handover. */
    val prevHost: FeedMember? = null,
    val newHost: FeedMember? = null,
    /** Target message of DELETE_TO_ALL / OPENLINK_REWRITE_FEED. */
    @Serializable(with = LongAsStringSerializer::class)
    val logId: Long? = null,
    /** Target messages, for feeds about several messages. */
    val logIds: List<@Serializable(with = LongAsStringSerializer::class) Long> = emptyList(),
    val hidden: Boolean = false,
    val kicked: Boolean = false,
    @Transient
    val raw: JsonObject? = null,
) {
    val feedType: FeedType?
        get() = FeedType.of(feedTypeInt)

    /** Every member the feed mentions. */
    val allMembers: List<FeedMember>
        get() = buildList {
            addAll(members)
            member?.let(::add)
            inviter?.let(::add)
            prevHost?.let(::add)
            newHost?.let(::add)
        }.distinctBy(FeedMember::userId)

    /** [logIds] and [logId] together. */
    val targetLogIds: List<Long>
        get() = (logIds + listOfNotNull(logId)).distinct()

    @Serializable
    data class FeedMember(
        @Serializable(with = LongAsStringSerializer::class)
        val userId: Long,
        val nickName: String? = null,
    )
}
