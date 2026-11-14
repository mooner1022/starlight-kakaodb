---
title: Sending
summary: How KakaoDB sends messages through KakaoTalk notifications' reply actions, which rooms can be sent to, the send queue and interval, and why a send returns false or never arrives.
---

The database can only be read, so KakaoDB sends the way StarLight does: through the reply action of KakaoTalk's
notification for the room, as if the user typed a reply in the notification. `room.send(text)` (in an event function,
`event.room.send(text)`, whatever the event) and `KakaoDB.send(roomId, text)` both do this, and send text only.

## Which rooms can be sent to

A room can be sent to once a notification from it arrived since StarLight started. So:
- right after StarLight starts, no room can be sent to until a message arrives in it;
- a room whose notifications the user turned off in KakaoTalk can't be sent to, even though `onKakaoMessage` comes
  for it;
- a room a script never got a message from, such as one found with `KakaoDB.getRooms()`, can be sent to only if a
  notification from it arrived.

`room.canSend` and `KakaoDB.canSend(roomId)` tell whether a room can be sent to now, and
`KakaoDB.getSendableRoomIds()` lists those rooms. The plugin settings show them under **전송 가능한 방** (rooms that
can be sent to). Notification access for StarLight must be on, as for StarLight's own replies.

## The send queue

Sends are queued and go out one at a time, in order, with the plugin setting **전송 간격 (ms)** (send interval)
between them: 600 ms by default, from 100 to 3000. KakaoTalk drops replies that come too fast.

- `send` returns at once. `true` means the message was queued, not that it was delivered.
- `false` means it wasn't queued: no notification from the room was seen since StarLight started, 256 messages are
  already waiting, or the plugin is off.
- A queued message is dropped, with a line in the plugin's log, when KakaoTalk no longer takes replies through the
  room's notification, such as after KakaoTalk restarted. The room can be sent to again once a new notification from it
  arrives.
- Messages sent this way appear as the user's own; with **내 메시지 무시** (ignore my messages) on, as by default, they
  don't call `onKakaoMessage` (guide:events).

## Marking as read

`room.markAsRead()` and `KakaoDB.markAsRead(roomId)` use the notification's "mark as read" action, with the same
condition: a notification from the room since StarLight started. They return `false` without one.

## Files

KakaoDB doesn't send files. A KakaoDB room is also a StarLight `ChatRoom`, so StarLight's own `room.sendMedia(...)`
applies to it; StarLight sends those through KakaoTalk's share screen, not through KakaoDB's queue or interval
(`describe_api MediaSharer`).
