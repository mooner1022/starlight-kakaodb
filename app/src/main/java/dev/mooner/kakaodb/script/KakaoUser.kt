package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.core.model.NormalUserData
import dev.mooner.kakaodb.core.model.OpenChatMemberData
import dev.mooner.kakaodb.core.model.OpenMemberType
import dev.mooner.kakaodb.core.model.SelfUserData
import dev.mooner.kakaodb.core.model.UnknownUserData
import dev.mooner.kakaodb.core.model.UserData
import dev.mooner.kakaodb.core.parser.kakaoJson

/*
 * Objects handed to scripts.
 *
 * IDs are strings: KakaoTalk ids go past 2^53, which a JS number can't hold.
 * Boolean properties have get-prefixed getters (getIsMine), so both Rhino and NeonJS read `msg.isMine` as a value
 * instead of a method.
 */

/**
 * A KakaoTalk user: the user themselves, a friend, an open chat member, or someone the database knows only by ID.
 *
 * In an open chat room, a member's name and profile are their open profile in that room.
 */
class KakaoUser internal constructor(
    private val data: UserData,
    // A name to show instead of the profile's (the nickname a feed recorded).
    displayName: String? = null,
    selfId: Long,
) {

    /** The user's ID. */
    val id: String = data.id.toString()

    /**
     * The user's name: their nickname in the open chat, the name the user sees for a friend, or the name a feed
     * recorded. `Unknown(<id>)` for users of kind `unknown` that no feed named.
     */
    val name: String = displayName ?: data.name

    /** What the database knows the user as: `self`, `friend`, `open_member` or `unknown`. */
    val kind: String = when (data) {
        is SelfUserData       -> "self"
        is NormalUserData     -> "friend"
        is OpenChatMemberData -> "open_member"
        is UnknownUserData    -> "unknown"
    }

    /** The URL of the user's profile image, or `null` if the database has none. */
    val profileImageUrl: String? = data.profileImageURL

    /** The URL of the user's full-size profile image, for friends and open chat members; `null` otherwise. */
    val fullProfileImageUrl: String? = when (data) {
        is NormalUserData     -> data.fullProfileImageURL
        is OpenChatMemberData -> data.fullProfileImageURL
        else                  -> null
    }

    /** The friend's status message; `null` for users who aren't friends. */
    val statusMessage: String? = (data as? NormalUserData)?.statusMessage

    /** The member's role in the open chat: `HOST`, `MANAGER`, `NORMAL`, `BOT` or `UNKNOWN`; `null` outside open chats. */
    val memberType: String? = (data as? OpenChatMemberData)?.memberType?.name

    /** Whether this is the user themselves: the account the bot runs as. */
    @get:JvmName("getIsMe")
    val isMe: Boolean = data is SelfUserData || data.id == selfId

    /** Whether the user is a KakaoTalk friend. */
    @get:JvmName("getIsFriend")
    val isFriend: Boolean = data is NormalUserData

    /** Whether the user is the open chat's host. */
    @get:JvmName("getIsHost")
    val isHost: Boolean = (data as? OpenChatMemberData)?.memberType == OpenMemberType.HOST

    /** Whether the user is a manager (부방장) of the open chat. */
    @get:JvmName("getIsManager")
    val isManager: Boolean = (data as? OpenChatMemberData)?.memberType == OpenMemberType.MANAGER

    /** The user as the plugin read them from the database, as JSON text, with IDs as strings. */
    fun toJson(): String =
        kakaoJson.encodeToString(UserData.serializer(), data)

    override fun toString(): String =
        "KakaoUser(name=$name, id=$id, kind=$kind)"
}

/** A member a feed names, as the feed recorded them. */
class KakaoFeedMember internal constructor(
    /** The member's user ID. */
    val id: String,
    /** The member's nickname as the feed recorded it, or `null` if it recorded none. */
    val name: String?,
) {
    override fun toString(): String =
        "KakaoFeedMember(name=$name, id=$id)"
}
