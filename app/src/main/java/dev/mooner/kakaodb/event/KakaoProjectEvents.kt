package dev.mooner.kakaodb.event

import dev.mooner.kakaodb.script.KakaoFeedEvent
import dev.mooner.kakaodb.script.KakaoMemberJoinedEvent
import dev.mooner.kakaodb.script.KakaoMemberLeftEvent
import dev.mooner.kakaodb.script.KakaoMemberRoleChangedEvent
import dev.mooner.kakaodb.script.KakaoMessage
import dev.mooner.kakaodb.script.KakaoMessageDeletedEvent
import dev.mooner.kakaodb.script.KakaoMessageHiddenEvent
import dev.mooner.kakaodb.utils.tr
import dev.mooner.starlight.plugincore.chat.DeletedMessage
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import kotlin.reflect.KClass

/*
 * Project events. Their ids are kakaodb.<category>.<id>, with the categories set in KakaoDBPlugin.
 * The short ids are unique across categories: ProjectEvent compares events by them.
 *
 * StarLight AI (StarLight 1.1.0) describes each event from its KDoc, whose @param names the script's argument (argName),
 * and from description, usage and functionName. Each takes one argument and dispatch isn't overridden, so what the
 * script receives is argTypes: scriptArguments and listenerName keep their defaults.
 */

/** A KakaoDB event: the database row that caused it reaches the script's function as one argument, [argName]. */
abstract class KakaoProjectEvent(
    override val id: String,
    override val functionName: String,
    english: String,
    korean: String,
    /** When the event happens, in a few words: the event list shows it under the name. */
    descriptionEnglish: String,
    descriptionKorean: String,
    argType: KClass<*>,
    commentEnglish: String,
    commentKorean: String,
    /** The name of the script function's argument, which the starting code uses and the KDoc's @param names. */
    argName: String = "event",
) : ProjectEvent() {

    override val name: String = tr(english, korean)

    // PluginCore 1.0.1 and later show it; older hosts never call it.
    override val description: String? = tr(descriptionEnglish, descriptionKorean)

    override val functionComment: String = tr(commentEnglish, commentKorean)

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf(argName typedAs argType)
}

// ---------------------------------------------------------------- message

/**
 * A message was stored in KakaoTalk's database; calls the script's `onKakaoMessage(msg)`.
 *
 * Called once per message stored after the plugin connected, notifications or not: rooms with notifications off, and
 * the room open on screen, call it too. Feeds (joins, deletions...) don't; they have events of their own. The user's own
 * messages, the bot's replies among them, call it only when the plugin setting **내 메시지 무시** (Ignore my messages)
 * is off.
 *
 * @param msg The message, with its room and sender; `msg.reply(text)` answers in its room.
 * @see guide:events
 * @see guide:sending
 */
class KakaoMessageCreateEvent : KakaoProjectEvent(
    id = "create",
    functionName = "onKakaoMessage",
    english = "KakaoTalk message (DB)",
    korean = "카카오톡 메시지 (DB)",
    descriptionEnglish = "When a message is stored in KakaoTalk's database",
    descriptionKorean = "카카오톡 데이터베이스에 메시지가 저장될 때",
    argType = KakaoMessage::class,
    commentEnglish = "Called when a message is stored in KakaoTalk's database.",
    commentKorean = "카카오톡 데이터베이스에 메시지가 저장되면 호출됩니다.",
    argName = "msg",
)

/**
 * A message was deleted for everyone ("모든 사람에게서 삭제"); calls the script's `onKakaoMessageDeleted(event)`.
 *
 * Unlike StarLight's `onMessageDeleted`, it comes for any message of any room, not only the latest one.
 *
 * @param event The deletion: `event.message` is the deleted message with its text, if the database still had it.
 * @see guide:events
 */
