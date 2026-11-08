package dev.mooner.kakaodb.core

import dev.mooner.kakaodb.core.model.ChatData
import dev.mooner.kakaodb.core.model.FeedData
import dev.mooner.kakaodb.core.model.RoomData
import dev.mooner.kakaodb.core.model.UserData

/**
 * What the decoder found in new chat_logs rows, in row order.
 *
 * Each row yields [LogCreated] first, then [MessageCreated] for a message, or [Feed] followed by the specific feed
 * event (if the feed type has one) for a feed.
 */
sealed class KakaoEvent {

    /** The row the event comes from. For feed events, this is the feed row; its user is who caused the feed. */
    abstract val chat: ChatData

    abstract val room: RoomData

    /** Every new row, feeds included. */
    data class LogCreated(override val chat: ChatData, override val room: RoomData) : KakaoEvent()

    /** A message: any row that isn't a feed. */
    data class MessageCreated(override val chat: ChatData, override val room: RoomData) : KakaoEvent()

    /** Every feed row, whatever its type. */
    data class Feed(override val chat: ChatData, override val room: RoomData, val feed: FeedData) : KakaoEvent()

    /** A message was deleted for everyone (DELETE_TO_ALL). [target] is null if it isn't in the database. */
    data class MessageDeleted(
        override val chat: ChatData,
        override val room: RoomData,
        val logId: Long,
        val target: ChatData?,
    ) : KakaoEvent()

    /** An open chat host or manager hid messages (OPENLINK_REWRITE_FEED). */
    data class MessageHidden(
        override val chat: ChatData,
        override val room: RoomData,
        val logIds: List<Long>,
        val targets: List<ChatData>,
    ) : KakaoEvent()

    /** Members joined (INVITE, OPENLINK_JOIN). [inviter] is set for invitations. */
    data class MembersJoined(
        override val chat: ChatData,
        override val room: RoomData,
        val members: List<Member>,
        val inviter: Member?,
        val isOpenLinkJoin: Boolean,
    ) : KakaoEvent()

    /** A member left or was kicked (LEAVE, SECRET_LEAVE, LOCAL_LEAVE, OPENLINK_KICKED, CHANNEL_KICKED). */
    data class MemberLeft(
        override val chat: ChatData,
        override val room: RoomData,
        val member: Member,
        val kicked: Boolean,
    ) : KakaoEvent()

    /** Open chat roles changed (OPEN_MANAGER_GRANT/REVOKE, OPENLINK_HAND_OVER_HOST). */
    data class MemberRoleChanged(
        override val chat: ChatData,
        override val room: RoomData,
        val change: RoleChange,
        val members: List<Member>,
        /** The previous host, for [RoleChange.HOST_CHANGED]. */
        val previousHost: Member?,
    ) : KakaoEvent()

    /** A member named by a feed, resolved to a user where the database knows them. */
    data class Member(
        val userId: Long,
        /** The nickname the feed recorded, which outlives the member's profile. */
        val nickName: String?,
        val user: UserData,
    ) {
        val name: String
            get() = nickName ?: user.name
    }

    enum class RoleChange {
        MANAGER_GRANTED,
        MANAGER_REVOKED,
        HOST_CHANGED,
    }
}
