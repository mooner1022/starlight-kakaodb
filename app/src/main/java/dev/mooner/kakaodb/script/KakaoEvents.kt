package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.KakaoDBRuntime

/**
 * What every KakaoDB event has: the room, and the log (a message, or a feed) the event was read from.
 *
 * A new message is one log. KakaoTalk records joins, leaves, deletions and the like as feeds (system messages) among
 * the room's messages; each of the other events is one such feed.
 *
 * @see guide:events
 */
abstract class KakaoRoomEvent internal constructor(
    /** The room the event happened in: `event.room.send(text)` sends to it. */
    val room: KakaoRoom,
    /** The ID of the log the event was read from: the new message's ID, or the feed's. */
    val logId: String,
    /**
     * The user the log was recorded for: for a feed usually who caused the event (who deleted, invited, kicked...),
     * `null` if the database has no such user; for a new message its sender, never `null`.
     */
    val actor: KakaoUser?,
    /** When the event happened, as Unix time in milliseconds. */
    val createdAt: Long,
) {
    /** Database queries and sending: the same object as the global `KakaoDB`. */
    val db: KakaoClient
        get() = KakaoDBRuntime.client
}

/**
 * A message was stored in KakaoTalk's database: [message] is the message, and [actor] its sender, the same object as
 * `event.message.sender`.
 */
class KakaoMessageEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** The message, with its sender (`event.message.sender`) and text (`event.message.text`). */
    val message: KakaoMessage,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMessageEvent(room=${room.name}, sender=${message.sender.name}, type=${message.type}, text=${message.text})"
}

/** A message was deleted for everyone ("모든 사람에게서 삭제"). */
class KakaoMessageDeletedEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** The deleted message's ID. */
    val messageId: String,
    /** The deleted message, with its text, if the database still had it; `null` otherwise. */
    val message: KakaoMessage?,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMessageDeletedEvent(room=${room.name}, messageId=$messageId, message=${message?.text})"
}

/** An open chat's host or a manager hid messages ("가리기"); [actor] is who hid them. */
class KakaoMessageHiddenEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** The IDs of the hidden messages. */
    val messageIds: List<String>,
    /** The hidden messages the database still had, with their text; may be fewer than [messageIds]. */
    val messages: List<KakaoMessage>,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMessageHiddenEvent(room=${room.name}, messageIds=$messageIds)"
}

/** Members joined a room: invited to a group chat, or through an open chat's link. */
class KakaoMemberJoinedEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** The members who joined, with the names the feed recorded. */
    val members: List<KakaoUser>,
    /** Who invited them; `null` for open chat joins. */
    val inviter: KakaoUser?,
    /** Whether the members joined through an open chat's link rather than by invitation. */
    @get:JvmName("getIsOpenLinkJoin")
    val isOpenLinkJoin: Boolean,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMemberJoinedEvent(room=${room.name}, members=${members.map(KakaoUser::name)})"
}

/** A member left a room or was kicked from it. */
class KakaoMemberLeftEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** The member who left, with the name the feed recorded. */
    val member: KakaoUser,
    /** Whether the member was kicked rather than leaving by themselves. */
    @get:JvmName("getIsKicked")
    val isKicked: Boolean,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMemberLeftEvent(room=${room.name}, member=${member.name}, kicked=$isKicked)"
}

/** An open chat's roles changed: a manager (부방장) was appointed or dismissed, or the host handed over the room. */
class KakaoMemberRoleChangedEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /** What changed: `MANAGER_GRANTED`, `MANAGER_REVOKED` or `HOST_CHANGED`. */
    val change: String,
    /** The members who got or lost the manager role; for `HOST_CHANGED`, the new host. */
    val members: List<KakaoUser>,
    /** The previous host, for `HOST_CHANGED`; `null` otherwise or if the feed didn't record them. */
    val previousHost: KakaoUser?,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoMemberRoleChangedEvent(room=${room.name}, change=$change, members=${members.map(KakaoUser::name)})"
}

/**
 * Any feed (a system message in a room), those of the other KakaoDB events included, as KakaoTalk recorded it.
 *
 * For feeds without an event of their own, or details the other events don't give: read [feedType] and [feedJson].
 */
class KakaoFeedEvent internal constructor(
    room: KakaoRoom,
    logId: String,
    actor: KakaoUser?,
    createdAt: Long,
    /**
     * The feed's type: `INVITE`, `LEAVE`, `SECRET_LEAVE`, `OPENLINK_JOIN`, `OPENLINK_KICKED`, `OPEN_MANAGER_GRANT`,
     * `OPEN_MANAGER_REVOKE`, `OPENLINK_REWRITE_FEED` (messages hidden), `DELETE_TO_ALL`, `OPENLINK_HAND_OVER_HOST` and
     * others; `UNKNOWN` for a type this plugin doesn't know (see [feedTypeCode]).
     */
    val feedType: String,
    /** KakaoTalk's number for the feed's type, also for types [feedType] calls `UNKNOWN`. */
    val feedTypeCode: Int,
    /** The members the feed names, as it recorded them. */
    val members: List<KakaoFeedMember>,
    /** The feed as KakaoTalk stored it, as JSON text: `JSON.parse(event.feedJson)`. */
    val feedJson: String,
) : KakaoRoomEvent(room, logId, actor, createdAt) {
    override fun toString(): String =
        "KakaoFeedEvent(room=${room.name}, feedType=$feedType)"
}