class KakaoMessageDeleteEvent : KakaoProjectEvent(
    id = "delete",
    functionName = "onKakaoMessageDeleted",
    english = "KakaoTalk message deleted (DB)",
    korean = "카카오톡 메시지 삭제 (DB)",
    descriptionEnglish = "When a message is deleted for everyone",
    descriptionKorean = "메시지가 모두에게서 삭제될 때",
    argType = KakaoMessageDeletedEvent::class,
    commentEnglish = "Called when a message is deleted for everyone.",
    commentKorean = "메시지가 모두에게서 삭제되면 호출됩니다.",
)

/**
 * An open chat's host or a manager hid messages ("가리기"); calls the script's `onKakaoMessageHidden(event)`.
 *
 * @param event The hiding: the hidden messages' IDs, the messages the database still had, and who hid them (`actor`).
 * @see guide:events
 */
class KakaoMessageHideEvent : KakaoProjectEvent(
    id = "hide",
    functionName = "onKakaoMessageHidden",
    english = "KakaoTalk messages hidden (DB)",
    korean = "카카오톡 메시지 가리기 (DB)",
    descriptionEnglish = "When an open chat host or manager hides messages",
    descriptionKorean = "오픈채팅 방장이나 부방장이 메시지를 가릴 때",
    argType = KakaoMessageHiddenEvent::class,
    commentEnglish = "Called when an open chat host or manager hides messages.",
    commentKorean = "오픈채팅 방장이나 부방장이 메시지를 가리면 호출됩니다.",
)

// ---------------------------------------------------------------- member

/**
 * Members joined a room, invited or through an open chat's link; calls the script's `onKakaoMemberJoined(event)`.
 *
 * @param event The join: the members who joined, and who invited them.
 * @see guide:events
 */
class KakaoMemberJoinEvent : KakaoProjectEvent(
    id = "join",
    functionName = "onKakaoMemberJoined",
    english = "KakaoTalk member joined (DB)",
    korean = "카카오톡 멤버 입장 (DB)",
    descriptionEnglish = "When members are invited or join an open chat",
    descriptionKorean = "멤버가 초대되거나 오픈채팅에 들어올 때",
    argType = KakaoMemberJoinedEvent::class,
    commentEnglish = "Called when members are invited or join an open chat.",
    commentKorean = "멤버가 초대되거나 오픈채팅에 들어오면 호출됩니다.",
)

/**
 * A member left a room or was kicked from it; calls the script's `onKakaoMemberLeft(event)`.
 *
 * @param event The leave: the member, and whether they were kicked.
 * @see guide:events
 */
class KakaoMemberLeaveEvent : KakaoProjectEvent(
    id = "leave",
    functionName = "onKakaoMemberLeft",
    english = "KakaoTalk member left (DB)",
    korean = "카카오톡 멤버 퇴장 (DB)",
    descriptionEnglish = "When a member leaves or is kicked",
    descriptionKorean = "멤버가 나가거나 내보내질 때",
    argType = KakaoMemberLeftEvent::class,
    commentEnglish = "Called when a member leaves or is kicked.",
    commentKorean = "멤버가 나가거나 내보내지면 호출됩니다.",
)

/**
 * An open chat's manager (부방장) was appointed or dismissed, or its host changed; calls the script's
 * `onKakaoMemberRoleChanged(event)`.
 *
 * @param event The change: what changed, and the members it concerns.
 * @see guide:events
 */
class KakaoMemberRoleEvent : KakaoProjectEvent(
    id = "role",
    functionName = "onKakaoMemberRoleChanged",
    english = "KakaoTalk open chat role changed (DB)",
    korean = "카카오톡 오픈채팅 권한 변경 (DB)",
    descriptionEnglish = "When an open chat's manager or host changes",
    descriptionKorean = "오픈채팅 부방장이나 방장이 바뀔 때",
    argType = KakaoMemberRoleChangedEvent::class,
    commentEnglish = "Called when a manager is appointed or dismissed, or the host changes.",
    commentKorean = "부방장이 지정·해제되거나 방장이 바뀌면 호출됩니다.",
)

// ---------------------------------------------------------------- room

