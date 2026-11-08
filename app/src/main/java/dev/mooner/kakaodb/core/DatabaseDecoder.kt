package dev.mooner.kakaodb.core

import dev.mooner.kakaodb.core.crypto.KakaoCrypto
import dev.mooner.kakaodb.core.model.*
import dev.mooner.kakaodb.core.parser.*
import dev.mooner.kakaodb.core.schema.KakaoDatabase
import dev.mooner.kakaodb.core.schema.KakaoSchema
import dev.mooner.kakaodb.core.schema.KakaoTable
import dev.mooner.kakaodb.core.sqlite.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads the KakaoTalk database, detects new rows and turns them into [KakaoEvent]s.
 *
 * - Every database access runs on one dedicated thread: the session and the cache are not thread-safe.
 * - One read-only connection stays open, and `PRAGMA data_version` tells whether anything was committed.
 * - New messages are read as `_id > lastSeenPk` (rowid index; no count or OFFSET).
 */
class DatabaseDecoder(
    private val options: DecoderOptions,
    private val sessionFactory: SessionFactory,
    private val logger: CoreLogger = CoreLogger.None,
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kakaodb-decoder").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    val mainDBFile: File = File(options.baseDBPath, DecoderOptions.MAIN_DB)
    val subDBFile : File = File(options.baseDBPath, DecoderOptions.SUB_DB)

    // Accessed on the dispatcher only.
    private var mSession: ISqliteSession? = null
    private lateinit var schema: KakaoSchema
    private val cache = DataCache(options.roomCacheTtlMillis)
    private var lastSeenPk: Long = 0L
    private var lastMainVersion: Long = -1L
    private var lastDb2Version : Long = -1L

    // crypto_user_database, while it holds the friend list (see openUserDatabase). Accessed on the dispatcher only.
    private var mUserSession: ISqliteSession? = null
    private var userSchema: KakaoSchema? = null
    private var lastUserDbVersion: Long = -1L
    private var userDbRetryAt: Long = 0L

    @Volatile
    private var closed = false
    private var pollJob: Job? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    @Volatile
    var selfID: Long = -1L
        private set

    @Volatile
    var selfName: String = ""
        private set

    /** Tables this KakaoTalk version doesn't have (known after [open]). */
    @Volatile
    var missingTables: List<String> = emptyList()
        private set

    /** Where the friend list comes from (known after [open]; changes when crypto_user_database fails or recovers). */
    @Volatile
    var friendSource: FriendSource = FriendSource.Db2
        private set

    private val mHealth = MutableStateFlow<DecoderHealth>(DecoderHealth.Ok)

    /**
     * Whether the connection works. When it breaks and can't be reopened, this is [DecoderHealth.Lost] until a later
     * attempt succeeds; the watch started by [start] keeps trying on its own.
     */
    val health: StateFlow<DecoderHealth> = mHealth.asStateFlow()

    /** Failed reopen attempts in a row. Accessed on the dispatcher only. */
    private var reconnectFailures = 0

    private val session: ISqliteSession
        get() = mSession ?: throw IllegalStateException("Database is not open")

    private val selfUser: SelfUserData
        get() = SelfUserData(selfID, selfName)

    private val chatLogs      get() = schema[KakaoTable.CHAT_LOGS]
    private val chatRooms     get() = schema[KakaoTable.CHAT_ROOMS]
    private val friends       get() = schema[KakaoTable.FRIENDS]
    private val openLinks     get() = schema[KakaoTable.OPEN_LINK]
    private val openProfiles  get() = schema[KakaoTable.OPEN_PROFILE]
    private val openMembers   get() = schema[KakaoTable.OPEN_CHAT_MEMBER]

    // ================================================================ lifecycle

    /**
     * Opens the database and resolves the user. Throws if it can't be opened.
     * Rows already in the database are history: only rows added after this are reported.
     */
    suspend fun open() {
        withContext(dispatcher) {
            check(!closed) { "Decoder is closed" }
            if (mSession != null)
                return@withContext
            openSession()
            lastSeenPk = currentMaxPk()
            val (id, name) = resolveSelf()
            selfID = id
            selfName = name
            isOpen = true
            logger.info { "Decoder opened: self=$selfName($selfID), lastSeenPk=$lastSeenPk" }
        }
    }

    /**
     * Starts watching: each value of [triggers] checks the database for changes, and found events go to [sink] in
     * order. Triggers that arrive while a check runs are merged into one (conflate); a check reads everything since
     * the last one, so nothing is lost.
     */
    fun start(scope: CoroutineScope, triggers: Flow<*>, sink: suspend (KakaoEvent) -> Unit): Job {
        pollJob?.cancel()
        return scope.launch {
            merge(flowOf(Unit), triggers, reconnectTicks())
                .conflate()
                .collect {
                    val events = try {
                        db { pollChanges() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // While the connection is lost, connectionLost() already told why and when it retries.
                        if (mHealth.value !is DecoderHealth.Lost)
                            logger.error(e) { "Failed to process database changes: $e" }
                        emptyList()
                    }
                    for (event in events) {
                        try {
                            sink(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to handle ${event::class.simpleName}: $e" }
                        }
                    }
                    // Triggers coming meanwhile are merged (conflate) and checked once this is over.
                    delay(options.minPollSpacingMillis)
                }
        }.also { pollJob = it }
    }

    override fun close() {
        if (closed)
            return
        closed = true
        isOpen = false
        pollJob?.cancel()
        runCatching {
            executor.submit {
                runCatching { mSession?.close() }
                mSession = null
                closeUserDatabase()
            }.get(5, TimeUnit.SECONDS)
        }
        executor.shutdown()
    }

    /**
     * One tick when the next reopen attempt is due, while the connection is lost: file changes may not come then (a
     * root helper that died doesn't watch either), and polling may be off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun reconnectTicks(): Flow<Unit> =
        mHealth.flatMapLatest { health ->
            if (health !is DecoderHealth.Lost)
                emptyFlow()
            else
                flow {
                    // A little late rather than early: an attempt before retryAt would be skipped.
                    delay((health.retryAt - System.currentTimeMillis()).coerceAtLeast(0L) + RECONNECT_TICK_MARGIN_MILLIS)
                    emit(Unit)
                }
        }

    // ================================================================ public queries

    fun getSelf(): SelfUserData = selfUser

    suspend fun getRoom(roomId: Long): RoomData? =
        cachedDb { room(roomId) }

    /** Every room, most recently active first. */
    suspend fun getRooms(): List<RoomData> =
        cachedDb { allRooms() }

    suspend fun getFriends(): List<NormalUserData> =
        cachedDb { allFriends() }

    /** A user; with [roomId] of an open chat, that room's open profile comes first. */
    suspend fun getUser(userId: Long, roomId: Long? = null): UserData? =
        cachedDb { resolveUserOrNull(userId, roomId?.let(::room)) }

    suspend fun getMembers(roomId: Long): List<UserData> =
        cachedDb { members(roomId) }

    suspend fun getOpenLinks(): List<OpenLinkData> =
        cachedDb { allOpenLinks() }

    /** Up to [limit] messages before [beforePk] (chat_logs._id), oldest first. */
    suspend fun getChats(roomId: Long, beforePk: Long? = null, limit: Int = 50): List<ChatData> =
        cachedDb { chats(roomId, beforePk ?: Long.MAX_VALUE, limit) }

    suspend fun getChat(roomId: Long, logId: Long): ChatData? =
        cachedDb { chatByLogId(roomId, logId) }

    /** Read watermarks of a room, read fresh (the cached room may be minutes old). */
    suspend fun getReadState(roomId: Long): ReadState? =
        db { readState(roomId) }

    // ================================================================ change detection

    /**
     * Reads what was added since the last poll. Every query here is a round trip to the root helper in root mode, so
     * the common cases take few: nothing committed to KakaoTalk.db is one query, a new message four (data versions,
     * then the rows).
     */
    private fun pollChanges(): List<KakaoEvent> {
        val mainVersion = session.dataVersion("main")
        if (mainVersion == lastMainVersion)
            return emptyList()
        if (!chatLogs.available) {
            lastMainVersion = mainVersion
            return emptyList()
        }
        // The new rows' senders and rooms come from the cache: drop first what changed in the other databases.
        refreshCaches()

        // Where this poll has read up to. It becomes lastSeenPk (and mainVersion lastMainVersion) only once every row
        // is read and turned into events: when a query fails halfway, the retry after reconnecting (or the next poll)
        // reads them all again. Nothing is emitted before this returns, so nothing comes twice.
        var cursor = lastSeenPk
        val events: MutableList<KakaoEvent> = arrayListOf()
        val sql = chatLogs.select("_id > ?", "ORDER BY _id LIMIT ?")
        while (true) {
            val rows = session.query(sql, cursor, options.batchSize, mapper = ::readLog)
            if (rows.isEmpty())
                break
            cursor = rows.last().pk
            val chats = rows.mapNotNull(LogRow::chat)
            prefetchFriends(chats.map(ChatData::userId))
            for (chat in chats)
                collectEventsOrSkip(chat, events)
            if (rows.size < options.batchSize)
                break
        }

        if (cursor == lastSeenPk) {
            // Nothing new: KakaoTalk committed something else (rooms, read receipts...), or the table was emptied.
            val maxPk = currentMaxPk()
            if (maxPk < lastSeenPk) {
                logger.warn { "chat_logs max _id went backwards ($lastSeenPk -> $maxPk). Was the database reset?" }
                cursor = maxPk
                cache.invalidateAll()
            }
        }
        lastSeenPk = cursor
        lastMainVersion = mainVersion
        return events
    }

    /**
     * Drops cached friends, open chat members, links and rooms when KakaoTalk2.db, or crypto_user_database while it
     * holds the friend list, was committed to since the last look. Runs before new rows are turned into events, and
     * before queries: nothing reads the cache in between, so there is no need to look on every poll.
     */
    private fun refreshCaches() {
        val db2Version = session.dataVersion("db2")
        if (db2Version != lastDb2Version) {
            if (lastDb2Version != -1L) {
                logger.debug { "KakaoTalk2.db changed ($lastDb2Version -> $db2Version), invalidating cache" }
                cache.invalidateDb2()
            }
            lastDb2Version = db2Version
        }
        if (!friends.available)
            checkUserDatabase()
    }

    /** A chat_logs row; [chat] is null when the row can't be read, which mustn't hold up the rows after it. */
    private class LogRow(val pk: Long, val chat: ChatData?)

    private fun readLog(row: Row): LogRow {
        val pk = row.long("_id") ?: 0L
        return try {
            LogRow(pk, row.toChatData())
        } catch (e: SessionException) {
            throw e
        } catch (e: RuntimeException) {
            logger.error(e) { "Skipping chat_logs row $pk, which can't be read: $e" }
            LogRow(pk, null)
        }
    }

    /**
     * [collectEvents], or nothing for [chat] when that fails for a reason of its own (a bug, odd data), which mustn't
     * hold up the rows after it either. A broken connection still fails the poll, to be read again.
     */
    private fun collectEventsOrSkip(chat: ChatData, out: MutableList<KakaoEvent>) {
        val mark = out.size
        try {
            collectEvents(chat, out)
        } catch (e: SessionException) {
            throw e
        } catch (e: RuntimeException) {
            while (out.size > mark)
                out.removeAt(out.lastIndex)
            logger.error(e) { "Skipping chat_logs row ${chat.pk} (log ${chat.id}): $e" }
        }
    }

    private fun collectEvents(chat: ChatData, out: MutableList<KakaoEvent>) {
        val feed = chat.feed
        if (feed != null) {
            // A feed changes who is in the room: drop that room and its affected members only.
            cache.invalidateRoom(chat.chatId)
            for (member in feed.allMembers)
                cache.invalidateOpenMember(chat.chatId, member.userId)
        }

        val room = room(chat.chatId) ?: fallbackRoom(chat.chatId)
        chat.user = resolveUser(chat.userId, room)
        logger.debug { "[${room.name}] ${chat.user?.name}(${chat.userId}): ${chat.message}" }

        out += KakaoEvent.LogCreated(chat, room)
        if (chat.type != ChatType.FEED) {
            out += KakaoEvent.MessageCreated(chat, room)
            return
        }
        if (feed == null) {
            logger.debug { "Unreadable feed in ${room.name}: ${chat.message}" }
            return
        }
        out += KakaoEvent.Feed(chat, room, feed)
        collectFeedEvents(chat, room, feed, out)
    }

    private fun collectFeedEvents(chat: ChatData, room: RoomData, feed: FeedData, out: MutableList<KakaoEvent>) {
        fun membersOf(list: List<FeedData.FeedMember>) =
            list.distinctBy(FeedData.FeedMember::userId).map { member(it, room) }

        when (val type = feed.feedType) {
            FeedType.DELETE_TO_ALL -> {
                val logId = feed.logId ?: return
                out += KakaoEvent.MessageDeleted(chat, room, logId, chatByLogId(chat.chatId, logId))
            }
            FeedType.OPENLINK_REWRITE_FEED -> {
                val ids = feed.targetLogIds
                out += KakaoEvent.MessageHidden(chat, room, ids, ids.mapNotNull { chatByLogId(chat.chatId, it) })
            }
            FeedType.INVITE, FeedType.OPENLINK_JOIN -> {
                val joined = membersOf(feed.members + listOfNotNull(feed.member))
                if (joined.isNotEmpty())
                    out += KakaoEvent.MembersJoined(
                        chat, room, joined,
                        inviter = feed.inviter?.let { member(it, room) },
                        isOpenLinkJoin = type == FeedType.OPENLINK_JOIN,
                    )
            }
            FeedType.LEAVE, FeedType.SECRET_LEAVE, FeedType.LOCAL_LEAVE,
            FeedType.OPENLINK_KICKED, FeedType.CHANNEL_KICKED -> {
                val target = feed.member
                    ?: feed.members.firstOrNull()
                    ?: FeedData.FeedMember(if (type == FeedType.LOCAL_LEAVE) selfID else chat.userId)
                val kicked = feed.kicked || type == FeedType.OPENLINK_KICKED || type == FeedType.CHANNEL_KICKED
                out += KakaoEvent.MemberLeft(chat, room, member(target, room), kicked)
            }
            FeedType.OPEN_MANAGER_GRANT, FeedType.OPEN_MANAGER_REVOKE -> {
                val targets = membersOf(feed.members + listOfNotNull(feed.member))
                val change = if (type == FeedType.OPEN_MANAGER_GRANT)
                    KakaoEvent.RoleChange.MANAGER_GRANTED
                else
                    KakaoEvent.RoleChange.MANAGER_REVOKED
                if (targets.isNotEmpty())
                    out += KakaoEvent.MemberRoleChanged(chat, room, change, targets, previousHost = null)
            }
            FeedType.OPENLINK_HAND_OVER_HOST -> {
                val newHost = feed.newHost ?: feed.member ?: feed.members.firstOrNull()
                out += KakaoEvent.MemberRoleChanged(
                    chat, room, KakaoEvent.RoleChange.HOST_CHANGED,
                    members = listOfNotNull(newHost).map { member(it, room) },
                    previousHost = feed.prevHost?.let { member(it, room) },
                )
            }
            else -> {}
        }
    }

    private fun member(member: FeedData.FeedMember, room: RoomData): KakaoEvent.Member {
        val nickName = member.nickName?.takeIf(String::isNotBlank)
        val user = resolveUserOrNull(member.userId, room)
            ?: UnknownUserData(member.userId, nickName ?: "Unknown(${member.userId})")
        return KakaoEvent.Member(member.userId, nickName, user)
    }

    // ================================================================ chats

    private fun currentMaxPk(): Long =
        if (!chatLogs.available) 0L
        else session.queryFirst("SELECT MAX(_id) FROM ${chatLogs.from}") { it.long(0) } ?: 0L

    private fun chats(roomId: Long, beforePk: Long, limit: Int): List<ChatData> {
        if (!chatLogs.available)
            return emptyList()
        val chats = session.query(
            chatLogs.select("chat_id = ? AND _id < ?", "ORDER BY _id DESC LIMIT ?"),
            roomId, beforePk, limit.coerceIn(1, 1000),
        ) { it.toChatData() }.asReversed()

        val room = room(roomId)
        prefetchFriends(chats.map(ChatData::userId))
        for (chat in chats)
            chat.user = resolveUser(chat.userId, room)
        return chats
    }

    private fun chatByLogId(roomId: Long, logId: Long): ChatData? {
        if (!chatLogs.available)
            return null
        val chat = session.queryFirst(chatLogs.select("chat_id = ? AND id = ?", "LIMIT 1"), roomId, logId) { it.toChatData() }
            ?: return null
        chat.user = resolveUser(chat.userId, room(roomId))
        return chat
    }

    private fun readState(roomId: Long): ReadState? {
        if (!chatRooms.has("watermarks") || !chatRooms.has("active_member_ids"))
            return null
        return session.queryFirst(
            "SELECT active_member_ids, watermarks FROM ${chatRooms.from} WHERE id = ? LIMIT 1",
            roomId,
        ) { row -> ReadState(parseIdList(row.string("active_member_ids")), parseIdList(row.string("watermarks"))) }
    }

    // ================================================================ users

    private fun resolveUser(userId: Long, room: RoomData?): UserData =
        resolveUserOrNull(userId, room) ?: UnknownUserData(userId)

    private fun resolveUserOrNull(userId: Long, room: RoomData?): UserData? {
        if (userId == selfID)
            return selfUser
        // In open chats, the room's open profile comes first.
        if (room != null && room.isOpenChat)
            openMember(room, userId)?.let { return it }
        return friend(userId)
    }

    private fun friend(userId: Long): NormalUserData? {
        if (friends.available)
            return cache.friend(userId) { id ->
                session.queryFirst(friends.select("id = ?", "LIMIT 1"), id) { it.toFriend(selfID) }
            }
        if (cache.hasFriend(userId))
            return cache.cachedFriend(userId)
        // Not cached when crypto_user_database can't be read: the next lookup tries again.
        val found = fromUserDb { userDb, tables -> Found(userDbFriend(userDb, tables, userId)) }
            ?: return null
        cache.putFriend(userId, found.value)
        return found.value
    }

    /** Reads friends missing from the cache with one IN query, instead of one query per user. */
    private fun prefetchFriends(userIds: Collection<Long>) {
        val missing = userIds.asSequence()
            .distinct()
            .filter { it != selfID && !cache.hasFriend(it) }
            .toList()
        if (missing.isEmpty())
            return
        if (!friends.available) {
            fromUserDb { userDb, tables -> prefetchUserDbFriends(userDb, tables, missing) }
            return
        }
        for (chunk in missing.chunked(500)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            val found = session.query(friends.select("id IN ($placeholders)"), *chunk.toTypedArray()) { it.toFriend(selfID) }
                .filterNotNull()
                .associateBy(NormalUserData::id)
            for (id in chunk)
                cache.putFriend(id, found[id])
        }
    }

    private fun allFriends(): List<NormalUserData> {
        if (cache.allFriendsLoaded)
            return cache.cachedFriends()
        val all = if (friends.available)
            session.query(friends.select()) { it.toFriend(selfID) }.filterNotNull()
        else
            // `user` may hold more than the friend list: the columns logged at open may tell them apart.
            fromUserDb { userDb, tables ->
                userDb.query(tables[KakaoTable.USER].select()) { it.toUserDbFriend(selfID) }
                    .filterNotNull()
                    .filter { it.id != selfID }
            } ?: return emptyList()
        cache.putAllFriends(all)
        return all
    }

    // ================================================================ crypto_user_database

    /**
     * Opens crypto_user_database when the friend list is there: newer KakaoTalk versions dropped db2.friends for it.
     * A failure only costs the friends' names (they show as unknown users) and is retried after
     * [USER_DB_RETRY_MILLIS].
     */
    private fun openUserDatabase() {
        closeUserDatabase()
        if (friends.available) {
            friendSource = FriendSource.Db2
            return
        }
        val userDb = try {
            sessionFactory.openUserDatabase(options.baseDBPath)
        } catch (e: Exception) {
            return userDatabaseFailed("Couldn't open ${DecoderOptions.USER_DB}", e)
        }
        if (userDb == null) {
            friendSource = FriendSource.UserDatabaseUnsupported
            logger.warn { "db2.friends is missing and ${DecoderOptions.USER_DB} can't be read here: friends show as unknown users" }
            return
        }
        val tables = try {
            KakaoSchema.resolve(userDb, logger, KakaoDatabase.USER)
        } catch (e: Exception) {
            runCatching { userDb.close() }
            return userDatabaseFailed("Couldn't read the schema of ${DecoderOptions.USER_DB}", e)
        }
        val users = tables[KakaoTable.USER]
        if (!users.available) {
            runCatching { userDb.close() }
            return userDatabaseFailed("${KakaoTable.USER.label} doesn't exist", null)
        }
        mUserSession = userDb
        userSchema = tables
        lastUserDbVersion = -1L
        friendSource = FriendSource.UserDatabase
        // Names may have changed while it couldn't be read.
        cache.invalidateFriends()
        // Most of its columns are guesses (see KakaoTable.USER): what is really there helps to map them.
        logger.info { "Friends are read from ${KakaoTable.USER.label}, columns: ${users.columns.sorted()}" }
    }

    private fun userDatabaseFailed(message: String, error: Exception?) {
        val reason = if (error == null) message else "$message: ${error.message ?: error}"
        friendSource = FriendSource.UserDatabaseFailed(reason)
        userDbRetryAt = System.currentTimeMillis() + USER_DB_RETRY_MILLIS
        logger.warn(error) { reason }
    }

    private fun closeUserDatabase() {
        runCatching { mUserSession?.close() }
        mUserSession = null
        userSchema = null
    }

    /**
     * What [block] reads from crypto_user_database, or null when it can't be read. Its errors don't propagate (they
     * would make [db] reconnect the main session): they close it, to be opened again after [USER_DB_RETRY_MILLIS].
     */
    private inline fun <T : Any> fromUserDb(block: (ISqliteSession, KakaoSchema) -> T): T? {
        if (mUserSession == null && friendSource is FriendSource.UserDatabaseFailed
            && System.currentTimeMillis() >= userDbRetryAt) {
            logger.info { "Opening ${DecoderOptions.USER_DB} again" }
            openUserDatabase()
        }
        val userDb = mUserSession ?: return null
        val tables = userSchema ?: return null
        return try {
            block(userDb, tables)
        } catch (e: SessionException) {
            closeUserDatabase()
            userDatabaseFailed("Reading ${DecoderOptions.USER_DB} failed", e)
            null
        }
    }

    private fun userDbFriend(userDb: ISqliteSession, tables: KakaoSchema, userId: Long): NormalUserData? {
        val users = tables[KakaoTable.USER]
        val channels = tables[KakaoTable.TALK_CHANNEL]
        return userDb.queryFirst(users.select("id = ?", "LIMIT 1"), userId) { it.toUserDbFriend(selfID) }
            ?: if (channels.available)
                userDb.queryFirst(channels.select("id = ?", "LIMIT 1"), userId) { it.toTalkChannelUser() }
            else null
    }

    private fun prefetchUserDbFriends(userDb: ISqliteSession, tables: KakaoSchema, ids: List<Long>) {
        val users = tables[KakaoTable.USER]
        val channels = tables[KakaoTable.TALK_CHANNEL]
        for (chunk in ids.chunked(500)) {
            val found = HashMap<Long, NormalUserData>()
            userDb.query(users.select("id IN (${placeholders(chunk.size)})"), *chunk.toTypedArray()) { it.toUserDbFriend(selfID) }
                .filterNotNull()
                .associateByTo(found, NormalUserData::id)
            val rest = chunk.filter { it !in found }
            if (rest.isNotEmpty() && channels.available)
                userDb.query(channels.select("id IN (${placeholders(rest.size)})"), *rest.toTypedArray()) { it.toTalkChannelUser() }
                    .filterNotNull()
                    .associateByTo(found, NormalUserData::id)
            for (id in chunk)
                cache.putFriend(id, found[id])
        }
    }

    /** crypto_user_database changes when friends do; their names also make rooms' names. */
    private fun checkUserDatabase() {
        val version = fromUserDb { userDb, _ -> userDb.dataVersion("main") } ?: return
        if (version != lastUserDbVersion) {
            if (lastUserDbVersion != -1L) {
                logger.debug { "${DecoderOptions.USER_DB} changed ($lastUserDbVersion -> $version), invalidating friends" }
                cache.invalidateFriends()
            }
            lastUserDbVersion = version
        }
    }

    private fun placeholders(count: Int): String =
        List(count) { "?" }.joinToString(", ")

    /** Tells "found nothing" (a value of null) apart from "couldn't look" (no Found at all). */
    private class Found<T>(val value: T)

    /** The room's open link, when members can be looked up by it (see [KakaoTable.OPEN_CHAT_MEMBER]). */
    private fun memberLinkOf(room: RoomData): Long? =
        room.linkId?.takeIf { it > 0 && openMembers.has("link_id") }

    /**
     * [userId]'s profile in the open chat [room]. Newer KakaoTalk versions key open_chat_member by (user_id, link_id)
     * and leave involved_chat_id 0 for most members, so the link comes first; involved_chat_id is what older versions
     * have (and what a row of a room without a link names).
     */
    private fun openMember(room: RoomData, userId: Long): OpenChatMemberData? {
        if (!openMembers.available)
            return null
        return cache.openMember(room.id, userId) {
            val linkId = memberLinkOf(room)
            val byLink = linkId?.let {
                session.queryFirst(openMembers.select("user_id = ? AND link_id = ?", "LIMIT 1"), userId, it) { row -> row.toOpenChatMember(selfID) }
            }
            when {
                byLink != null                      -> byLink
                openMembers.has("involved_chat_id") ->
                    session.queryFirst(openMembers.select("user_id = ? AND involved_chat_id = ?", "LIMIT 1"), userId, room.id) { it.toOpenChatMember(selfID) }
                linkId == null                      ->
                    session.queryFirst(openMembers.select("user_id = ?", "LIMIT 1"), userId) { it.toOpenChatMember(selfID) }
                else                                -> null
            }
        }
    }

    /** Every open_chat_member row of [room] (by link when possible, see [openMember]), into the cache too. */
    private fun openMembersOf(room: RoomData): List<OpenChatMemberData> {
        val linkId = memberLinkOf(room)
        val rows = when {
            linkId != null                      -> session.query(openMembers.select("link_id = ?"), linkId) { it.toOpenChatMember(selfID) }
            openMembers.has("involved_chat_id") -> session.query(openMembers.select("involved_chat_id = ?"), room.id) { it.toOpenChatMember(selfID) }
            else                                -> emptyList()
        }.filterNotNull()
        for (member in rows)
            cache.putOpenMember(room.id, member.id, member)
        return rows
    }

    private fun members(roomId: Long): List<UserData> {
        val room = room(roomId) ?: return emptyList()
        if (room.isOpenChat && openMembers.available) {
            // Who is in the room now. A link's rows also hold members who left, and 1:1 open chats share their link.
            val ids = room.activeMemberIds.ifEmpty { room.members }.distinct().filter { it != selfID }
            val rows = openMembersOf(room)
            if (ids.isEmpty())
                return listOf(selfUser) + rows.filter { it.chatId == room.id && it.id != selfID }
            val profiles = rows.associateBy(OpenChatMemberData::id)
            prefetchFriends(ids.filter { it !in profiles })
            return listOf(selfUser) + ids.map { id -> profiles[id] ?: friend(id) ?: UnknownUserData(id) }
        }
        val ids = (room.members + room.activeMemberIds).distinct().filter { it != selfID }
        prefetchFriends(ids)
        return listOf(selfUser) + ids.map { resolveUser(it, room) }
    }

    private fun resolveSelf(): Pair<Long, String> {
        val profile = if (openProfiles.available)
            session.queryFirst(openProfiles.select(suffix = "LIMIT 1")) { row ->
                val enc = row.int("enc") ?: 0
                val userId = row.long("user_id")
                val nickname = row.string("nickname")?.let { KakaoCrypto.decryptOrRaw(userId ?: 0L, enc, it) }
                userId to nickname
            }
        else null

        // The user_id of a message the user sent is the surest source.
        val fromLogs = if (options.selfId == null && chatLogs.available && chatLogs.has("v"))
            session.queryFirst(
                "SELECT user_id FROM ${chatLogs.from} WHERE v LIKE ? ORDER BY _id DESC LIMIT 1",
                "%\"isMine\":true%",
            ) { it.long("user_id") }
        else null

        val id = options.selfId ?: fromLogs ?: profile?.first ?: -1L
        val name = options.selfName ?: profile?.second ?: ""
        if (id == -1L)
            logger.warn { "Failed to resolve the user's own id. Set it in the plugin settings." }
        return id to name
    }

    // ================================================================ rooms

    private fun room(roomId: Long): RoomData? {
        cache.room(roomId)?.let { return it }
        if (!chatRooms.available)
            return null
        val raw = session.queryFirst(chatRooms.select("id = ?", "LIMIT 1"), roomId, mapper = ::RawRoom)
            ?: return null
        return buildRoom(raw).also(cache::putRoom)
    }

    private fun allRooms(): List<RoomData> {
        if (!chatRooms.available)
            return emptyList()
        val order = if (chatRooms.has("last_updated_at")) "ORDER BY last_updated_at DESC" else "ORDER BY _id DESC"
        val raws = session.query(chatRooms.select(suffix = order), mapper = ::RawRoom)
        allFriends() // Loads the friends that room names are made of, at once.
        return raws.map { raw -> cache.room(raw.id) ?: buildRoom(raw).also(cache::putRoom) }
    }

    private fun openLink(linkId: Long): OpenLinkData? {
        if (!openLinks.available)
            return null
        return cache.openLink(linkId) { id ->
            session.queryFirst(openLinks.select("id = ?", "LIMIT 1"), id) { it.toOpenLink() }
        }
    }

    private fun allOpenLinks(): List<OpenLinkData> =
        if (!openLinks.available) emptyList()
        else session.query(openLinks.select()) { it.toOpenLink() }.filterNotNull()

    private fun buildRoom(raw: RawRoom): RoomData {
        val type = RoomData.Type.of(raw.type)
        val customName = raw.privateMeta.stringField("name")
        val meta = parseRoomMeta(raw.meta)
        val openLink = raw.linkId?.takeIf { it > 0 }?.let(::openLink)
        val isOpen = type == RoomData.Type.OM || type == RoomData.Type.OD

        val name = if (isOpen)
            openLink?.name ?: customName ?: meta.title
        else
            customName ?: meta.title ?: generateRoomName(raw.members)

        return RoomData(
            id                 = raw.id,
            type               = type,
            name               = name ?: "Unknown(${raw.id})",
            customName         = customName,
            title              = meta.title,
            notice             = meta.notice,
            members            = raw.members,
            activeMemberIds    = raw.activeMemberIds,
            activeMembersCount = raw.activeMembersCount,
            watermarks         = raw.watermarks,
            lastLogId          = raw.lastLogId,
            lastReadLogId      = raw.lastReadLogId,
            lastUpdatedAt      = raw.lastUpdatedAt,
            unreadCount        = raw.unreadCount,
            muteUntilAt        = raw.muteUntilAt,
            linkId             = raw.linkId,
            openLink           = openLink,
            moimMeta           = raw.moimMeta,
            privateMeta        = raw.privateMeta,
            meta               = raw.meta,
            v                  = raw.v,
        )
    }

    /** Names the room after the other members, as KakaoTalk does. For a 1:1 chat, the other person's name. */
    private fun generateRoomName(members: List<Long>): String? {
        val others = members.filter { it != selfID }
        if (others.isEmpty())
            return selfName.takeIf(String::isNotEmpty)
        prefetchFriends(others.take(MAX_NAMES_IN_ROOM_NAME))
        val names = others.take(MAX_NAMES_IN_ROOM_NAME).mapNotNull { friend(it)?.name }
        if (names.isEmpty())
            return null
        val rest = others.size - names.size
        return names.joinToString(", ") + if (rest > 0) " 외 ${rest}명" else ""
    }

    private fun fallbackRoom(roomId: Long): RoomData =
        RoomData(id = roomId, type = RoomData.Type.Unknown, name = "Unknown($roomId)")

    /** The values of one chat_rooms row, so that no other query runs while its cursor is open. */
    private class RawRoom(row: Row) {
        val id                 = row.long("id") ?: 0L
        val type               = row.string("type")
        val members            = parseIdList(row.string("members"))
        val activeMemberIds    = parseIdList(row.string("active_member_ids"))
        val activeMembersCount = row.int("active_members_count")
        val watermarks         = parseIdList(row.string("watermarks"))
        val lastLogId          = row.long("last_log_id")
        val lastReadLogId      = row.long("last_read_log_id")
        val lastUpdatedAt      = row.long("last_updated_at")
        val unreadCount        = row.int("unread_count")
        val muteUntilAt        = row.long("mute_until_at")
        val linkId             = row.long("link_id")
        val moimMeta           = row.string("moim_meta")
        val privateMeta: JsonElement? = row.string("private_meta")?.let(::parseJsonOrNull)
        val meta       : JsonElement? = row.string("meta")?.let(::parseJsonOrNull)
        val v          : JsonElement? = row.string("v")?.let(::parseJsonOrNull)
    }

    // ================================================================ connection

    private fun openSession() {
        // Whether the files exist is the factory's to check (see SessionFactory.requiringFiles): this process may not
        // be able to see them at all, when a root helper opens them.
        val session = sessionFactory.open(mainDBFile.absolutePath, subDBFile.absolutePath)
        try {
            schema = KakaoSchema.resolve(session, logger)
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
        mSession = session
        lastMainVersion = -1L
        lastDb2Version = -1L
        openUserDatabase()
        // db2.friends isn't missing when crypto_user_database took its place.
        missingTables = schema.missingTables
            .filterNot { it == KakaoTable.FRIENDS && friendSource == FriendSource.UserDatabase }
            .map(KakaoTable::label)
    }

    /**
     * Opens a new session in place of the current one. On failure the connection is [DecoderHealth.Lost] (until an
     * attempt succeeds, see [reopenIfDue]) and this throws a [SessionException].
     */
    private fun reconnect() {
        logger.warn { "Reconnecting to the database..." }
        runCatching { mSession?.close() }
        mSession = null
        closeUserDatabase()
        try {
            openSession()
        } catch (e: Exception) {
            connectionLost(e)
            throw e as? SessionException ?: SessionException(e.message ?: e.toString(), e)
        }
        cache.invalidateAll()
        if (mHealth.value is DecoderHealth.Lost)
            logger.info { "Reconnected to the database" }
        reconnectFailures = 0
        mHealth.value = DecoderHealth.Ok
    }

    private fun connectionLost(error: Exception) {
        val now = System.currentTimeMillis()
        val delay = (options.reconnectDelayMillis shl reconnectFailures.coerceAtMost(MAX_BACKOFF_DOUBLINGS))
            .coerceAtMost(options.maxReconnectDelayMillis)
        reconnectFailures++
        val reason = error.message ?: error.toString()
        val since = (mHealth.value as? DecoderHealth.Lost)?.since ?: now
        mHealth.value = DecoderHealth.Lost(reason, since, retryAt = now + delay)
        logger.error(error) { "Couldn't reopen the database ($reason); trying again in ${(delay + 999) / 1000}s" }
    }

    /** After a failed [reconnect], tries again once the wait is over; until then, fails at once. */
    private fun reopenIfDue() {
        val lost = mHealth.value as? DecoderHealth.Lost
        if (lost != null && System.currentTimeMillis() < lost.retryAt)
            throw SessionException("The database connection is lost: ${lost.reason}")
        reconnect()
    }

    /**
     * Runs [block] on the database thread. On a [SessionException], reconnects once and retries. When the connection
     * couldn't be reopened before, reopens it first, if the next attempt is due; otherwise fails at once.
     */
    private suspend fun <T> db(block: () -> T): T =
        withContext(dispatcher) {
            check(!closed) { "Decoder is closed" }
            if (mSession == null)
                reopenIfDue()
            try {
                block()
            } catch (e: SessionException) {
                logger.warn(e) { "Database error, retrying after reconnect: ${e.message}" }
                reconnect()
                block()
            }
        }

    /** [db], for what reads the cache: polls only refresh it when there are new rows (see [refreshCaches]). */
    private suspend fun <T> cachedDb(block: () -> T): T =
        db {
            refreshCaches()
            block()
        }

    private companion object {
        const val MAX_NAMES_IN_ROOM_NAME = 4

        /** reconnectDelayMillis doubles at most this many times (the max delay usually caps it first). */
        const val MAX_BACKOFF_DOUBLINGS = 10

        const val RECONNECT_TICK_MARGIN_MILLIS = 50L

        /** How long after a failure crypto_user_database is opened again. */
        const val USER_DB_RETRY_MILLIS = 5 * 60_000L
    }
}

/** Whether the decoder's connection works. */
sealed class DecoderHealth {

    data object Ok : DecoderHealth()

    /**
     * The connection broke and reopening it failed, at [since]: nothing is read until it is reopened, which is tried
     * again at [retryAt] (and later, waiting longer each time). Rows added meanwhile are read once it is.
     */
    data class Lost(val reason: String, val since: Long, val retryAt: Long) : DecoderHealth()
}

/** Where the decoder reads the friend list from. */
sealed class FriendSource {

    /** KakaoTalk2.db's friends table (older KakaoTalk versions). */
    data object Db2 : FriendSource()

    /** crypto_user_database (newer versions). */
    data object UserDatabase : FriendSource()

    /** The friend list is in crypto_user_database, which the session factory can't read (only root mode does). */
    data object UserDatabaseUnsupported : FriendSource()

    /** crypto_user_database couldn't be opened or read; it is tried again every so often. */
    data class UserDatabaseFailed(val reason: String) : FriendSource()
}

/**
 * How far each active member of a room has read. [watermarks] holds the last read log id of each of [memberIds],
 * in the same order.
 */
data class ReadState(
    val memberIds: List<Long>,
    val watermarks: List<Long>,
) {
    /** Members who haven't read up to [logId] yet. Approximate: it is what the watermarks say right now. */
    fun unreadCount(logId: Long): Int =
        watermarks.count { it < logId }
}
