package dev.mooner.kakaodb.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.JsonElement

/**
 * A chat room (chat_rooms, plus db2.open_link for open chats).
 *
 * How [name] is chosen:
 *  - open chats : open link name → name the user set → room title
 *  - others     : name the user set (private_meta.name) → room title (meta type 3) → the other members' names
 */
@Serializable
data class RoomData(
    @Serializable(with = LongAsStringSerializer::class)
    val id: Long,
    val type: Type,
    val name: String,
    /** private_meta.name: a name the user gave the room. */
    val customName: String? = null,
    /** Room title from meta (type 3). */
    val title: String? = null,
    /** Notice from meta (type 1). */
    val notice: String? = null,
    val members: List<@Serializable(with = LongAsStringSerializer::class) Long> = emptyList(),
    val activeMemberIds: List<@Serializable(with = LongAsStringSerializer::class) Long> = emptyList(),
    val activeMembersCount: Int? = null,
    /** Last read log id of each of [activeMemberIds], in the same order. */
    val watermarks: List<@Serializable(with = LongAsStringSerializer::class) Long> = emptyList(),
    @Serializable(with = LongAsStringSerializer::class)
    val lastLogId: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val lastReadLogId: Long? = null,
    val lastUpdatedAt: Long? = null,
    val unreadCount: Int? = null,
    val muteUntilAt: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val linkId: Long? = null,
    val openLink: OpenLinkData? = null,
    val moimMeta: String? = null,
    val privateMeta: JsonElement? = null,
    val meta: JsonElement? = null,
    val v: JsonElement? = null,
) {
    val isOpenChat: Boolean
        get() = type == Type.OM || type == Type.OD

    val isDirectChat: Boolean
        get() = type == Type.DirectChat || type == Type.SDirectChat || type == Type.OD

    val isGroupChat: Boolean
        get() = type == Type.MultiChat || type == Type.SMultiChat || type == Type.OM

    val isSecretChat: Boolean
        get() = type == Type.SDirectChat || type == Type.SMultiChat

    val isMemoChat: Boolean
        get() = type == Type.MemoChat

    @Serializable
    enum class Type {
        PlusChat,
        DirectChat,
        MultiChat,
        SDirectChat,
        SMultiChat,
        OM,
        OD,
        MemoChat,
        MmsChat,
        PlusChatList,
        ItemDetailChat,
        KeywordLogList,
        Ad,
        EventRoom,
        StorageBox,
        Unknown;

        companion object {
            private val byName = entries.associateBy(Type::name)

            fun of(value: String?): Type =
                value?.let(byName::get) ?: Unknown
        }
    }
}

/** db2.open_link */
@Serializable
data class OpenLinkData(
    @Serializable(with = LongAsStringSerializer::class)
    val id: Long,
    val name: String? = null,
    val url: String? = null,
    val imageURL: String? = null,
    val iconURL: String? = null,
    val description: String? = null,
    /** 1: group open chat, 2: 1:1 open chat */
    val type: Int? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val hostId: Long? = null,
    val memberLimit: Int? = null,
    val directChatLimit: Int? = null,
    val active: Boolean? = null,
    val searchable: Boolean? = null,
    val createdAt: Long? = null,
)
