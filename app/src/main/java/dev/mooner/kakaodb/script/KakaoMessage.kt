package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.KakaoDBRuntime
import dev.mooner.kakaodb.core.model.ChatData
import dev.mooner.kakaodb.core.model.ChatType
import dev.mooner.kakaodb.core.model.EmoticonInfo
import dev.mooner.kakaodb.core.model.LocationInfo
import dev.mooner.kakaodb.core.model.MediaInfo
import dev.mooner.kakaodb.core.model.Mention
import dev.mooner.kakaodb.core.parser.kakaoJson

/**
 * A KakaoTalk message, read from the database: what `onKakaoMessage(msg)` receives, and what queries return.
 *
 * Its values are as the database had them when it was read; methods marked as reading the database read it again.
 *
 * @see guide:scripting#replies-and-threads
 */
class KakaoMessage internal constructor(
    private val chat: ChatData,
    /** The room the message is in. */
    val room: KakaoRoom,
    /** Who sent the message; a user of kind `unknown` if the database has no profile of them. */
    val sender: KakaoUser,
    selfId: Long,
) {

    /** The message's ID: its log ID on KakaoTalk's server. */
    val id: String = chat.id.toString()

    /** The ID of the room the message is in. */
    val roomId: String = chat.chatId.toString()

    /** The sender's user ID. */
    val senderId: String = chat.userId.toString()

    /**
     * The message's text, decrypted. Empty for messages without text, such as photos; for a feed (type `FEED`), the
     * feed's JSON.
     */
    val text: String = chat.message

    /**
     * The message type: `TEXT`, `PHOTO`, `VIDEO`, `AUDIO`, `FILE`, `REPLY`, `MULTIPHOTO`, `FEED`, emoticon types and
     * more, or `UNKNOWN` for a type this plugin doesn't know (see [typeCode]).
     */
    val type: String = chat.type?.name ?: "UNKNOWN"

    /** KakaoTalk's number for the message type, also for types [type] calls `UNKNOWN`. */
    val typeCode: Int = chat.typeInt

    /** When the message was sent, as Unix time in milliseconds: `new Date(msg.createdAt)`. */
    val createdAt: Long = chat.createdAt * 1000L

    /** Whether the user (the account the bot runs as) sent the message. */
    @get:JvmName("getIsMine")
    val isMine: Boolean = chat.userId == selfId || chat.v.isMine

    /** Whether the message was deleted. */
    @get:JvmName("getIsDeleted")
    val isDeleted: Boolean = chat.deleted

    /** Whether the message is an emoticon. */
    @get:JvmName("getIsEmoticon")
    val isEmoticon: Boolean = chat.type?.isEmoticon == true

    /** The users the message mentions with `@name`, in order. */
    val mentions: List<KakaoMention> = chat.mentions.map(::KakaoMention)

    /** Whether the message mentions anyone. */
    val hasMention: Boolean = mentions.isNotEmpty()

    /** Whether the message mentions the user (the account the bot runs as). */
    val mentionsMe: Boolean = selfId > 0 && chat.mentions.any { it.userId == selfId }

    /**
     * The message this one quotes, for messages of type `REPLY`; `null` otherwise.
     *
     * Messages posted in an open chat thread aren't replies: see [threadId].
     */
    val replyTo: KakaoReplyInfo? = chat.reply?.let { reply ->
        KakaoReplyInfo(
            id = reply.logId.toString(),
            senderId = reply.userId.toString(),
            text = reply.message,
            typeCode = reply.type,
            type = reply.type?.let(ChatType::of)?.name,
        )
    }

    /**
     * For a message posted in an open chat thread, the ID of the message the thread hangs from; `null` outside threads.
     *
     * Thread messages aren't `REPLY` messages, so their [replyTo] is `null` unless they also quote a message.
     */
    val threadId: String? = chat.threadId?.takeIf { chat.isThreadMessage }?.toString()

    /** Whether the message was posted in an open chat thread. The message the thread hangs from isn't. */
    @get:JvmName("getIsThreadMessage")
    val isThreadMessage: Boolean = chat.isThreadMessage

    /**
     * KakaoTalk's scope of the message: `1` in the room, `3` for a thread message also shown in the room. `null` on
     * KakaoTalk versions without threads.
     */
    val scope: Int? = chat.scope

    /** The photos, videos, audio clips and files of the message, in order; empty for other messages. */
    val media: List<MediaInfo> = chat.media

    /** The shared location, for a location message; `null` otherwise. */
    val location: LocationInfo? = chat.location

    /** The emoticon, for an emoticon message; `null` otherwise. */
    val emoticon: EmoticonInfo? = chat.emoticon

    /**
     * The message's attachment as KakaoTalk stored it, decrypted, as JSON text: `JSON.parse(msg.attachmentJson)`.
     * `null` for messages without one.
     */
    val attachmentJson: String? by lazy { chat.rawAttachment?.toString() }

    /** Database queries and sending: the same object as the global `KakaoDB`. */
    val db: KakaoClient
        get() = KakaoDBRuntime.client

    /**
     * Sends a text message to this message's room, as [KakaoRoom.send] does.
     *
     * @param text The message to send.
     * @return Whether the message was queued; `false` if no notification from the room was seen since StarLight started.
     * @see guide:sending
     */
    fun reply(text: String): Boolean =
        room.send(text)

    /**
     * Marks this message's room as read, as [KakaoRoom.markAsRead] does.
     *
     * @return Whether KakaoTalk took the request.
     */
    fun markAsRead(): Boolean =
        room.markAsRead()

    /**
     * The message the thread hangs from, for a message posted in an open chat thread. Reads the database on each call.
     *
     * @return The message, or `null` if this isn't a thread message or the database doesn't have the message.
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getThreadRoot(): KakaoMessage? =
        threadId?.let { KakaoDBRuntime.client.getMessage(roomId, it) }

    /**
     * How many members of the room haven't read this message yet. Reads the database on each call.
     *
     * @return The number of members, or `-1` if the database doesn't tell.
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getUnreadCount(): Int =
        KakaoDBRuntime.client.getUnreadCount(roomId, id)

    /** The whole database row of the message as JSON text, with IDs as strings: `JSON.parse(msg.toJson())`. */
    fun toJson(): String =
        kakaoJson.encodeToString(ChatData.serializer(), chat)

    override fun toString(): String =
        "KakaoMessage(room=${room.name}, sender=${sender.name}, type=$type, text=$text)"
}

