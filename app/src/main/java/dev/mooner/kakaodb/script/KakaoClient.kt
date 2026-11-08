package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.KakaoDBRuntime
import dev.mooner.kakaodb.core.DatabaseDecoder
import dev.mooner.kakaodb.core.DecoderHealth
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Reads KakaoTalk's database and sends messages: the global `KakaoDB`, and `db` on every KakaoDB event object.
 *
 * Both are the same object. Queries block until the database answers, for up to 15 seconds, so call them from event
 * functions rather than at the top level of a script. They throw while the plugin isn't connected to the database;
 * check [isConnected] first where that can happen. Sending doesn't need the database, only a notification from the room.
 *
 * Every ID is a string, as KakaoTalk's IDs go beyond 2^53: pass them as strings, and compare them as strings.
 *
 * @see guide:scripting
 * @see guide:sending
 */
class KakaoClient internal constructor() {

    /**
     * Whether the plugin is connected to the database and it answers.
     *
     * False before the first connection, after the user disconnects, and while a lost connection is being reopened.
     *
     * @see guide:setup
     */
    @get:JvmName("getIsConnected")
    val isConnected: Boolean
        get() = KakaoDBRuntime.decoder?.let { it.isOpen && it.health.value == DecoderHealth.Ok } == true

    /**
     * The user whose KakaoTalk this is: the account the bot runs as.
     *
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getSelf(): KakaoUser =
        query { mapper.user(getSelf()) }

    /**
     * A chat room, as it is now.
     *
     * @param roomId The room's ID, such as `msg.roomId` or `room.id`.
     * @return The room, or `null` if the database has no room with that ID.
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException [roomId] isn't a number.
     */
    fun getRoom(roomId: String): KakaoRoom? =
        query { getRoom(roomId.toId())?.let(mapper::room) }

    /**
     * Every chat room the user is in, most recently active first.
     *
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getRooms(): List<KakaoRoom> =
        query { getRooms().map(mapper::room) }

    /**
     * The user's KakaoTalk friends.
     *
     * Empty in direct mode when this KakaoTalk keeps its friends in `crypto_user_database`, which only root and shared
     * UID modes read; the plugin status shows a warning then, and friends show as users of kind `unknown`.
     *
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getFriends(): List<KakaoUser> =
        query { getFriends().map { mapper.user(it) } }

    /**
     * A user by ID; with the ID of an open chat room, that room's open profile of the user.
     *
     * @param userId The user's ID, such as `msg.senderId`.
     * @param roomId An open chat room's ID, to get the nickname and profile the user has there. Leave it out, or pass
     *   `null`, for the friend profile.
     * @return The user, or `null` if the database knows no user with that ID.
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException An ID isn't a number.
     */
    @JvmOverloads
    fun getUser(userId: String, roomId: String? = null): KakaoUser? =
        query { getUser(userId.toId(), roomId?.toId())?.let { mapper.user(it) } }

    /**
     * The members of a room, the user first.
     *
     * In an open chat room, members have the room's open profiles. Members the database knows nothing about come as
     * users of kind `unknown`.
     *
     * @param roomId The room's ID.
     * @return The members; empty if the database has no room with that ID.
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException [roomId] isn't a number.
     */
    fun getMembers(roomId: String): List<KakaoUser> =
        query { getMembers(roomId.toId()).map { mapper.user(it) } }

    /**
     * The open chat links the database has: those of the open chat rooms the user is in.
     *
     * @throws IllegalStateException The plugin isn't connected to the database.
     */
    fun getOpenLinks(): List<KakaoOpenLink> =
        query { getOpenLinks().map(::KakaoOpenLink) }

    /**
     * Messages of a room, oldest first: the latest ones, or those before a given message.
     *
     * Feeds (system messages such as joins) are included, as messages of type `FEED` whose `text` is the feed's JSON.
     * Page back by passing the `id` of the first message of the previous result as [beforeMessageId].
     *
     * @param roomId The room's ID.
     * @param limit How many messages to return at most, from 1 to 1000. Defaults to 50.
     * @param beforeMessageId The `id` of a message: only messages before it are returned. Leave it out, or pass `null`,
     *   for the latest messages.
     * @return The messages; empty if the database has no room with that ID, or no message [beforeMessageId] in it.
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException An ID isn't a number.
     */
    @JvmOverloads
    fun getMessages(roomId: String, limit: Int = 50, beforeMessageId: String? = null): List<KakaoMessage> =
        query {
            val id = roomId.toId()
            val beforePk = beforeMessageId?.let { getChat(id, it.toId())?.pk ?: return@query emptyList() }
            val room = getRoom(id)?.let(mapper::room) ?: return@query emptyList()
            getChats(id, beforePk, limit).map { mapper.message(it, room) }
        }

