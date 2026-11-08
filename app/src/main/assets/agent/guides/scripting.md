---
title: Scripting
summary: Working with KakaoDB's objects in scripts: string IDs, Java lists, times, boolean properties, blocking queries, replies versus threads, with NeonJS and Rhino examples.
---

## Reaching KakaoDB

- Event functions get the event's object: a `KakaoMessage` for `onKakaoMessage(msg)`, an event object with `room` and
  `actor` for the others (guide:events).
- `KakaoDB` is a global in every project. Every event object also has it as `db` (`msg.db`, `event.db`): the same
  object.
- `msg.room` and `event.room` are `KakaoRoom`s, which send and query too: `room.send(text)`,
  `room.getMessages(20)`.

## Values

- **IDs are strings.** KakaoTalk's IDs go beyond 2^53, which a JS number can't hold exactly. Pass IDs to KakaoDB as
  strings, compare them as strings (`msg.senderId === "1234567890123456789"`), and keep them as strings when storing
  them. `Number(id)` loses digits.
- **Lists are Java lists**: read them by index, with `length`, or with `for...of`. They aren't JS arrays:
  `Array.from(list)` makes one, for `map`, `filter` and `join`.
- **Times** are Unix time in milliseconds: `new Date(msg.createdAt)`. `MediaInfo.expiresAt` is in seconds.
- **Booleans are properties**: `msg.isMine`, `room.isOpenChat`, `user.isHost`, without parentheses. The exceptions
  come from StarLight's `ChatRoom`: read `room.groupChat` and `room.debugRoom`, or call `room.isGroupChat()`;
  `room.isGroupChat` without the call is a method, which is always truthy.
- **Types are strings**: `msg.type` is `"TEXT"`, `"PHOTO"`, `"REPLY"`..., `user.kind` is `"self"`, `"friend"`,
  `"open_member"` or `"unknown"`.
- Event objects hold the database's values from when the event was read. `room.memberCount`, `room.canSend` and the
  like don't update; read the room again with `KakaoDB.getRoom(room.id)`.

## Queries

- `KakaoDB.getRooms()`, `getMembers(...)`, `getMessages(...)` and the other getters read the database and wait for it,
  for up to 15 seconds. Call them in event functions or later, not at the top level while the script compiles.
- They throw while the plugin isn't connected (`KakaoDB.isConnected` is `false`), and when an ID isn't a number.
  Getters for one thing return `null` when the database doesn't have it; getters for lists return an empty list.
- `msg.getUnreadCount()` and `msg.getThreadRoot()` read the database on each call.
- `toJson()` and `attachmentJson` give the raw database values as JSON text, for what the objects don't show:
  `JSON.parse(msg.attachmentJson)`.

## Replies and threads

Open chats store an answer to a message in one of two ways:
- a **reply** (답장) is a message of type `REPLY`, with the quoted message in `msg.replyTo`;
- a **thread message** (답글) has its usual type, such as `TEXT`, and `msg.replyTo` is `null`. The message the thread
  hangs from is `msg.threadId`, and `msg.getThreadRoot()` reads it.

A reply posted inside a thread has both: `replyTo` is the message it quotes, `threadId` the thread's root. To find
what a message answers, check both:

```js
const target = msg.replyTo != null ? msg.replyTo.id : msg.threadId;   // null: answers nothing
```

`msg.reply(text)` sends to the room, not into the thread.

## Examples

NeonJS:

```js
export function onKakaoMessage(msg) {
    if (msg.text === "!rooms") {
        const names = Array.from(msg.db.getRooms()).slice(0, 10).map((room) => room.name);
        msg.reply(names.join("\n"));
    }
    if (msg.text === "!unread")
        msg.reply(`${msg.getUnreadCount()} haven't read this`);
}

export function onKakaoMemberJoined(event) {
    for (const member of event.members)
        event.room.send(`Welcome, ${member.name}!`);
}
```

Rhino:

```js
function onKakaoMessageDeleted(event) {
    if (event.message != null)
        event.room.send(event.message.sender.name + " deleted: " + event.message.text);
}

function onKakaoMessage(msg) {
    if (msg.isThreadMessage) {
        var root = msg.getThreadRoot();
        if (root != null && root.isMine)
            msg.reply("A thread answer to my message: " + msg.text);
    }
}
```
