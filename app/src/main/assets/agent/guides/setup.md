---
title: Setup
summary: How KakaoDB reaches KakaoTalk's database: the three access modes, the initial setup, the connection status and the settings a script depends on.
---

KakaoDB reads KakaoTalk's own database (`KakaoTalk.db` and `KakaoTalk2.db`) to give scripts events and queries. It
needs no notification to receive, but Android keeps that database private to KakaoTalk, so the user has to give the
plugin a way in: the **접근 방식** (access mode). Scripts can't change it; tell the user what to set.

## Access modes

| Mode | Setting | What it needs |
|---|---|---|
| Direct | **직접** | The database files are readable as they are: a device without SELinux enforcement (such as redroid), permissions changed by hand, or a copy of the files in another folder. |
| Root | **루트** | A rooted device (Android 10 or later). The plugin starts a helper with `su`, which reads the database in place. The root manager may ask for permission. |
| Shared UID | **공유 UID** | KakaoTalk and the plugin's helper app, patched to share a user ID and installed in the plugin's initial setup. No root. Updating the helper and the signing key are in the plugin settings, under **공유 UID 도우미** (shared UID helper). |

- **데이터베이스 폴더** (database folder) is `/data/data/com.kakao.talk/databases` unless KakaoTalk's data is
  somewhere else, such as a second user or a cloned app.
- In direct mode, newer KakaoTalk versions keep the friend list in `crypto_user_database`, which only root and shared
  UID modes read. Friends then come as users of kind `unknown`, and `KakaoDB.getFriends()` is empty; messages still
  arrive.

## Initial setup

StarLight opens the plugin's **초기 설정** (initial setup) sheet when the access mode was never saved, and again when
the connection fails, with the reason on top. The user can also open it from the plugin list. It asks for the access
mode and the database folder, and checks that the database can be read before saving. For shared UID mode it also
holds the first installation's three steps: patch KakaoTalk and the helper, install KakaoTalk, install the helper.
What comes later is in the plugin settings, shown while shared UID is chosen: **도우미만 다시 패치** and
**패치된 도우미 설치** (patch and install the helper again, when the status says the installed helper is out of date
after a plugin update), and **서명 키 백업** and **서명 키 복원** (back up the signing key before removing StarLight
or the plugin; restore it before the first patch after installing again).

## Connection status

The plugin settings' **연결** (connection) category shows **상태** (status): connected with the account's name and ID,
reconnecting, stopped, or failed with the reason, and any warnings. **연결** / **연결 끊기** (connect, disconnect) and
**다시 연결** (reconnect) are there too. **자동 연결** (connect automatically) connects when the plugin is enabled; it
is on by default.

From a script, `KakaoDB.isConnected` tells whether queries work now. Each connection starts at the database's newest
row: events come only for what is stored while connected, never for older messages, and messages stored while the
plugin was disconnected or off don't come later (queries still read them). A connection that breaks is reopened by
itself, and what was stored meanwhile comes once it is back.

## Settings scripts depend on

| Setting | Effect |
|---|---|
| **내 메시지 무시** (ignore my messages) | On by default: the user's own messages, the bot's replies among them, don't call `onKakaoMessage`. |
| **onMessage 호환 이벤트**, **onMessageDeleted 호환 이벤트** | Off by default: the compatibility events fire only while they are on (guide:events#compatibility-events). |
| **전송 간격 (ms)** (send interval) | The time between sent messages, 600 ms by default (guide:sending). |
| **내 사용자 ID**, **내 이름** (my user ID, my name) | Needed only when the plugin can't find the account in the database, which its status then warns about: without them, the user's own messages may not be told apart. |
