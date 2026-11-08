package dev.mooner.kakaodb.core

import dev.mooner.kakaodb.core.model.NormalUserData
import dev.mooner.kakaodb.core.model.OpenChatMemberData
import dev.mooner.kakaodb.core.model.OpenLinkData
import dev.mooner.kakaodb.core.model.RoomData

/**
 * Cache of friends, open chat members, open links and rooms.
 *
 * Invalidation:
 *  - when `data_version` of db2 (KakaoTalk2.db) changes, [invalidateDb2] drops everything from db2, and the rooms,
 *    whose names depend on db2;
 *  - when the friend list is in crypto_user_database and that changes, [invalidateFriends] drops the friends and the
 *    rooms;
 *  - a FEED message (join, leave, kick...) drops that room and its affected members only, through [invalidateRoom];
 *  - names the user gives rooms (private_meta) change without any signal, so rooms also expire after a TTL.
 *
 * Null results (not in the database) are cached too: a new friend changes db2, which clears everything.
 *
 * Not thread-safe: use it from the decoder's dispatcher only.
 */
internal class DataCache(
    private val roomTtlMillis: Long,
) {
    private val friends     : MutableMap<Long, NormalUserData?> = hashMapOf()
    private val openMembers : MutableMap<MemberKey, OpenChatMemberData?> = hashMapOf()
    private val openLinks   : MutableMap<Long, OpenLinkData?> = hashMapOf()
    private val rooms       : MutableMap<Long, RoomEntry> = hashMapOf()

    /** The whole friend list, once read; lookups of single users (Kakao channels too) don't add to it. */
    private var friendList: List<NormalUserData>? = null

    /** Whether the whole friend list has been read. */
    val allFriendsLoaded: Boolean
        get() = friendList != null

    fun friend(id: Long, loader: (Long) -> NormalUserData?): NormalUserData? =
        if (id in friends) friends[id] else loader(id).also { friends[id] = it }

    fun hasFriend(id: Long): Boolean =
        id in friends

    /** The cached friend [id]; null also when it isn't cached (see [hasFriend]). */
    fun cachedFriend(id: Long): NormalUserData? =
        friends[id]

    fun putFriend(id: Long, data: NormalUserData?) {
        friends[id] = data
    }

    fun putAllFriends(data: List<NormalUserData>) {
        for (friend in data)
            friends[friend.id] = friend
        friendList = data
    }

    /** The friend list [putAllFriends] stored; empty before. */
    fun cachedFriends(): List<NormalUserData> =
        friendList.orEmpty()

    fun openMember(chatId: Long, userId: Long, loader: () -> OpenChatMemberData?): OpenChatMemberData? {
        val key = MemberKey(chatId, userId)
        return if (key in openMembers) openMembers[key] else loader().also { openMembers[key] = it }
    }

    fun putOpenMember(chatId: Long, userId: Long, data: OpenChatMemberData?) {
        openMembers[MemberKey(chatId, userId)] = data
    }

    fun invalidateOpenMember(chatId: Long, userId: Long) {
        openMembers -= MemberKey(chatId, userId)
    }

    fun openLink(id: Long, loader: (Long) -> OpenLinkData?): OpenLinkData? =
        if (id in openLinks) openLinks[id] else loader(id).also { openLinks[id] = it }

    fun room(id: Long, now: Long = System.currentTimeMillis()): RoomData? {
        val entry = rooms[id] ?: return null
        if (now - entry.loadedAt > roomTtlMillis) {
            rooms -= id
            return null
        }
        return entry.room
    }

    fun putRoom(room: RoomData, now: Long = System.currentTimeMillis()) {
        rooms[room.id] = RoomEntry(room, now)
    }

    fun invalidateRoom(id: Long) {
        rooms -= id
    }

    /** Drops the friends, and the rooms named after them. */
    fun invalidateFriends() {
        friends.clear()
        rooms.clear()
        friendList = null
    }

    fun invalidateDb2() {
        friends.clear()
        openMembers.clear()
        openLinks.clear()
        rooms.clear()
        friendList = null
    }

    fun invalidateAll() =
        invalidateDb2()

    private data class MemberKey(val chatId: Long, val userId: Long)

    private class RoomEntry(val room: RoomData, val loadedAt: Long)
}
