package dev.mooner.kakaodb.core.parser

import dev.mooner.kakaodb.core.crypto.KakaoCrypto
import dev.mooner.kakaodb.core.model.*
import dev.mooner.kakaodb.core.sqlite.*
import kotlinx.serialization.json.*

/** Lenient parser settings for the JSON columns of the KakaoTalk database. */
val kakaoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

private const val FLAG_DELETED = 0x4000
private const val JSON_EMPTY_OBJ = "{}"

// ---------------------------------------------------------------- chat_logs

fun Row.toChatData(): ChatData {
    val rawType  = int("type") ?: 0
    val deleted  = rawType and FLAG_DELETED != 0
    val typeInt  = rawType and FLAG_DELETED.inv()
    val type     = ChatType.of(typeInt)
    val userId   = long("user_id") ?: 0L

    val v = string("v")
        ?.let { runCatching { kakaoJson.decodeFromString<ChatData.Metadata>(it) }.getOrNull() }
        ?: ChatData.Metadata()

    val message = string("message")
        ?.let { if (it.isEmpty() || it == JSON_EMPTY_OBJ) it else KakaoCrypto.decryptOrRaw(userId, v.enc, it) }
        ?: ""

    val attachment = string("attachment")
        ?.takeIf { it.isNotBlank() && it != JSON_EMPTY_OBJ }
        ?.let { KakaoCrypto.decryptOrRaw(userId, v.enc, it) }
        ?.let(::parseJsonOrNull)
    val obj = attachment as? JsonObject

    return ChatData(
        pk              = long("_id") ?: 0L,
        id              = long("id") ?: 0L,
        type            = type,
        typeInt         = typeInt,
        chatId          = long("chat_id") ?: 0L,
        userId          = userId,
        message         = message,
        rawAttachment   = attachment,
        createdAt       = long("created_at") ?: 0L,
        deletedAt       = long("deleted_at") ?: 0L,
        clientMessageId = long("client_message_id") ?: 0L,
        prevId          = long("prev_id") ?: 0L,
        referer         = long("referer") ?: 0L,
        supplement      = string("supplement"),
        v               = v,
        deleted         = deleted,
        feed            = if (type == ChatType.FEED) parseFeed(message) else null,
        mentions        = obj?.let { parseMentions(it["mentions"]) }.orEmpty(),
        reply           = if (type == ChatType.REPLY) obj?.let(::parseReply) else null,
        media           = obj?.let { parseMedia(type, it) }.orEmpty(),
        location        = if (type == ChatType.MAP) obj?.let(::parseLocation) else null,
        emoticon        = if (type?.isEmoticon == true) obj?.let(::parseEmoticon) else null,
        threadId        = long("thread_id")?.takeIf { it != 0L },
        scope           = int("scope"),
    )
}

fun parseFeed(message: String): FeedData? {
    val obj = parseJsonOrNull(message) as? JsonObject ?: return null
    return runCatching { kakaoJson.decodeFromJsonElement<FeedData>(obj).copy(raw = obj) }
        .getOrNull()
}

// ---------------------------------------------------------------- attachments
// Read field by field rather than through serializers, so that a field KakaoTalk drops or retypes only loses that
// field instead of the whole attachment.

/** `[{"user_id": 123, "at": [1], "len": 3}, ...]` */
fun parseMentions(element: JsonElement?): List<Mention> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val userId = obj.long("user_id") ?: obj.long("userId") ?: return@mapNotNull null
        Mention(
            userId    = userId,
            positions = obj.array("at").orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull },
            length    = obj.int("len") ?: 0,
        )
    }

private fun parseReply(obj: JsonObject): ReplyInfo? {
    val logId = obj.long("src_logId") ?: return null
    return ReplyInfo(
        logId    = logId,
        userId   = obj.long("src_userId") ?: 0L,
        message  = obj.string("src_message"),
        type     = obj.int("src_type"),
        mentions = parseMentions(obj["src_mentions"]),
    )
}

