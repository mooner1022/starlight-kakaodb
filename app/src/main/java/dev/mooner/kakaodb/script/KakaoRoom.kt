package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.KakaoDBRuntime
import dev.mooner.kakaodb.core.model.OpenLinkData
import dev.mooner.kakaodb.core.model.RoomData
import dev.mooner.kakaodb.core.parser.kakaoJson
import dev.mooner.starlight.plugincore.chat.ChatRoom

/**
 * A KakaoTalk chat room, as the database had it when it was read.
 *
 * It is also a StarLight `ChatRoom`, so it can be passed where one is expected and sends the same way. Its values
 * don't change after it was read: `KakaoDB.getRoom(room.id)` reads the room again.
 *
 * @see guide:sending
 */
class KakaoRoom internal constructor(private val data: RoomData) : ChatRoom {

    private val roomId: Long = data.id

    /** The room's ID. */
    override val id: String = data.id.toString()

    /**
     * The room's name: the open chat's name, the name the user or the room's members gave it, or the members' names.
     * `Unknown(<id>)` if the database has none of these.
     */
    override val name: String = data.name

    /**
     * Whether the room is a group chat: a group, secret group or open group chat.
     *
     * Scripts read it as `room.groupChat`, or call `room.isGroupChat()`; `room.isGroupChat` without the call is the
     * method itself, which is always truthy.
     */
    override val isGroupChat: Boolean = data.isGroupChat

    /** Always `false`: KakaoDB's rooms are never StarLight's debug room. Read it as `room.debugRoom`, or call `room.isDebugRoom()`. */
    override val isDebugRoom: Boolean = false

    /**
     * KakaoTalk's room type: `DirectChat` (1:1), `MultiChat` (group), `SDirectChat` and `SMultiChat` (secret), `OD` (open
     * 1:1), `OM` (open group), `MemoChat` (chat with oneself), `PlusChat` (channel), or another of KakaoTalk's types.
     */
    val type: String = data.type.name

    /** Whether the room is an open chat, 1:1 or group. */
    @get:JvmName("getIsOpenChat")
    val isOpenChat: Boolean = data.isOpenChat

    /** Whether the room is a 1:1 chat: a normal, secret or open 1:1 chat. */
    @get:JvmName("getIsDirectChat")
    val isDirectChat: Boolean = data.isDirectChat

    /** Whether the room is a secret chat. */
    @get:JvmName("getIsSecretChat")
    val isSecretChat: Boolean = data.isSecretChat

    /** Whether the room is the user's chat with themselves (나와의 채팅). */
    @get:JvmName("getIsMemoChat")
    val isMemoChat: Boolean = data.isMemoChat

    /** The name the user gave the room, or `null` if they didn't. */
    val customName: String? = data.customName

    /** The room's notice, or `null` if it has none. */
    val notice: String? = data.notice

    /** How many members the room has. */
    val memberCount: Int = data.activeMembersCount ?: data.activeMemberIds.size

    /** How many messages of the room the user hadn't read when the room was read. */
    val unreadMessageCount: Int = data.unreadCount ?: 0

    /** The room's open chat link, for an open chat; `null` otherwise. */
    val openLink: KakaoOpenLink? = data.openLink?.let(::KakaoOpenLink)

    /**
     * Whether messages can be sent to the room now: a notification from it was seen since StarLight started.
     *
     * @see guide:sending
     */
    val canSend: Boolean
        get() = KakaoDBRuntime.canSend(roomId)

    /**
     * Queues a text message for the room, sent through the reply action of the room's KakaoTalk notification.
     *
     * Messages are sent one at a time, in order, with the plugin's send interval between them.
     *
     * @param message The message to send.
     * @return Whether the message was queued, not whether it was delivered: `false` if no notification from the room
     *   was seen since StarLight started (see [canSend]), or 256 messages are already waiting.
     * @see guide:sending
     */
    override fun send(message: String): Boolean =
        KakaoDBRuntime.send(roomId, message)

    /**
     * Marks the room's messages as read, through the "mark as read" action of the room's KakaoTalk notification.
     *
     * @return Whether KakaoTalk took the request: `false` if no notification from the room was seen, or its action
     *   expired.
     */
    override fun markAsRead(): Boolean =
        KakaoDBRuntime.markAsRead(roomId)

    /**
     * The room's members, the user first. Reads the database.
     *
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getMembers(): List<KakaoUser> =
        KakaoDBRuntime.client.getMembers(id)

    /**
     * The room's latest messages, oldest first. Reads the database.
     *
     * @param limit How many messages to return at most, from 1 to 1000. Defaults to 50.
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    @JvmOverloads
    fun getMessages(limit: Int = 50): List<KakaoMessage> =
        KakaoDBRuntime.client.getMessages(id, limit)

    /**
     * One message of the room. Reads the database.
     *
     * @param messageId The message's `id`.
     * @return The message, or `null` if the database doesn't have it in this room.
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getMessage(messageId: String): KakaoMessage? =
        KakaoDBRuntime.client.getMessage(id, messageId)

    /** The room as the plugin read it from the database, as JSON text, with IDs as strings. */
    fun toJson(): String =
        kakaoJson.encodeToString(RoomData.serializer(), data)

    override fun toString(): String =
        "KakaoRoom(name=$name, id=$id, type=$type)"
}

/** An open chat link: the open chat a room belongs to. Values the database doesn't have are `null`. */
class KakaoOpenLink internal constructor(data: OpenLinkData) {

    /** The link's ID. */
    val id: String = data.id.toString()

    /** The open chat's name. */
    val name: String? = data.name

    /** The link to the open chat, `https://open.kakao.com/...`. */
    val url: String? = data.url

    /** The URL of the open chat's cover image. */
    val imageUrl: String? = data.imageURL

    /** The open chat's description. */
    val description: String? = data.description

    /** The host's user ID. */
    val hostId: String? = data.hostId?.toString()

    /** How many members the open chat takes at most. */
    val memberLimit: Int? = data.memberLimit

    /** `1` for a group open chat, `2` for a 1:1 open chat. */
    val type: Int? = data.type

    override fun toString(): String =
        "KakaoOpenLink(name=$name, id=$id)"
}