/** A mention in a message: the user an `@name` in its text stands for. */
class KakaoMention internal constructor(mention: Mention) {

    /** The mentioned user's ID. */
    val userId: String = mention.userId.toString()

    /** Which `@name` occurrences in the text (counting from 1) mention this user. */
    val positions: List<Int> = mention.positions

    /** The length of the mentioned name, as KakaoTalk records it. */
    val length: Int = mention.length

    override fun toString(): String =
        "KakaoMention(userId=$userId, positions=$positions)"
}

/** The message a `REPLY` message quotes, as the reply recorded it. */
class KakaoReplyInfo internal constructor(
    /** The quoted message's ID: `db.getMessage(msg.roomId, msg.replyTo.id)` reads the whole message. */
    val id: String,
    /** The quoted message's sender's user ID. */
    val senderId: String,
    /** The quoted message's text, as the reply recorded it; `null` if it recorded none. */
    val text: String?,
    /** KakaoTalk's number for the quoted message's type; `null` if the reply recorded none. */
    val typeCode: Int?,
    /** The quoted message's type, as [KakaoMessage.type] names it; `null` if the reply recorded none or it's unknown. */
    val type: String?,
) {
    override fun toString(): String =
        "KakaoReplyInfo(id=$id, senderId=$senderId, text=$text)"
}