private fun parseMedia(type: ChatType?, obj: JsonObject): List<MediaInfo> =
    when (type) {
        ChatType.PHOTO -> listOf(MediaInfo(
            kind         = "photo",
            url          = obj.string("url"),
            thumbnailUrl = obj.string("thumbnailUrl"),
            width        = obj.int("w"),
            height       = obj.int("h"),
            size         = obj.long("s") ?: obj.long("size"),
            mimeType     = obj.string("mt"),
        ))
        ChatType.MULTIPHOTO -> {
            val urls = obj.strings("imageUrls")
            val thumbnails = obj.strings("thumbnailUrls")
            val widths = obj.longs("wl")
            val heights = obj.longs("hl")
            val sizes = obj.longs("sl")
            val mimeTypes = obj.strings("mtl")
            urls.indices.map { i ->
                MediaInfo(
                    kind         = "photo",
                    url          = urls[i],
                    thumbnailUrl = thumbnails.getOrNull(i),
                    width        = widths.getOrNull(i)?.toInt(),
                    height       = heights.getOrNull(i)?.toInt(),
                    size         = sizes.getOrNull(i),
                    mimeType     = mimeTypes.getOrNull(i),
                )
            }
        }
        ChatType.VIDEO -> listOf(MediaInfo(
            kind            = "video",
            url             = obj.string("url"),
            thumbnailUrl    = obj.string("thumbnailUrl"),
            width           = obj.int("w"),
            height          = obj.int("h"),
            size            = obj.long("s") ?: obj.long("size"),
            durationSeconds = obj.int("d"),
        ))
        ChatType.AUDIO -> listOf(MediaInfo(
            kind            = "audio",
            url             = obj.string("url"),
            size            = obj.long("s") ?: obj.long("size"),
            durationSeconds = obj.int("d"),
        ))
        ChatType.FILE -> listOf(MediaInfo(
            kind      = "file",
            url       = obj.string("url"),
            name      = obj.string("name"),
            size      = obj.long("size") ?: obj.long("s"),
            expiresAt = obj.long("expire"),
        ))
        else -> emptyList()
    }.filter { it.url != null || it.name != null }

private fun parseLocation(obj: JsonObject): LocationInfo? {
    val lat = obj.double("lat") ?: return null
    val lng = obj.double("lng") ?: return null
    return LocationInfo(lat, lng, address = obj.string("a"), title = obj.string("t"))
}

private fun parseEmoticon(obj: JsonObject): EmoticonInfo =
    EmoticonInfo(
        name = obj.string("name"),
        path = obj.string("path"),
        alt  = obj.string("alt"),
        type = obj.string("type"),
    )

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.string(key: String): String? =
    primitive(key)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.long(key: String): Long? =
    primitive(key)?.contentOrNull?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

private fun JsonObject.int(key: String): Int? =
    long(key)?.toInt()

private fun JsonObject.double(key: String): Double? =
    primitive(key)?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.array(key: String): JsonArray? =
    this[key] as? JsonArray

private fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }

private fun JsonObject.longs(key: String): List<Long?> =
    array(key).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }

// ---------------------------------------------------------------- chat_rooms

/** An id list such as "[1,2,3]", with number or string elements. */
fun parseIdList(text: String?): List<Long> {
    if (text.isNullOrBlank())
        return emptyList()
    val array = parseJsonOrNull(text) as? JsonArray
        ?: return text.trim('[', ']').split(',').mapNotNull { it.trim().trim('"').toLongOrNull() }
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
}

class RoomMeta(val title: String?, val notice: String?)

/**
 * chat_rooms.meta: `[{"type":3,"content":"title",...}, {"type":1,"content":"notice",...}]`
 * (LOCO ChatMetaType: 1 NOTICE, 3 TITLE)
 */
fun parseRoomMeta(element: JsonElement?): RoomMeta {
    val entries = element as? JsonArray ?: return RoomMeta(null, null)
    fun contentOf(type: Int): String? =
        entries.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"] as? JsonPrimitive)?.intOrNull == type }
            ?.get("content")
            ?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            ?.takeIf(String::isNotBlank)
    return RoomMeta(title = contentOf(3), notice = contentOf(1))
}

