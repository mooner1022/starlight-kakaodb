---
title: Events
summary: KakaoDB's events, what calls each one and what doesn't, how they compare with StarLight's notification-based onMessage, and the compatibility events.
---

KakaoDB fires an event for each row KakaoTalk stores in its database while the plugin is connected (guide:setup).
`describe_event` shows each one's argument.

Every `onKakao...` function gets one event object, `event`, with the same base: the room it happened in
(`event.room`, which sends with `event.room.send(text)`), who caused it (`event.actor`), when (`event.createdAt`) and
the client (`event.db`). `onKakaoMessage` gets the message as `event.message`, and its sender as `event.actor`. The
compatibility events `onMessage` and `onMessageDeleted` get StarLight's own objects instead (below); they have
`event.room` too, but not the rest.

| Function | Event ID | Called when |
|---|---|---|
| `onKakaoMessage(event)` | `kakaodb.message.create` | A message is stored: text, photos, replies, emoticons... |
| `onKakaoMessageDeleted(event)` | `kakaodb.message.delete` | A message is deleted for everyone. |
| `onKakaoMessageHidden(event)` | `kakaodb.message.hide` | An open chat's host or a manager hides messages. |
| `onKakaoMemberJoined(event)` | `kakaodb.member.join` | Members are invited, or join an open chat through its link. |
| `onKakaoMemberLeft(event)` | `kakaodb.member.leave` | A member leaves or is kicked. |
| `onKakaoMemberRoleChanged(event)` | `kakaodb.member.role` | An open chat's manager is appointed or dismissed, or its host changes. |
| `onKakaoFeed(event)` | `kakaodb.room.feed` | Any feed (system message), including those of the four events above. |
| `onMessage(event)` | `kakaodb.compat.on_message` | A message is stored, while its compatibility setting is on (below). |
| `onMessageDeleted(event)` | `kakaodb.compat.on_message_deleted` | A message is deleted for everyone, while its compatibility setting is on (below). |

- A project receives an event only once one of its allowed-event entries allows it: `kakaodb.*` allows them all
  (guide:starlight:projects#allowed-events). In NeonJS, the function must be exported.
- Events come while the plugin is connected and StarLight's **전역 전원** (global power) is on.
- The user's own messages, the bot's replies among them, don't call `onKakaoMessage` while **내 메시지 무시** (ignore
  my messages) is on, as it is by default. That keeps a bot from answering itself. Check `event.message.isMine` when
  the user turned it off.
- Feeds, such as joins and deletions, don't call `onKakaoMessage`. `onKakaoFeed` gets every feed, so a project that
  receives it and, say, `onKakaoMemberJoined` gets each join twice: once as a feed.

## Compared with StarLight's onMessage

StarLight's own `onMessage` comes from KakaoTalk's notifications. `onKakaoMessage` comes from the database, so:
- it also comes for rooms whose notifications are off, and while the room is open on screen;
- it has the exact room and user IDs, mentions, the quoted message of a reply, threads, and media
  (guide:scripting);
- deletions come for any message, not only the latest of a room.

Replying still goes through notifications: a room can be sent to only after a notification from it arrived since
StarLight started (guide:sending).

## Compatibility events

`kakaodb.compat.on_message` and `kakaodb.compat.on_message_deleted` call `onMessage(event)` and
`onMessageDeleted(event)` with StarLight's own objects, so a script written for StarLight's events runs from the
database unchanged. They fire only while the plugin settings **onMessage 호환 이벤트** and
**onMessageDeleted 호환 이벤트** are on; both are off by default.

- A project that receives both StarLight's `starlight.message.create` and `kakaodb.compat.on_message` gets every
  message twice, as both call `onMessage`. Allow only one of them; the same goes for the deletion events.
- The database has no profile pictures or images: `event.sender.profileBitmap` is an empty 1×1 image, and
  `event.image` is always `null`.
- `event.room` is a KakaoDB room, so `event.room.send(text)` goes through KakaoDB's send queue.
- `onMessageDeleted`'s `message` and `sender` are empty when the database no longer had the deleted message.
