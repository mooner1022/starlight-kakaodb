package dev.mooner.kakaodb.script

import dev.mooner.kakaodb.core.KakaoEvent
import dev.mooner.kakaodb.core.model.ChatData
import dev.mooner.kakaodb.core.model.RoomData
import dev.mooner.kakaodb.core.model.UnknownUserData
import dev.mooner.kakaodb.core.model.UserData

/** Turns the core's data into the objects scripts get. */
internal class ScriptMapper(val selfId: Long) {

    fun room(room: RoomData): KakaoRoom =
        KakaoRoom(room)

    fun user(user: UserData, displayName: String? = null): KakaoUser =
        KakaoUser(user, displayName, selfId)

    fun sender(chat: ChatData): KakaoUser =
        user(chat.user ?: UnknownUserData(chat.userId))

    fun message(chat: ChatData, room: KakaoRoom): KakaoMessage =
        KakaoMessage(chat, room, sender(chat), selfId)

    fun member(member: KakaoEvent.Member): KakaoUser =
        user(member.user, member.nickName)

    /** The script object for [event]; null for events scripts don't get (LogCreated). */
    fun event(event: KakaoEvent): Any? {
        val room = room(event.room)
        val chat = event.chat
        val logId = chat.id.toString()
        val actor = chat.user?.let(::user)
        val createdAt = chat.createdAt * 1000L
        return when (event) {
            is KakaoEvent.LogCreated        -> null
            is KakaoEvent.MessageCreated    -> message(chat, room)
            is KakaoEvent.Feed              -> KakaoFeedEvent(
                room, logId, actor, createdAt,
                feedType = event.feed.feedType?.name ?: "UNKNOWN",
                feedTypeCode = event.feed.feedTypeInt,
                members = event.feed.allMembers.map { KakaoFeedMember(it.userId.toString(), it.nickName) },
                feedJson = event.feed.raw?.toString() ?: chat.message,
            )
            is KakaoEvent.MessageDeleted    -> KakaoMessageDeletedEvent(
                room, logId, actor, createdAt,
                messageId = event.logId.toString(),
                message = event.target?.let { message(it, room) },
            )
            is KakaoEvent.MessageHidden     -> KakaoMessageHiddenEvent(
                room, logId, actor, createdAt,
                messageIds = event.logIds.map(Long::toString),
                messages = event.targets.map { message(it, room) },
            )
            is KakaoEvent.MembersJoined     -> KakaoMemberJoinedEvent(
                room, logId, actor, createdAt,
                members = event.members.map(::member),
                inviter = event.inviter?.let(::member),
                isOpenLinkJoin = event.isOpenLinkJoin,
            )
            is KakaoEvent.MemberLeft        -> KakaoMemberLeftEvent(
                room, logId, actor, createdAt,
                member = member(event.member),
                isKicked = event.kicked,
            )
            is KakaoEvent.MemberRoleChanged -> KakaoMemberRoleChangedEvent(
                room, logId, actor, createdAt,
                change = event.change.name,
                members = event.members.map(::member),
                previousHost = event.previousHost?.let(::member),
            )
        }
    }
}