fun JsonElement?.stringField(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

// ---------------------------------------------------------------- db2

private fun Row.decrypted(column: String, selfId: Long, enc: Int): String? =
    string(column)
        ?.let { KakaoCrypto.decryptOrRaw(selfId, enc, it) }
        ?.takeIf { it.isNotEmpty() && it != JSON_EMPTY_OBJ }

fun Row.toFriend(selfId: Long): NormalUserData? {
    val id  = long("id") ?: return null
    val enc = int("enc") ?: 0
    fun dec(column: String) = decrypted(column, selfId, enc)

    val boardV = dec("board_v")?.let(::parseJsonOrNull)
    val nickName = dec("nick_name")
    return NormalUserData(
        id                      = id,
        name                    = dec("name") ?: nickName ?: "Unknown($id)",
        nickName                = nickName,
        statusMessage           = dec("status_message"),
        createdAt               = long("created_at"),
        profileImageURL         = dec("original_profile_image_url"),
        fullProfileImageURL     = dec("full_profile_image_url"),
        thumbnailImageURL       = dec("profile_image_url"),
        backgroundImageURL      = boardV.stringField("originalBackgroundImageUrl"),
        animatedProfileImageURL = boardV.stringField("originalAnimatedProfileImageUrl"),
        userType                = int("user_type"),
        phoneNumber             = dec("phone_number"),
        contactName             = dec("contact_name"),
        uuid                    = dec("uuid"),
        accountId               = long("accout_id"),
        favorite                = bool("favorite") ?: false,
        hidden                  = bool("hidden") ?: false,
        blocked                 = bool("blocked") ?: false,
        purged                  = bool("purged") ?: false,
        suspended               = bool("suspended") ?: false,
    )
}

// ---------------------------------------------------------------- crypto_user_database

/**
 * A row of crypto_user_database.user, where newer KakaoTalk versions keep the friend list. The file itself is
 * encrypted (SQLCipher), so the values are plain; an `enc` column, if one shows up, is honored as in db2.friends.
 *
 * Iris resolves names from `nickname`, checked on a device; `name` and `nick_name` are db2.friends' names, kept as
 * fallbacks in case a version brings them back.
 */
fun Row.toUserDbFriend(selfId: Long): NormalUserData? {
    val id  = long("id") ?: return null
    val enc = int("enc") ?: 0
    fun text(column: String) =
        if (enc > 0) decrypted(column, selfId, enc)
        else string(column)?.takeIf { it.isNotEmpty() && it != JSON_EMPTY_OBJ }

    val boardV    = text("board_v")?.let(::parseJsonOrNull)
    val nickname  = text("nickname")
    val thumbnail = text("profile_image_url")
    val full      = text("full_profile_image_url")
    return NormalUserData(
        id                      = id,
        name                    = nickname ?: text("name") ?: text("nick_name") ?: "Unknown($id)",
        nickName                = text("nick_name") ?: nickname,
        statusMessage           = text("status_message"),
        createdAt               = long("created_at"),
        profileImageURL         = text("original_profile_image_url") ?: full ?: thumbnail,
        fullProfileImageURL     = full,
        thumbnailImageURL       = thumbnail,
        backgroundImageURL      = boardV.stringField("originalBackgroundImageUrl"),
        animatedProfileImageURL = boardV.stringField("originalAnimatedProfileImageUrl"),
        userType                = int("user_type"),
        phoneNumber             = text("phone_number"),
        contactName             = text("contact_name"),
        uuid                    = text("uuid"),
        accountId               = long("account_id") ?: long("accout_id"),
        favorite                = bool("favorite") ?: false,
        hidden                  = bool("hidden") ?: false,
        blocked                 = bool("blocked") ?: false,
        purged                  = bool("purged") ?: false,
        suspended               = bool("suspended") ?: false,
    )
}

/** A Kakao channel of crypto_user_database.talk_channel: no `user` row, only a name. Null without one. */
fun Row.toTalkChannelUser(): NormalUserData? {
    val id   = long("id") ?: return null
    val name = string("name")?.takeIf(String::isNotEmpty) ?: return null
    val image = string("profile_image_url")?.takeIf(String::isNotEmpty)
    return NormalUserData(id = id, name = name, profileImageURL = image, thumbnailImageURL = image)
}

fun Row.toOpenChatMember(selfId: Long): OpenChatMemberData? {
    val id  = long("user_id") ?: return null
    val enc = int("enc") ?: 0
    fun dec(column: String) = decrypted(column, selfId, enc)

    val thumbnail = dec("profile_image_url")
    val full      = dec("full_profile_image_url")
    return OpenChatMemberData(
        id                  = id,
        name                = dec("nickname") ?: "Unknown($id)",
        profileImageURL     = dec("original_profile_image_url") ?: full ?: thumbnail,
        fullProfileImageURL = full,
        thumbnailImageURL   = thumbnail,
        chatId              = long("involved_chat_id"),
        linkId              = long("link_id"),
        profileLinkId       = long("profile_link_id"),
        memberType          = OpenMemberType.of(int("link_member_type")),
        profileType         = int("profile_type"),
        privilege           = long("privilege"),
    )
}

fun Row.toOpenLink(): OpenLinkData? {
    val id = long("id") ?: return null
    return OpenLinkData(
        id              = id,
        name            = string("name"),
        url             = string("url"),
        imageURL        = string("image_url"),
        iconURL         = string("icon_url"),
        description     = string("description"),
        type            = int("type"),
        hostId          = long("user_id"),
        memberLimit     = int("member_limit"),
        directChatLimit = int("direct_chat_limit"),
        active          = bool("active"),
        searchable      = bool("searchable"),
        createdAt       = long("created_at"),
    )
}

fun parseJsonOrNull(text: String): JsonElement? {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '['))
        return null
    return runCatching { kakaoJson.parseToJsonElement(text) }.getOrNull()
}
