package dev.mooner.kakaodb.core

import dev.mooner.kakaodb.JdbcSqliteSession
import dev.mooner.kakaodb.core.crypto.KakaoCrypto
import dev.mooner.kakaodb.core.model.*
import dev.mooner.kakaodb.core.parser.kakaoJson
import dev.mooner.kakaodb.core.sqlite.ISqliteSession
import dev.mooner.kakaodb.core.sqlite.Row
import dev.mooner.kakaodb.core.sqlite.SessionException
import dev.mooner.kakaodb.core.sqlite.SessionFactory
import dev.mooner.kakaodb.rootd.ServedSql
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import kotlin.test.*

/**
 * Runs the decoder against fake databases with the known KakaoTalk schema: change detection, decryption, naming,
 * cache invalidation and the feed events. A read-write connection (writer) plays KakaoTalk.
 */
class DatabaseDecoderTest {

    private val selfId   = 1_000_000_001L
    private val aliceId  = 2_000_000_002L
    private val carolId  = 3_000_000_003L
    private val bobId    = 4_000_000_004L
    private val daveId   = 5_000_000_005L
    private val channelId = 6_000_000_006L
    private val groupId  = 18_000_000_000_000_030L  // above 2^53: must serialize as a string
    private val openId   = 18_000_000_000_000_040L
    private val directId = 18_000_000_000_000_050L
    private val linkId   = 500L
    private val enc      = 31

    private lateinit var dir: File
    private lateinit var main: Connection
    private lateinit var sub: Connection
    private lateinit var decoder: DatabaseDecoder