/**
 * A feed (a system message: joins, leaves, deletions, role changes...) was stored in a room; calls the script's
 * `onKakaoFeed(event)`.
 *
 * Called for every feed, including those that also call one of the events above, so a project that receives both gets
 * such a feed twice. Use it for the feeds that have no event of their own.
 *
 * @param event The feed: its type, the members it names, and its JSON.
 * @see guide:events
 */
class KakaoRoomFeedEvent : KakaoProjectEvent(
    id = "feed",
    functionName = "onKakaoFeed",
    english = "KakaoTalk feed (DB)",
    korean = "카카오톡 피드 (DB)",
    descriptionEnglish = "For every feed (system message) in a room",
    descriptionKorean = "채팅방의 모든 피드(시스템 메시지)마다",
    argType = KakaoFeedEvent::class,
    commentEnglish = "Called for every feed (system message), including those with their own events.",
    commentKorean = "모든 피드(시스템 메시지)마다 호출됩니다. 별도 이벤트가 있는 피드도 포함돼요.",
)

// ---------------------------------------------------------------- compat

/**
 * A message was stored in KakaoTalk's database; calls the script's `onMessage(event)` with StarLight's own message
 * object, so a script written for StarLight's `onMessage` runs from the database.
 *
 * Fired only while the plugin setting **onMessage 호환 이벤트** (onMessage compatibility event) is on. StarLight's own
 * `onMessage` (`starlight.message.create`) calls the same function from notifications, so a project that receives both
 * gets every message twice: allow only one of them. The sender's `profileBitmap` is an empty 1×1 image, and `image` is
 * always `null`, as the database has neither. The user's own messages follow **내 메시지 무시** as `onKakaoMessage` does.
 *
 * @param event The message, as StarLight's `onMessage` gives it; `event.room` is the KakaoDB room, so
 *   `event.room.send(text)` goes through KakaoDB's send queue.
 * @see guide:events#compatibility-events
 */
class KakaoCompatMessageEvent : KakaoProjectEvent(
    id = "on_message",
    functionName = "onMessage",
    english = "onMessage compatibility (DB)",
    korean = "onMessage 호환 이벤트 (DB)",
    descriptionEnglish = "StarLight's onMessage, from the database",
    descriptionKorean = "데이터베이스로 받는 StarLight onMessage",
    argType = Message::class,
    commentEnglish = "StarLight's onMessage, fed from the database. Don't allow it together with StarLight's own onMessage event.",
    commentKorean = "데이터베이스로 받는 StarLight onMessage 이벤트입니다. StarLight 기본 onMessage 이벤트와 함께 켜면 메시지를 두 번 받아요.",
)

/**
 * A message was deleted for everyone; calls the script's `onMessageDeleted(event)` with StarLight's own object, so a
 * script written for StarLight's `onMessageDeleted` runs from the database.
 *
 * Fired only while the plugin setting **onMessageDeleted 호환 이벤트** (onMessageDeleted compatibility event) is on. A
 * project that also receives StarLight's own `onMessageDeleted` (`starlight.message.delete`) may get a deletion twice:
 * allow only one of them.
 *
 * @param event The deletion, as StarLight's `onMessageDeleted` gives it; `message` and `sender` are empty if the
 *   database no longer had the message.
 * @see guide:events#compatibility-events
 */
class KakaoCompatDeleteEvent : KakaoProjectEvent(
    id = "on_message_deleted",
    functionName = "onMessageDeleted",
    english = "onMessageDeleted compatibility (DB)",
    korean = "onMessageDeleted 호환 이벤트 (DB)",
    descriptionEnglish = "StarLight's onMessageDeleted, from the database",
    descriptionKorean = "데이터베이스로 받는 StarLight onMessageDeleted",
    argType = DeletedMessage::class,
    commentEnglish = "StarLight's onMessageDeleted, fed from the database. Don't allow it together with StarLight's own event.",
    commentKorean = "데이터베이스로 받는 StarLight onMessageDeleted 이벤트입니다. StarLight 기본 이벤트와 함께 켜면 두 번 받아요.",
)