    /**
     * One message of a room.
     *
     * @param roomId The room's ID.
     * @param messageId The message's `id`.
     * @return The message, or `null` if the database doesn't have it in that room.
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException An ID isn't a number.
     */
    fun getMessage(roomId: String, messageId: String): KakaoMessage? =
        query {
            val id = roomId.toId()
            val chat = getChat(id, messageId.toId()) ?: return@query null
            val room = getRoom(id)?.let(mapper::room) ?: return@query null
            mapper.message(chat, room)
        }

    /**
     * How many members of a room haven't read a message yet, as KakaoTalk shows next to it.
     *
     * Read from the database on each call, so it goes down as members read the message.
     *
     * @param roomId The room's ID.
     * @param messageId The message's `id`.
     * @return The number of members, or `-1` if the database doesn't tell (unknown room, or no read state).
     * @throws IllegalStateException The plugin isn't connected to the database.
     * @throws IllegalArgumentException An ID isn't a number.
     */
    fun getUnreadCount(roomId: String, messageId: String): Int =
        query { getReadState(roomId.toId())?.unreadCount(messageId.toId()) ?: -1 }

    /**
     * Queues a text message for a room, sent through the reply action of the room's KakaoTalk notification.
     *
     * Messages are sent one at a time, in order, with the plugin's send interval between them. `true` means the message
     * was queued, not that it was delivered: if KakaoTalk has dropped the notification's reply action by then (it
     * restarted, for one), the message is dropped and the plugin logs it.
     *
     * @param roomId The room's ID.
     * @param text The message.
     * @return Whether the message was queued: `false` if no notification from the room was seen since StarLight
     *   started (see [canSend]), if 256 messages are already waiting, or if the plugin is off.
     * @throws IllegalArgumentException [roomId] isn't a number.
     * @see guide:sending
     */
    fun send(roomId: String, text: String): Boolean =
        KakaoDBRuntime.send(roomId.toId(), text)

    /**
     * Marks a room's messages as read, through the "mark as read" action of the room's KakaoTalk notification.
     *
     * @param roomId The room's ID.
     * @return Whether KakaoTalk took the request: `false` if no notification from the room was seen, or its action
     *   expired.
     * @throws IllegalArgumentException [roomId] isn't a number.
     */
    fun markAsRead(roomId: String): Boolean =
        KakaoDBRuntime.markAsRead(roomId.toId())

    /**
     * Whether messages can be sent to a room now: a notification from it was seen since StarLight started.
     *
     * @param roomId The room's ID.
     * @throws IllegalArgumentException [roomId] isn't a number.
     * @see guide:sending
     */
    fun canSend(roomId: String): Boolean =
        KakaoDBRuntime.canSend(roomId.toId())

    /**
     * The IDs of the rooms messages can be sent to now, the room of the latest notification first.
     *
     * @see guide:sending
     */
    fun getSendableRoomIds(): List<String> =
        KakaoDBRuntime.replies.sendableRooms().map { it.first }

    private val mapper: ScriptMapper
        get() = ScriptMapper(KakaoDBRuntime.selfId)

    private fun <T> query(block: suspend DatabaseDecoder.() -> T): T {
        val decoder = KakaoDBRuntime.decoder?.takeIf { it.isOpen }
            ?: throw IllegalStateException(tr("KakaoDB isn't connected to the database", "KakaoDB가 데이터베이스에 연결되어 있지 않아요"))
        return runBlocking { withTimeout(QUERY_TIMEOUT_MILLIS) { decoder.block() } }
    }

    private fun String.toId(): Long =
        trim().toLongOrNull() ?: throw IllegalArgumentException(tr("Not an id: '$this' (pass ids as strings)", "올바른 ID가 아니에요: '$this' (ID는 문자열로 넘겨 주세요)"))

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 15_000L
    }
}
