package dev.mooner.kakaodb.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.JsonElement

/**
 * A chat_logs row. [message] and [rawAttachment] are decrypted.
 * IDs serialize as strings: they don't fit in a JS number.
 */
@Serializable
data class ChatData(
    /** chat_logs._id: rowid, a local sequence that only grows. */
    @Serializable(with = LongAsStringSerializer::class)
    val pk: Long,
    /** Server log id. */
    @Serializable(with = LongAsStringSerializer::class)
    val id: Long,
    val type: ChatType?,
    /** The type value without the deleted flag, including types this plugin doesn't know. */
    val typeInt: Int,
    @Serializable(with = LongAsStringSerializer::class)
    val chatId: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val userId: Long,
    val message: String,
    /** The decrypted attachment JSON. */
    @SerialName("attachment")
    val rawAttachment: JsonElement? = null,
    /** Unix time in seconds. */
    val createdAt: Long,
    val deletedAt: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val clientMessageId: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val prevId: Long,
    val referer: Long,
    val supplement: String?,
    val v: Metadata,
    val deleted: Boolean,
    val feed: FeedData? = null,
    val mentions: List<Mention> = emptyList(),
    val reply: ReplyInfo? = null,
    val media: List<MediaInfo> = emptyList(),
    val location: LocationInfo? = null,
    val emoticon: EmoticonInfo? = null,
    /**
     * chat_logs.thread_id: log id of the message an open chat thread hangs from, for messages posted in the thread.
     * Null outside threads and on KakaoTalk versions without them.
     *
     * Thread messages aren't REPLY rows: what they answer is only here, not in a `src_*` attachment, so [reply] is
     * null for them unless they also quote a message.
     */
    @Serializable(with = LongAsStringSerializer::class)
    val threadId: Long? = null,
    /**
     * chat_logs.scope. Seen so far: 1 for messages in the room, 3 for thread messages also shown in the room.
     * Null on KakaoTalk versions without threads.
     */
    val scope: Int? = null,
) {

    /** The sender: a friend, an open chat member, the user, or unknown. */
    var user: UserData? = null

    val isFeed: Boolean
        get() = type == ChatType.FEED

    /** Whether this message was posted in a thread (the message the thread hangs from isn't). */
    val isThreadMessage: Boolean
        get() = threadId != null && threadId != id

    @Serializable
    data class Metadata(
        val notDecoded              : Boolean = false,
        val origin                  : Origin = Origin.UNKNOWN,
        @SerialName("c")
        val timestamp               : String? = null,
        val isSingleDefaultEmoticon : Boolean = false,
        val defaultEmoticonsCount   : Int = 0,
        val isMine                  : Boolean = false,
        val pushAlert               : Boolean = false,
        val enc                     : Int = 0,
        val sendLeverageLog         : Boolean = false,
    ) {
        enum class Origin {
            MCHATLOGS,
            MSG,
            SYNCMSG,
            LOGINLIST,
            POST,
            CHATINFO,
            SYNCDLMSG, // message deleted
            WRITE,
            NEWMEM,    // joined
            DELMEM,    // left
            KICKMEM,   // kicked
            SYNCREWR,  // hidden (synced)
            REWRITE,   // hidden by the host
            SYNCMEMT,  // host changed
            DELETEMSG, // deleted by the host
            UNKNOWN,
        }
    }
}

/** A mention in a message: `@name` at the given character positions. */
@Serializable
data class Mention(
    @Serializable(with = LongAsStringSerializer::class)
    val userId: Long,
    val positions: List<Int> = emptyList(),
    val length: Int = 0,
)

/** What a REPLY message replies to. */
@Serializable
data class ReplyInfo(
    @Serializable(with = LongAsStringSerializer::class)
    val logId: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val userId: Long,
    val message: String? = null,
    val type: Int? = null,
    val mentions: List<Mention> = emptyList(),
)

/**
 * A photo, video, audio clip or file of a message: an element of `msg.media`.
 *
 * Values the attachment doesn't have are `null`. The URLs are KakaoTalk's servers' and may expire.
 */
@Serializable
data class MediaInfo(
    /** What it is: `photo`, `video`, `audio` or `file`. */
    val kind: String,
    /** The URL of the photo, video, audio clip or file. */
    val url: String? = null,
    /** The URL of a smaller preview image, for photos and videos. */
    val thumbnailUrl: String? = null,
    /** The width in pixels, for photos and videos. */
    val width: Int? = null,
    /** The height in pixels, for photos and videos. */
    val height: Int? = null,
    /** The size in bytes. */
    val size: Long? = null,
    /** The MIME type, such as `image/jpeg`, for photos. */
    val mimeType: String? = null,
    /** The file's name, for files. */
    val name: String? = null,
    /** When the file expires on KakaoTalk's servers, as Unix time in seconds, for files. */
    val expiresAt: Long? = null,
    /** The length in seconds, for videos and audio clips. */
    val durationSeconds: Int? = null,
)

/** A location shared in a message: `msg.location`. */
@Serializable
data class LocationInfo(
    /** The latitude, in degrees. */
    val latitude: Double,
    /** The longitude, in degrees. */
    val longitude: Double,
    /** The address, or `null` if the message has none. */
    val address: String? = null,
    /** The place's name, or `null` if the message has none. */
    val title: String? = null,
)

/** An emoticon sent in a message, as its attachment describes it: `msg.emoticon`. Missing values are `null`. */
@Serializable
data class EmoticonInfo(
    /** The emoticon's name. */
    val name: String? = null,
    /** The path of the emoticon's image on KakaoTalk's servers. */
    val path: String? = null,
    /** The emoticon's alternative text, as KakaoTalk shows in place of it. */
    val alt: String? = null,
    /** KakaoTalk's type of the emoticon's image. */
    val type: String? = null,
)