    private val events = Channel<KakaoEvent>(Channel.UNLIMITED)
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var nextLogId = 9000L

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kakaodb-test").toFile()
        main = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("KakaoTalk.db")}")
        sub  = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("KakaoTalk2.db")}")
        main.exec("PRAGMA journal_mode=WAL")
        sub.exec("PRAGMA journal_mode=WAL")

        main.exec("""CREATE TABLE chat_logs (_id INTEGER PRIMARY KEY AUTOINCREMENT, id INTEGER, type INTEGER, chat_id INTEGER,
            user_id INTEGER, message TEXT, attachment TEXT, created_at INTEGER, deleted_at INTEGER, client_message_id INTEGER,
            prev_id INTEGER, referer INTEGER, supplement TEXT, v TEXT, thread_id INTEGER, scope INTEGER)""")
        // Leaves out some known columns (schat_token...) on purpose: schema differences must be absorbed.
        main.exec("""CREATE TABLE chat_rooms (_id INTEGER PRIMARY KEY AUTOINCREMENT, id INTEGER, type TEXT, members TEXT,
            active_member_ids TEXT, last_log_id INTEGER, last_message TEXT, last_updated_at INTEGER, unread_count INTEGER,
            watermarks TEXT, v TEXT, meta TEXT, private_meta TEXT, link_id INTEGER, moim_meta TEXT, active_members_count INTEGER)""")
        sub.exec("""CREATE TABLE friends (_id INTEGER PRIMARY KEY AUTOINCREMENT, id INTEGER, type INTEGER, name TEXT,
            profile_image_url TEXT, full_profile_image_url TEXT, original_profile_image_url TEXT, status_message TEXT,
            board_v TEXT, nick_name TEXT, user_type INTEGER, favorite INTEGER, hidden INTEGER, enc INTEGER, created_at INTEGER)""")
        sub.exec("""CREATE TABLE open_link (id INTEGER PRIMARY KEY, user_id INTEGER, name TEXT, url TEXT, image_url TEXT,
            type INTEGER, member_limit INTEGER, active INTEGER, created_at INTEGER)""")
        sub.exec("CREATE TABLE open_profile (link_id INTEGER, user_id INTEGER, nickname TEXT)")
        sub.exec("""CREATE TABLE open_chat_member (_id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, type INTEGER,
            profile_type INTEGER, link_member_type INTEGER, nickname TEXT, profile_image_url TEXT, full_profile_image_url TEXT,
            original_profile_image_url TEXT, involved_chat_id INTEGER, enc INTEGER)""")

        insertFriend(aliceId, "Alice", "http://img/alice.jpg")
        sub.exec("INSERT INTO open_link (id, user_id, name, url, type, active) VALUES ($linkId, $bobId, '테스트 오픈방', 'https://open.kakao.com/o/test', 1, 1)")
        sub.exec("INSERT INTO open_profile (link_id, user_id, nickname) VALUES (1, $selfId, '내오픈닉')")
        insertOpenMember(bobId, "Bob", OpenMemberType.HOST)
        insertOpenMember(daveId, "Dave", OpenMemberType.NORMAL)

        main.exec("""INSERT INTO chat_rooms (id, type, members, active_member_ids, watermarks, meta, private_meta, link_id, last_updated_at)
            VALUES ($groupId, 'MultiChat', '[$aliceId,$carolId]', '[$aliceId,$carolId]', '[9001,0]', NULL, NULL, NULL, 3)""")
        main.exec("""INSERT INTO chat_rooms (id, type, members, link_id, last_updated_at)
            VALUES ($openId, 'OM', '[$bobId,$daveId]', $linkId, 2)""")
        main.exec("""INSERT INTO chat_rooms (id, type, members, meta, last_updated_at)
            VALUES ($directId, 'DirectChat', '[$aliceId]', '[{"type":1,"content":"공지입니다"}]', 1)""")

        // History (sent by the user: what the self id is inferred from). Before start, so it must not be reported.
        insertChat(groupId, selfId, "옛날 메시지", isMine = true)

        decoder = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), JdbcSqliteSession.factory)
        runBlocking { decoder.open() }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        decoder.close()
        main.close()
        sub.close()
        dir.deleteRecursively()
    }

    private fun start() {
        decoder.start(scope, triggers) { events.send(it) }
    }

    @Test
    fun `resolves self id from own messages`() {
        assertEquals(selfId, decoder.selfID)
        assertEquals("나", decoder.selfName)
        assertTrue(decoder.isOpen)
    }

    @Test
    fun `reads friends from db2 when it has them`() {
        assertEquals(FriendSource.Db2, decoder.friendSource)
    }

    @Test
    fun `reads friends from crypto_user_database when db2 has none`() = runBlocking {
        val userDb = moveFriendsToUserDb()
        val userDecoder = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), JdbcSqliteSession.factoryWithUserDb)
        try {
            userDecoder.open()
            assertEquals(FriendSource.UserDatabase, userDecoder.friendSource)
            assertFalse("db2.friends" in userDecoder.missingTables, userDecoder.missingTables.toString())

            val alice = assertIs<NormalUserData>(userDecoder.getUser(aliceId))
            assertEquals("Alice", alice.name)
            assertEquals("http://img/alice.jpg", alice.profileImageURL)
            assertEquals("카카오 채널", userDecoder.getUser(channelId)?.name)
            // The user's own row and the channel aren't friends.
            assertEquals(listOf(aliceId), userDecoder.getFriends().map(NormalUserData::id))
            assertEquals("Alice 외 1명", userDecoder.getRoom(groupId)?.name)

            userDecoder.start(scope, triggers) { events.send(it) }
            insertChat(groupId, aliceId, "before")
            triggers.emit(Unit)
            assertEquals("Alice", awaitEvents<KakaoEvent.MessageCreated>(1).single().chat.user?.name)

            // A change there invalidates the friends, and the room names made of them.
            userDb.exec("UPDATE user SET nickname = 'Alice2' WHERE id = $aliceId")
            insertChat(groupId, aliceId, "after")
            triggers.emit(Unit)
            val after = awaitEvents<KakaoEvent.MessageCreated>(1).single()
            assertEquals("Alice2", after.chat.user?.name)
            assertEquals("Alice2 외 1명", after.room.name)
        } finally {
            userDecoder.close()
            userDb.close()
        }
    }

    @Test
    // runBlocking<Unit>: the try block ends in assertIs, whose value would make the test non-void for JUnit.
    fun `keeps working when crypto_user_database can't be opened`() = runBlocking<Unit> {
        moveFriendsToUserDb().close()
        val failing = object : SessionFactory {
            override fun open(mainPath: String, subPath: String): ISqliteSession =
                JdbcSqliteSession.factory.open(mainPath, subPath)

            override fun openUserDatabase(dbDir: String): ISqliteSession =
                throw SessionException("wrong key")
        }
        val userDecoder = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), failing)
        try {
            userDecoder.open()
            val source = assertIs<FriendSource.UserDatabaseFailed>(userDecoder.friendSource)
            assertTrue("wrong key" in source.reason, source.reason)
            assertTrue("db2.friends" in userDecoder.missingTables, userDecoder.missingTables.toString())
            assertNull(userDecoder.getUser(aliceId))
            assertTrue(userDecoder.getFriends().isEmpty())

            userDecoder.start(scope, triggers) { events.send(it) }
            insertChat(groupId, aliceId, "안녕")
            triggers.emit(Unit)
            val created = awaitEvents<KakaoEvent.MessageCreated>(1).single()
            assertEquals("안녕", created.chat.message)
            assertIs<UnknownUserData>(created.chat.user)
        } finally {
            userDecoder.close()
        }
    }

    @Test
    fun `tells when crypto_user_database is needed but can't be read here`() = runBlocking {
        moveFriendsToUserDb().close()
        val userDecoder = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), JdbcSqliteSession.factory)
        try {
            userDecoder.open()
            assertEquals(FriendSource.UserDatabaseUnsupported, userDecoder.friendSource)
        } finally {
            userDecoder.close()
        }
    }

    @Test
    fun `fails to open without the database files`() {
        val empty = DatabaseDecoder(DecoderOptions(dir.resolve("missing").absolutePath), JdbcSqliteSession.factory)
        assertFailsWith<java.io.FileNotFoundException> { runBlocking { empty.open() } }
        assertFalse(empty.isOpen)
        empty.close()
    }

    @Test
    fun `emits every new chat once, in order, decrypted, with users and rooms`() = runBlocking {
        start()
        delay(200)
        assertTrue(drain().isEmpty(), "history before start must not be emitted")

        insertChat(groupId, aliceId, "안녕 1")
        insertChat(groupId, aliceId, "안녕 2")
        insertChat(openId, bobId, "오픈 메시지")
        insertChat(groupId, carolId, "친구 아님")
        triggers.emit(Unit)

        val created = awaitEvents<KakaoEvent.MessageCreated>(4)
        assertEquals(listOf("안녕 1", "안녕 2", "오픈 메시지", "친구 아님"), created.map { it.chat.message })

        val alice = created[0].chat.user
        assertIs<NormalUserData>(alice)
        assertEquals("Alice", alice.name)
        assertEquals("http://img/alice.jpg", alice.profileImageURL)
        assertEquals("Alice 외 1명", created[0].room.name)

        val bob = created[2].chat.user
        assertIs<OpenChatMemberData>(bob)
        assertEquals("Bob", bob.name)
        assertEquals(OpenMemberType.HOST, bob.memberType)
        assertEquals("테스트 오픈방", created[2].room.name)
        assertTrue(created[2].room.isOpenChat)
        assertEquals("https://open.kakao.com/o/test", created[2].room.openLink?.url)

        assertIs<UnknownUserData>(created[3].chat.user)

        // Every row is also reported raw.
        val raw = awaitEvents<KakaoEvent.LogCreated>(4)
        assertEquals(created.map { it.chat.pk }, raw.map { it.chat.pk })
    }

    @Test
    fun `reports own messages with the self user`() = runBlocking {
        start()
        insertChat(groupId, selfId, "내가 보냄", isMine = true)
        triggers.emit(Unit)
        val own = awaitEvents<KakaoEvent.MessageCreated>(1).single()
        assertIs<SelfUserData>(own.chat.user)
        assertTrue(own.chat.v.isMine)
    }

    @Test
    fun `friend changes in db2 invalidate cache`() = runBlocking {
        start()
        insertChat(groupId, aliceId, "before")
        triggers.emit(Unit)
        assertEquals("Alice", awaitEvents<KakaoEvent.MessageCreated>(1).single().chat.user?.name)

        sub.exec("UPDATE friends SET name = '${e(selfId, "Alice2")}' WHERE id = $aliceId")
        insertChat(groupId, aliceId, "after")
        triggers.emit(Unit)
        val after = awaitEvents<KakaoEvent.MessageCreated>(1).single()
        assertEquals("Alice2", after.chat.user?.name)
        assertEquals("Alice2 외 1명", after.room.name)
    }

    @Test
    fun `deletion feeds report the deleted message`() = runBlocking {
        start()
        val target = insertChat(groupId, aliceId, "지울 메시지")
        triggers.emit(Unit)
        awaitEvents<KakaoEvent.MessageCreated>(1)

        insertFeed(groupId, aliceId, """{"feedType":14,"logId":$target,"hidden":true}""")
        triggers.emit(Unit)

        val feed = awaitEvents<KakaoEvent.Feed>(1).single()
        assertEquals(14, feed.feed.feedTypeInt)
        val deleted = awaitEvents<KakaoEvent.MessageDeleted>(1).single()
        assertEquals(target, deleted.logId)
        assertEquals("지울 메시지", deleted.target?.message)
        assertEquals("Alice", deleted.target?.user?.name)
    }

    @Test
    fun `feeds become member and role events`() = runBlocking {
        start()
        insertFeed(groupId, aliceId, """{"feedType":1,"inviter":{"userId":$aliceId,"nickName":"Alice"},"members":[{"userId":$carolId,"nickName":"Carol"}]}""")
        insertFeed(openId, daveId, """{"feedType":4,"members":[{"userId":$daveId,"nickName":"Dave"}]}""")
        insertFeed(groupId, carolId, """{"feedType":2,"member":{"userId":$carolId,"nickName":"Carol"}}""")
        insertFeed(openId, bobId, """{"feedType":6,"member":{"userId":$daveId,"nickName":"Dave"}}""")
        insertFeed(openId, bobId, """{"feedType":11,"members":[{"userId":$daveId,"nickName":"Dave"}]}""")
        insertFeed(openId, bobId, """{"feedType":15,"prevHost":{"userId":$bobId,"nickName":"Bob"},"newHost":{"userId":$daveId,"nickName":"Dave"}}""")
        insertFeed(openId, bobId, """{"feedType":13,"logIds":[1,2],"hidden":true}""")
        triggers.emit(Unit)

        val joins = awaitEvents<KakaoEvent.MembersJoined>(2)
        assertEquals("Carol", joins[0].members.single().name)
        assertEquals("Alice", joins[0].inviter?.name)
        assertFalse(joins[0].isOpenLinkJoin)
        assertTrue(joins[1].isOpenLinkJoin)
        // Open chat members are resolved to the room's open profile.
        assertIs<OpenChatMemberData>(joins[1].members.single().user)

        val leaves = awaitEvents<KakaoEvent.MemberLeft>(2)
        assertEquals(carolId, leaves[0].member.userId)
        assertFalse(leaves[0].kicked)
        assertEquals("Carol", leaves[0].member.name) // unknown user: the feed's nickname
        assertTrue(leaves[1].kicked)

        val roles = awaitEvents<KakaoEvent.MemberRoleChanged>(2)
        assertEquals(KakaoEvent.RoleChange.MANAGER_GRANTED, roles[0].change)
        assertEquals(KakaoEvent.RoleChange.HOST_CHANGED, roles[1].change)
        assertEquals(daveId, roles[1].members.single().userId)
        assertEquals(bobId, roles[1].previousHost?.userId)

        val hidden = awaitEvents<KakaoEvent.MessageHidden>(1).single()
        assertEquals(listOf(1L, 2L), hidden.logIds)
    }

    @Test
    fun `query api exposes rooms, members, chats`() = runBlocking {
        val rooms = decoder.getRooms()
        assertEquals(listOf(groupId, openId, directId), rooms.map(RoomData::id))
        val direct = rooms.first { it.id == directId }
        assertEquals("Alice", direct.name)
        assertEquals("공지입니다", direct.notice)

        val members = decoder.getMembers(openId)
        assertIs<SelfUserData>(members.first())
        assertEquals(listOf("Bob", "Dave"), members.drop(1).map { it.name })

        repeat(5) { insertChat(groupId, aliceId, "m$it") }
        val page = decoder.getChats(groupId, limit = 3)
        assertEquals(listOf("m2", "m3", "m4"), page.map(ChatData::message))
        val older = decoder.getChats(groupId, beforePk = page.first().pk, limit = 10)
        assertEquals(listOf("옛날 메시지", "m0", "m1"), older.map(ChatData::message))
        assertIs<SelfUserData>(older.first().user)

        assertEquals("Alice", decoder.getFriends().single().name)
        assertEquals("테스트 오픈방", decoder.getOpenLinks().single().name)

        // Watermarks [9001, 0]: Alice read up to 9001, Carol nothing.
        val read = decoder.getReadState(groupId)!!
        assertEquals(1, read.unreadCount(9001))
        assertEquals(2, read.unreadCount(9002))
    }

    @Test
    fun `thread messages point at the message they answer`() = runBlocking {
        start()
        // What a 1:1 open chat stored when the other side answered the user's message: a TEXT row in a thread.
        val root = insertChat(openId, selfId, "봇 메시지", isMine = true)
        insertChat(openId, bobId, "답글", threadId = root, scope = 3)
        triggers.emit(Unit)

        val (rootChat, answer) = awaitEvents<KakaoEvent.MessageCreated>(2).map { it.chat }
        assertNull(rootChat.threadId)
        assertFalse(rootChat.isThreadMessage)
        assertEquals(1, rootChat.scope)

        assertEquals(root, answer.threadId)
        assertEquals(3, answer.scope)
        assertTrue(answer.isThreadMessage)
        assertNull(answer.reply)
        val answered = decoder.getChat(openId, answer.threadId!!)
        assertEquals("봇 메시지", answered?.message)
        assertIs<SelfUserData>(answered?.user)

        val json = kakaoJson.encodeToString(ChatData.serializer(), answer)
        assertTrue("\"threadId\":\"$root\"" in json, json)
    }

    @Test
    fun `a query failing halfway through a poll loses no messages`() = runBlocking {
        val factory = TestFactory()
        val flaky = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), factory)
        try {
            flaky.open()
            flaky.start(scope, triggers) { events.send(it) }
            // Fails once the new rows are read, while their senders are looked up: the poll must read them again.
            factory.failNext = { "IN (" in it }
            insertChat(groupId, aliceId, "하나")
            insertChat(groupId, carolId, "둘")
            insertChat(openId, bobId, "셋")
            triggers.emit(Unit)

            val created = awaitEvents<KakaoEvent.MessageCreated>(3)
            assertEquals(listOf("하나", "둘", "셋"), created.map { it.chat.message })
            assertNull(factory.failNext, "the lookup should have failed once")
            assertEquals(DecoderHealth.Ok, flaky.health.value)
            delay(300)
            assertTrue((skipped + drain()).none { it is KakaoEvent.MessageCreated }, "no message may come twice")
        } finally {
            flaky.close()
        }
    }

    @Test
    fun `recovers by itself when the connection can't be reopened at first`() = runBlocking {
        val factory = TestFactory()
        val options = DecoderOptions(dir.absolutePath, selfName = "나", reconnectDelayMillis = 100, maxReconnectDelayMillis = 200)
        val flaky = DatabaseDecoder(options, factory)
        try {
            flaky.open()
            flaky.start(scope, triggers) { events.send(it) }
            insertChat(groupId, aliceId, "전")
            triggers.emit(Unit)
            awaitEvents<KakaoEvent.MessageCreated>(1)

            // The root helper died, and can't be started again for now.
            factory.broken = true
            factory.failOpens = true
            insertChat(groupId, aliceId, "끊긴 동안")
            triggers.emit(Unit)
            withTimeout(5_000) { flaky.health.first { it is DecoderHealth.Lost } }
            assertFailsWith<SessionException> { runBlocking { flaky.getRooms() } }

            // Back. No trigger: the decoder tries again by itself, and reads what came meanwhile.
            factory.broken = false
            factory.failOpens = false
            assertEquals("끊긴 동안", awaitEvents<KakaoEvent.MessageCreated>(1).single().chat.message)
            assertEquals(DecoderHealth.Ok, flaky.health.value)
            assertEquals("Alice 외 1명", flaky.getRoom(groupId)?.name)
        } finally {
            flaky.close()
        }
    }

    /**
     * Newer KakaoTalk versions key open_chat_member by (user_id, link_id) and leave involved_chat_id 0 for most members:
     * looked up by room, the senders of a newly joined open chat were all unknown.
     */
    @Test
    fun `finds open chat members by their link when their row names no room`() = runBlocking {
        val erinId = 7_000_000_007L
        val frankId = 8_000_000_008L
        sub.exec("ALTER TABLE open_chat_member ADD COLUMN link_id INTEGER")
        sub.exec("UPDATE open_chat_member SET link_id = $linkId")
        for ((id, name) in listOf(erinId to "Erin", frankId to "Frank"))
            sub.exec("""INSERT INTO open_chat_member (user_id, link_id, link_member_type, nickname, involved_chat_id, enc)
                VALUES ($id, $linkId, ${OpenMemberType.NORMAL.typeInt}, '${e(selfId, name)}', 0, $enc)""")
        // Frank has left: still a row of the link, no longer among the room's active members.
        main.exec("UPDATE chat_rooms SET members = '[$bobId,$daveId,$erinId,$frankId]', active_member_ids = '[$bobId,$erinId]' WHERE id = $openId")

        // The schema is read when a decoder opens.
        val newer = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), JdbcSqliteSession.factory)
        try {
            newer.open()
            newer.start(scope, triggers) { events.send(it) }
            insertChat(openId, erinId, "새로 왔어요")
            insertChat(openId, bobId, "어서 와요")
            triggers.emit(Unit)

            val (erin, bob) = awaitEvents<KakaoEvent.MessageCreated>(2).map { it.chat.user }
            assertIs<OpenChatMemberData>(erin)
            assertEquals("Erin", erin.name)
            assertEquals(linkId, erin.linkId)
            assertEquals(0L, erin.chatId)
            assertIs<OpenChatMemberData>(bob)
            assertEquals("Bob", bob.name)

            assertEquals("Erin", (newer.getUser(erinId, openId) as OpenChatMemberData).name)
            // The members in the room now, with their open profiles.
            assertEquals(listOf("나", "Bob", "Erin"), newer.getMembers(openId).map { it.name })
        } finally {
            newer.close()
        }
    }

    /** In root mode every query is a round trip to the helper: the frequent checks must take few. */
    @Test
    fun `checks take few queries`() = runBlocking {
        val factory = TestFactory()
        val counted = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), factory)
        try {
            counted.open()
            counted.start(scope, triggers) { events.send(it) }
            insertChat(groupId, aliceId, "방과 친구를 캐시에")
            triggers.emit(Unit)
            awaitEvents<KakaoEvent.MessageCreated>(1)
            delay(100)

            // Nothing committed (another file changed, a poll tick, the settle check): one query.
            factory.statements.clear()
            triggers.emit(Unit)
            delay(100)
            assertEquals(listOf("PRAGMA main.data_version"), factory.statements.toList())

            // A new message from someone known: the data versions, then the rows.
            factory.statements.clear()
            insertChat(groupId, aliceId, "새 메시지")
            triggers.emit(Unit)
            awaitEvents<KakaoEvent.MessageCreated>(1)
            delay(50)
            val statements = factory.statements.toList()
            assertEquals(3, statements.size, statements.toString())
            assertEquals(listOf("PRAGMA main.data_version", "PRAGMA db2.data_version"), statements.take(2))
        } finally {
            counted.close()
        }
    }

    /** The root helper refuses other statements (ServedSql): every one the decoder makes must pass, or root mode breaks. */
    @Test
    fun `every statement is one the root helper serves`() = runBlocking {
        val factory = TestFactory()
        val audited = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), factory)
        try {
            audited.open()
            audited.start(scope, triggers) { events.send(it) }
            val target = insertChat(groupId, aliceId, "지울 메시지")
            insertChat(openId, bobId, "오픈")
            insertFeed(groupId, aliceId, """{"feedType":14,"logId":$target,"hidden":true}""")
            insertFeed(openId, bobId, """{"feedType":13,"logIds":[$target],"hidden":true}""")
            insertFeed(openId, bobId, """{"feedType":4,"members":[{"userId":$daveId,"nickName":"Dave"}]}""")
            triggers.emit(Unit)
            awaitEvents<KakaoEvent.MessageDeleted>(1)
            awaitEvents<KakaoEvent.MessageHidden>(1)
            awaitEvents<KakaoEvent.MembersJoined>(1)
            audited.getRooms()
            audited.getMembers(openId)
            audited.getMembers(groupId)
            audited.getChats(groupId, limit = 5)
            audited.getChat(groupId, target)
            audited.getReadState(groupId)
            audited.getFriends()
            audited.getOpenLinks()
            audited.getUser(bobId, openId)
            audited.getUser(aliceId)
        } finally {
            audited.close()
        }

        // And with the friend list in crypto_user_database.
        skipped.clear()
        drain()
        moveFriendsToUserDb().close()
        val withUserDb = DatabaseDecoder(DecoderOptions(dir.absolutePath, selfName = "나"), factory)
        try {
            withUserDb.open()
            assertEquals(FriendSource.UserDatabase, withUserDb.friendSource)
            withUserDb.start(scope, triggers) { events.send(it) }
            insertChat(groupId, aliceId, "친구 목록은 따로")
            triggers.emit(Unit)
            assertEquals("Alice", awaitEvents<KakaoEvent.MessageCreated>(1).single().chat.user?.name)
            withUserDb.getFriends()
            withUserDb.getUser(channelId)
            withUserDb.getRooms()
        } finally {
            withUserDb.close()
        }

        val statements = factory.statements.toList()
        assertTrue(statements.size > 20, statements.toString())
        assertEquals(emptyList(), statements.filterNot(ServedSql::isServed).distinct())
    }

    @Test
    fun `serializes ids as strings`() = runBlocking {
        val json = kakaoJson.encodeToString(RoomData.serializer(), decoder.getRoom(groupId)!!)
        assertTrue("\"id\":\"$groupId\"" in json, json)
    }

    // ------------------------------------------------------------------ helpers

    private fun e(userId: Long, text: String) = KakaoCrypto.encrypt(userId, enc, text)

    /**
     * What newer KakaoTalk versions do: no db2.friends, the friend list in crypto_user_database (plain here, see
     * JdbcSqliteSession.factoryWithUserDb), with the columns Iris reads. Returns the writer of that database.
     */
    private fun moveFriendsToUserDb(): Connection {
        sub.exec("DROP TABLE friends")
        val userDb = DriverManager.getConnection("jdbc:sqlite:${dir.resolve(DecoderOptions.USER_DB)}")
        userDb.exec("PRAGMA journal_mode=WAL")
        userDb.exec("CREATE TABLE user (id INTEGER PRIMARY KEY, nickname TEXT, profile_image_url TEXT, status_message TEXT)")
        userDb.exec("CREATE TABLE talk_channel (id INTEGER PRIMARY KEY, name TEXT)")
        userDb.exec("INSERT INTO user (id, nickname) VALUES ($selfId, '나')")
        userDb.exec("INSERT INTO user (id, nickname, profile_image_url) VALUES ($aliceId, 'Alice', 'http://img/alice.jpg')")
        userDb.exec("INSERT INTO talk_channel (id, name) VALUES ($channelId, '카카오 채널')")
        return userDb
    }

    private fun insertFriend(id: Long, name: String, image: String) {
        sub.exec("""INSERT INTO friends (id, name, original_profile_image_url, board_v, favorite, enc, created_at)
            VALUES ($id, '${e(selfId, name)}', '${e(selfId, image)}', '${e(selfId, "{}")}', 1, $enc, 1)""")
    }

    private fun insertOpenMember(id: Long, name: String, type: OpenMemberType) {
        sub.exec("""INSERT INTO open_chat_member (user_id, link_member_type, nickname, original_profile_image_url, involved_chat_id, enc)
            VALUES ($id, ${type.typeInt}, '${e(selfId, name)}', '${e(selfId, "http://img/$name.jpg")}', $openId, $enc)""")
    }

    private fun insertFeed(chatId: Long, userId: Long, json: String): Long =
        insertChat(chatId, userId, json, type = 0)

    /** @return the server log id */
    private fun insertChat(
        chatId: Long, userId: Long, message: String, type: Int = 1, isMine: Boolean = false,
        threadId: Long? = null, scope: Int = 1,
    ): Long {
        val logId = nextLogId++
        val v = """{"enc":$enc,"origin":"MSG","isMine":$isMine,"c":"x"}"""
        main.prepareStatement(
            "INSERT INTO chat_logs (id, type, chat_id, user_id, message, attachment, created_at, deleted_at, client_message_id, prev_id, referer, v, thread_id, scope) VALUES (?,?,?,?,?,?,?,0,0,0,0,?,?,?)"
        ).use {
            it.setLong(1, logId); it.setInt(2, type); it.setLong(3, chatId); it.setLong(4, userId)
            it.setString(5, KakaoCrypto.encrypt(userId, enc, message)); it.setString(6, "{}")
            it.setLong(7, logId); it.setString(8, v)
            if (threadId == null) it.setNull(9, java.sql.Types.INTEGER) else it.setLong(9, threadId)
            it.setInt(10, scope)
            it.executeUpdate()
        }
        return logId
    }

    private fun drain(): List<KakaoEvent> = buildList {
        while (true) add(events.tryReceive().getOrNull() ?: break)
    }

    private val skipped = arrayListOf<KakaoEvent>()

    /** Waits for [count] events of type [T], keeping the others for later calls. */
    private suspend inline fun <reified T : KakaoEvent> awaitEvents(count: Int): List<T> =
        withTimeout(5_000) {
            val out = arrayListOf<T>()
            val iterator = skipped.iterator()
            while (out.size < count && iterator.hasNext()) {
                val event = iterator.next()
                if (event is T) { out += event; iterator.remove() }
            }
            while (out.size < count) {
                val event = events.receive()
                if (event is T) out += event else skipped += event
            }
            out
        }

    private fun Connection.exec(sql: String) =
        createStatement().use { it.execute(sql) }

    /**
     * [JdbcSqliteSession.factoryWithUserDb] with failures to order: opening can fail, every query of the open sessions
     * can fail (the connection broke), or the next query matching [failNext]. It records every statement, as the root
     * session sends them.
     */
    private class TestFactory : SessionFactory {

        @Volatile var failOpens = false
        @Volatile var broken = false
        @Volatile var failNext: ((String) -> Boolean)? = null

        val statements: MutableList<String> = Collections.synchronizedList(arrayListOf())

        override fun open(mainPath: String, subPath: String): ISqliteSession {
            if (failOpens)
                throw SessionException("the root helper is down")
            return Session(JdbcSqliteSession.factoryWithUserDb.open(mainPath, subPath))
        }

        override fun openUserDatabase(dbDir: String): ISqliteSession? =
            JdbcSqliteSession.factoryWithUserDb.openUserDatabase(dbDir)?.let(::Session)

        private inner class Session(private val base: ISqliteSession) : ISqliteSession by base {

            private fun send(sql: String) {
                statements += sql
                if (broken)
                    throw SessionException("the connection broke")
                val predicate = failNext
                if (predicate != null && predicate(sql)) {
                    failNext = null
                    throw SessionException("failed: $sql")
                }
            }

            override fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> {
                send(sql)
                return base.query(sql, *args, mapper = mapper)
            }

            override fun <T> queryFirst(sql: String, vararg args: Any?, mapper: (Row) -> T): T? {
                send(sql)
                return base.queryFirst(sql, *args, mapper = mapper)
            }

            // As RootSqliteSession (and AndroidSqliteSession) write them.
            override fun dataVersion(schema: String): Long {
                send("PRAGMA $schema.data_version")
                return base.dataVersion(schema)
            }

            override fun columnsOf(schema: String, table: String): Set<String> {
                send("PRAGMA $schema.table_info($table)")
                return base.columnsOf(schema, table)
            }
        }
    }
}
