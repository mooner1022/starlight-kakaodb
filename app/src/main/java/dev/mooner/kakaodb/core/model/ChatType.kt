package dev.mooner.kakaodb.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** chat_logs.type without the deleted flag (0x4000). */
@Serializable(with = ChatType.Serializer::class)
enum class ChatType(val typeInt: Int) {
    FEED           ( 0),
    TEXT           ( 1),
    PHOTO          ( 2),
    VIDEO          ( 3),
    CONTACT        ( 4),
    AUDIO          ( 5),
    DITEMEMOTICON  ( 6),
    DITEMGIFT      ( 7),
    DITEMIMG       ( 8),
    KAKAOLINKV1    ( 9),
    AVATAR         (11),
    STICKER        (12),
    SCHEDULE       (13),
    VOTE           (14),
    LOTTERY        (15),
    MAP            (16),
    PROFILE        (17),
    FILE           (18),
    STICKERANI     (20),
    NUDGE          (21),
    ACTIONCON      (22),
    SEARCH         (23),
    POST           (24),
    STICKERGIF     (25),
    REPLY          (26),
    MULTIPHOTO     (27),
    VOIP           (51),
    LIVETALK       (52),
    CUSTOM         (71),
    ALIM           (72),
    PLUSFRIEND     (81),
    PLUSEVENT      (82),
    PLUSFRIENDVIRAL(83),
    OPEN_SCHEDULE  (96),
    OPEN_VOTE      (97),
    OPEN_POST      (98);

    val isEmoticon: Boolean
        get() = this == DITEMEMOTICON || this == STICKER || this == STICKERANI || this == STICKERGIF

    companion object {
        private val byTypeInt: Map<Int, ChatType> = entries.associateBy(ChatType::typeInt)

        fun of(typeInt: Int): ChatType? = byTypeInt[typeInt]
    }

    internal object Serializer : KSerializer<ChatType> {

        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ChatType", PrimitiveKind.INT)

        override fun deserialize(decoder: Decoder): ChatType {
            val value = decoder.decodeInt()
            return byTypeInt[value] ?: throw SerializationException("Unknown ChatType $value")
        }

        override fun serialize(encoder: Encoder, value: ChatType) {
            encoder.encodeInt(value.typeInt)
        }
    }
}
