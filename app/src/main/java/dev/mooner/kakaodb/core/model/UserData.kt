@file:OptIn(ExperimentalSerializationApi::class)

package dev.mooner.kakaodb.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.JsonClassDiscriminator

/** A user. IDs serialize as strings: they don't fit in a JS number. */
@Serializable
@JsonClassDiscriminator("kind")
sealed class UserData {

    abstract val id: Long

    abstract val name: String

    /** Original-size profile image URL. */
    abstract val profileImageURL: String?
}

/** The user whose KakaoTalk database this is. */
@Serializable
@SerialName("self")
data class SelfUserData(
    @Serializable(with = LongAsStringSerializer::class)
    override val id: Long,
    override val name: String,
    override val profileImageURL: String? = null,
) : UserData()

/** A user in the friend list (db2.friends). */
@Serializable
@SerialName("friend")
data class NormalUserData(
    @Serializable(with = LongAsStringSerializer::class)
    override val id: Long,
    override val name: String,
    val nickName: String? = null,
    val statusMessage: String? = null,
    val createdAt: Long? = null,
    override val profileImageURL: String? = null,
    val fullProfileImageURL: String? = null,
    val thumbnailImageURL: String? = null,
    val backgroundImageURL: String? = null,
    val animatedProfileImageURL: String? = null,
    val userType: Int? = null,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val uuid: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val accountId: Long? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val blocked: Boolean = false,
    val purged: Boolean = false,
    val suspended: Boolean = false,
) : UserData()

/** An open chat member (db2.open_chat_member). The same person can have a different profile in each room. */
@Serializable
@SerialName("open_member")
data class OpenChatMemberData(
    @Serializable(with = LongAsStringSerializer::class)
    override val id: Long,
    override val name: String,
    override val profileImageURL: String? = null,
    val fullProfileImageURL: String? = null,
    val thumbnailImageURL: String? = null,
    /** involved_chat_id: the room this row names. Newer KakaoTalk versions leave it 0 for most members. */
    @Serializable(with = LongAsStringSerializer::class)
    val chatId: Long? = null,
    /** The open link (room) whose member this is: what newer versions key members by, with the user id. */
    @Serializable(with = LongAsStringSerializer::class)
    val linkId: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val profileLinkId: Long? = null,
    val memberType: OpenMemberType = OpenMemberType.UNKNOWN,
    val profileType: Int? = null,
    val privilege: Long? = null,
) : UserData()

/** A user found nowhere in the database. */
@Serializable
@SerialName("unknown")
data class UnknownUserData(
    @Serializable(with = LongAsStringSerializer::class)
    override val id: Long,
    override val name: String = "Unknown($id)",
    override val profileImageURL: String? = null,
) : UserData()

/** open_chat_member.link_member_type */
@Serializable
enum class OpenMemberType(val typeInt: Int) {
    HOST    (1),
    NORMAL  (2),
    MANAGER (4),
    BOT     (8),
    UNKNOWN (-1);

    companion object {
        fun of(value: Int?): OpenMemberType =
            entries.firstOrNull { it.typeInt == value } ?: UNKNOWN
    }
}
