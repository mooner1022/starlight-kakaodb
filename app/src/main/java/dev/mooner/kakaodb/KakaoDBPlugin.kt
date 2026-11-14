package dev.mooner.kakaodb

import android.graphics.Bitmap
import android.text.InputType
import dev.mooner.configdsl.ConfigActionScope
import dev.mooner.configdsl.ConfigItemBuilder
import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.InfoState
import dev.mooner.configdsl.options.OptionStatus
import dev.mooner.configdsl.options.button
import dev.mooner.configdsl.options.choice
import dev.mooner.configdsl.options.info
import dev.mooner.configdsl.options.seekbar
import dev.mooner.configdsl.options.spinner
import dev.mooner.configdsl.options.string
import dev.mooner.configdsl.options.textList
import dev.mooner.configdsl.options.toggle
import dev.mooner.configdsl.utils.PeekType
import dev.mooner.kakaodb.api.KakaoDBApi
import dev.mooner.kakaodb.core.DatabaseDecoder
import dev.mooner.kakaodb.core.DecoderHealth
import dev.mooner.kakaodb.core.DecoderOptions
import dev.mooner.kakaodb.core.FriendSource
import dev.mooner.kakaodb.core.KakaoEvent
import dev.mooner.kakaodb.db.AccessMode
import dev.mooner.kakaodb.db.AccessResult
import dev.mooner.kakaodb.db.DatabaseAccess
import dev.mooner.kakaodb.db.DatabaseWatcher
import dev.mooner.kakaodb.db.RootAccess
import dev.mooner.kakaodb.event.KakaoCompatDeleteEvent
import dev.mooner.kakaodb.event.KakaoCompatMessageEvent
import dev.mooner.kakaodb.event.KakaoMemberJoinEvent
import dev.mooner.kakaodb.event.KakaoMemberLeaveEvent
import dev.mooner.kakaodb.event.KakaoMemberRoleEvent
import dev.mooner.kakaodb.event.KakaoMessageCreateEvent
import dev.mooner.kakaodb.event.KakaoMessageDeleteEvent
import dev.mooner.kakaodb.event.KakaoMessageHideEvent
import dev.mooner.kakaodb.event.KakaoRoomFeedEvent
import dev.mooner.kakaodb.script.KakaoFeedEvent
import dev.mooner.kakaodb.script.KakaoMemberJoinedEvent
import dev.mooner.kakaodb.script.KakaoMemberLeftEvent
import dev.mooner.kakaodb.script.KakaoMemberRoleChangedEvent
import dev.mooner.kakaodb.script.KakaoMessage
import dev.mooner.kakaodb.script.KakaoMessageDeletedEvent
import dev.mooner.kakaodb.script.KakaoMessageHiddenEvent
import dev.mooner.kakaodb.script.ScriptMapper
import dev.mooner.kakaodb.send.SendQueue
import dev.mooner.kakaodb.setup.InitialSetup
import dev.mooner.kakaodb.setup.sharedUidMaintenance
import dev.mooner.kakaodb.setup.sharedUidSetupSteps
import dev.mooner.kakaodb.utils.LOG
import dev.mooner.kakaodb.utils.PluginLogger
import dev.mooner.kakaodb.utils.tr
import dev.mooner.starlight.plugincore.chat.ChatSender
import dev.mooner.starlight.plugincore.chat.DeletedMessage
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.config.GlobalConfig
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.config.data.InMemoryConfig
import dev.mooner.starlight.plugincore.event.EventHandler
import dev.mooner.starlight.plugincore.event.Events
import dev.mooner.starlight.plugincore.event.on
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import androidx.core.graphics.createBitmap

@Suppress("unused")
class KakaoDBPlugin : StarlightPlugin() {

    /** What a connection is made with: changing any of it reconnects. */
    private data class ConnectionSettings(
        val path: String = DEFAULT_DB_PATH,
        val accessMode: AccessMode = AccessMode.DIRECT,
        val selfId: Long? = null,
        val selfName: String? = null,
        val pollMillis: Long = POLL_INTERVALS[DEF_POLL_INTERVAL],
    )

    private data class Settings(
        val connection: ConnectionSettings = ConnectionSettings(),
        /** Whether the access mode was ever saved: until then the plugin hasn't been told how to read KakaoTalk. */
        val configured: Boolean = false,
        val autoStart: Boolean = true,
        val ignoreOwnMessages: Boolean = true,
        val compatMessage: Boolean = false,
        val compatDelete: Boolean = false,
        val sendIntervalMillis: Long = DEF_SEND_INTERVAL.toLong(),
        val logEvents: Boolean = false,
        val verboseLogging: Boolean = false,
    )

    @Volatile
    private var settings = Settings()

    /** Lives from onEnable to onDisable. Also tells whether the plugin is on: StarLight reads it on the main thread. */
    @Volatile
    private var pluginScope: CoroutineScope? = null

    /** Serializes connecting and disconnecting. */
    private val connectionLock = Mutex()

    /** Guarded by [connectionLock]. */
    private var watchJob: Job? = null

    /** Mirrors the decoder's health into the status. Guarded by [connectionLock]. */
    private var healthJob: Job? = null

    /** The next attempt after a connection that couldn't be made (see [ConnectRetry]). Guarded by [connectionLock]. */
    private var retryJob: Job? = null

    /** Attempts that failed in a row, which the wait before the next one grows with. Guarded by [connectionLock]. */
    private var failedAttempts = 0

    /** compat events need a profile picture; the database has none. */
    private val placeholderProfile: Bitmap by lazy { createBitmap(1, 1) }

    /**
     * For what nothing else caught. Without it, an exception leaving one of the plugin's coroutines goes to the
     * thread's uncaught exception handler, which is StarLight's: the whole app ends.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        LOG.error(e) { tr("Unexpected error in KakaoDB: $e", "KakaoDB에서 예기치 못한 오류가 발생했어요: $e") }
    }

    override fun onEnable() {
        settings = readSettings(getPluginConfig())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        pluginScope = scope

        registerProjectEvents {
            category("message") {
                add<KakaoMessageCreateEvent>()
                add<KakaoMessageDeleteEvent>()
                add<KakaoMessageHideEvent>()
            }
            category("member") {
                add<KakaoMemberJoinEvent>()
                add<KakaoMemberLeaveEvent>()
                add<KakaoMemberRoleEvent>()
            }
            category("room") {
                add<KakaoRoomFeedEvent>()
            }
            category("compat") {
                add<KakaoCompatMessageEvent>()
                add<KakaoCompatDeleteEvent>()
            }
        }
        addApi(KakaoDBApi())

        EventHandler.on<Events.Notification.Post>(scope) {
            if (statusBarNotification.packageName == KAKAO_TALK) {
                KakaoDBRuntime.replies.capture(statusBarNotification, context)
                KakaoDBRuntime.bump()
            }
        }

        val queue = SendQueue(KakaoDBRuntime.replies) { settings.sendIntervalMillis }
        KakaoDBRuntime.sendQueue = queue
        queue.start(scope)

        if (settings.autoStart)
            scope.launch { connect() }
    }

    override fun onDisable() {
        KakaoDBRuntime.sendQueue?.close()
        KakaoDBRuntime.sendQueue = null
        val scope = pluginScope
        pluginScope = null
        // Closing the decoder waits for its thread, which onDisable (maybe on the main thread) shouldn't.
        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            disconnect()
            scope?.cancel()
        }
        KakaoDBRuntime.replies.clear()
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        val old = settings
        settings = readSettings(config)
        // Everything but a plugin that was stopped, or never started. A connection that failed is tried again with the
        // new settings: the setup it asked for may be what was just changed. One still being made is made again once
        // it is: it started with the old ones (connectLocked reads them after it sets its state, so a change that
        // finds the plugin stopped here is one it hasn't read yet).
        val connecting = KakaoDBRuntime.state.value != ConnectionState.Stopped
        if (settings.connection != old.connection && connecting) {
            LOG.info { tr("Database settings changed: reconnecting", "데이터베이스 설정이 바뀌어 다시 연결해요.") }
            pluginScope?.launch { reconnect() }
        }
    }

    private fun readSettings(config: ConfigData): Settings {
        val general = config.category(CATEGORY_GENERAL)
        val account = config.category(CATEGORY_ACCOUNT)
        val message = config.category(CATEGORY_MESSAGE)
        val logging = config.category(CATEGORY_LOGGING)
        return Settings(
            connection = ConnectionSettings(
                path = general.getString(CONF_DB_PATH)?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_DB_PATH,
                accessMode = AccessMode.of(general.getString(CONF_ACCESS_MODE)),
                selfId = account.getString(CONF_SELF_ID)?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                selfName = account.getString(CONF_SELF_NAME)?.trim()?.takeIf(String::isNotEmpty),
                pollMillis = POLL_INTERVALS.getOrElse(general.getInt(CONF_POLL_INTERVAL, DEF_POLL_INTERVAL)) { POLL_INTERVALS[DEF_POLL_INTERVAL] },
            ),
            configured = general.getString(CONF_ACCESS_MODE) != null,
            autoStart = general.getBoolean(CONF_AUTO_START, true),
            ignoreOwnMessages = message.getBoolean(CONF_IGNORE_OWN, true),
            compatMessage = message.getBoolean(CONF_COMPAT_MESSAGE, false),
            compatDelete = message.getBoolean(CONF_COMPAT_DELETE, false),
            sendIntervalMillis = config.category(CATEGORY_SENDING).getInt(CONF_SEND_INTERVAL, DEF_SEND_INTERVAL).toLong(),
            logEvents = logging.getBoolean(CONF_LOG_EVENTS, false),
            verboseLogging = logging.getBoolean(CONF_VERBOSE, false),
        )
    }

    // ------------------------------------------------------------------ connection

    private suspend fun connect() =
        connectionLock.withLock {
            withContext(Dispatchers.IO) {
                // Asked for: this attempt takes the place of the one that was waiting, and the waits start over.
                cancelRetryLocked()
                connectLocked()
            }
        }

    private suspend fun disconnect() =
        connectionLock.withLock { withContext(Dispatchers.IO) { disconnectLocked() } }

    private suspend fun reconnect() =
        connectionLock.withLock {
            withContext(Dispatchers.IO) {
                disconnectLocked()
                connectLocked()
            }
        }

    /**
     * Connects, unless connected. [retry]: this is an attempt the plugin makes by itself after a failed one. It leaves
     * the state as it is ([ConnectionState.Failed]) until it knows better: the status and the setup StarLight asks
     * for would otherwise flicker with every attempt.
     */
    private suspend fun connectLocked(retry: Boolean = false) {
        if (KakaoDBRuntime.decoder != null)
            return
        val scope = pluginScope ?: return
        // Before the settings are read: see onConfigUpdated.
        if (!retry)
            KakaoDBRuntime.setState(ConnectionState.Starting)
        val connection = settings.connection
        try {
            openConnection(scope, connection)
        } catch (e: CancellationException) {
            // Whoever asked for it went away (the settings screen closed while it connected): nothing is connecting
            // any more, and the status mustn't go on saying so.
            if (KakaoDBRuntime.state.value == ConnectionState.Starting)
                KakaoDBRuntime.setState(ConnectionState.Stopped)
            throw e
        } catch (e: Exception) {
            // What is known to fail is answered in openConnection. This is something nobody thought of: left alone it
            // would end the coroutine with the status stuck at connecting.
            LOG.error(e) { tr("Unexpected error while connecting: $e", "연결 중 예기치 못한 오류가 발생했어요: $e") }
            fail(tr("Unexpected error while connecting: $e", "연결 중 예기치 못한 오류가 발생했어요: $e"), retryable = false)
        }
    }

    private suspend fun openConnection(scope: CoroutineScope, connection: ConnectionSettings) {
        val dir = File(connection.path)
        val access = DatabaseAccess.of(connection.accessMode, getAsset(RootAccess.HELPER_ASSET))
        val warnings = when (val result = access.prepare(dir)) {
            is AccessResult.Failed -> return fail(result.reason, result.retryable)
            is AccessResult.Ready  -> result.warnings.toMutableList()
        }

        val options = DecoderOptions(baseDBPath = dir.path, selfId = connection.selfId, selfName = connection.selfName)
        val decoder = DatabaseDecoder(options, access.sessionFactory, PluginLogger { settings.verboseLogging })
        try {
            decoder.open()
        } catch (e: CancellationException) {
            decoder.close()
            throw e
        } catch (e: Exception) {
            decoder.close()
            return fail(
                tr("Failed to open the database: ${e.message ?: e}", "데이터베이스를 열지 못했어요: ${e.message ?: e}"),
                retryable = true,
            )
        }
        try {
            startConnection(scope, connection, access, dir, decoder, warnings)
        } catch (e: Throwable) {
            // Nothing of a connection that didn't get all the way stays behind.
            closeConnectionLocked()
            decoder.close()
            throw e
        }
    }

    private fun startConnection(
        scope: CoroutineScope,
        connection: ConnectionSettings,
        access: DatabaseAccess,
        dir: File,
        decoder: DatabaseDecoder,
        warnings: MutableList<String>,
    ) {
        if (decoder.missingTables.isNotEmpty())
            warnings += tr("Tables missing: ${decoder.missingTables.joinToString()}", "없는 테이블: ${decoder.missingTables.joinToString()}")
        when (val source = decoder.friendSource) {
            FriendSource.Db2, FriendSource.UserDatabase -> Unit
            FriendSource.UserDatabaseUnsupported -> warnings += tr(
                "Friends show as unknown users: this KakaoTalk keeps them in crypto_user_database, which only root and shared UID modes read.",
                "친구가 알 수 없는 사용자로 보여요. 이 카카오톡 버전은 친구 목록을 crypto_user_database에 두는데, 이 파일은 루트 모드와 공유 UID 모드에서만 읽을 수 있어요.",
            )
            is FriendSource.UserDatabaseFailed -> warnings += tr(
                "Friends show as unknown users: crypto_user_database couldn't be read (${source.reason}). Retried every 5 minutes.",
                "친구가 알 수 없는 사용자로 보여요. crypto_user_database를 읽지 못했어요 (${source.reason}). 5분마다 다시 시도해요.",
            )
        }
        if (decoder.selfID <= 0)
            warnings += tr(
                "Couldn't find your user id: set it in Account to tell your own messages apart.",
                "내 사용자 ID를 찾지 못했어요. 내 메시지를 구분하려면 계정 설정에서 직접 입력하세요.",
            )

        failedAttempts = 0
        KakaoDBRuntime.decoder = decoder
        val mapper = ScriptMapper(decoder.selfID)
        val triggers = DatabaseWatcher.triggers(access.fileChanges(dir), connection.pollMillis)
        watchJob = decoder.start(scope, triggers) { event -> dispatch(event, mapper) }
        val running = ConnectionState.Running(decoder.selfID, decoder.selfName, System.currentTimeMillis(), warnings)
        KakaoDBRuntime.setState(running)
        // The status follows the connection: Reconnecting while it is lost, Running again once it is back.
        healthJob = scope.launch {
            decoder.health.collect { health ->
                // Only while this decoder is the current one: a late update mustn't undo a disconnect.
                if (KakaoDBRuntime.decoder !== decoder)
                    return@collect
                KakaoDBRuntime.setState(
                    when (health) {
                        DecoderHealth.Ok         -> running
                        is DecoderHealth.Lost    -> ConnectionState.Reconnecting(health.reason, health.retryAt)
                    }
                )
            }
        }
        LOG.info { tr("Connected to the KakaoTalk database (${decoder.selfName})", "카카오톡 데이터베이스에 연결했어요 (${decoder.selfName})") }
        for (warning in warnings)
            LOG.warn { warning }
    }

    private fun disconnectLocked() {
        cancelRetryLocked()
        if (closeConnectionLocked())
            LOG.info { tr("Disconnected from the KakaoTalk database", "카카오톡 데이터베이스 연결을 끊었어요.") }
        KakaoDBRuntime.setState(ConnectionState.Stopped)
    }

    /** Stops the watch and closes the decoder; tells whether there was one. Leaves the state to the caller. */
    private fun closeConnectionLocked(): Boolean {
        watchJob?.cancel()
        watchJob = null
        healthJob?.cancel()
        healthJob = null
        val decoder = KakaoDBRuntime.decoder
        KakaoDBRuntime.decoder = null
        decoder?.close()
        return decoder != null
    }

    /**
     * The connection couldn't be made, for [reason]. When that may pass by itself ([retryable]), another attempt is
     * scheduled. An attempt that failed the way the one before it did says nothing more: the log and StarLight were
     * told then.
     */
    private fun fail(reason: String, retryable: Boolean) {
        val scope = pluginScope
        val retrying = retryable && scope != null
        val repeated = (KakaoDBRuntime.state.value as? ConnectionState.Failed)?.reason == reason
        KakaoDBRuntime.setState(ConnectionState.Failed(reason, retrying))
        val wait = if (retrying) scheduleRetryLocked(scope) else null
        if (repeated)
            return
        LOG.error { reason }
        if (wait != null)
            LOG.info {
                tr(
                    "Trying to connect again in ${wait / 1000}s, then less often for as long as it fails.",
                    "${wait / 1000}초 뒤에 다시 연결해 봐요. 계속 실패하면 간격을 늘려 가며 시도해요.",
                )
            }
        // isSetupRequired() follows the state, so it is true by now: tell StarLight, which opens the setup if it can.
        try {
            requestSetup()
        } catch (_: LinkageError) {
            // A StarLight from before the setup dialog: nothing listens, and the plugin settings still work.
        }
    }

    /** Schedules the next attempt, and tells how long until it. */
    private fun scheduleRetryLocked(scope: CoroutineScope): Long {
        failedAttempts++
        val wait = ConnectRetry.delayMillis(failedAttempts)
        retryJob = scope.launch {
            val self = coroutineContext[Job]
            delay(wait)
            connectionLock.withLock {
                // Not when something else connected, stopped, or scheduled another attempt meanwhile. Cleared before
                // connecting: this attempt is no longer one to cancel, and may schedule the next.
                if (retryJob !== self)
                    return@withLock
                retryJob = null
                withContext(Dispatchers.IO) { connectLocked(retry = true) }
            }
        }
        return wait
    }

    /** Drops the attempt that is waiting, if any; the waits start over from the shortest. */
    private fun cancelRetryLocked() {
        retryJob?.cancel()
        retryJob = null
        failedAttempts = 0
    }

    // ------------------------------------------------------------------ initial setup

    override fun isSetupRequired(): Boolean =
        InitialSetup.isRequired(pluginScope != null, settings.configured, KakaoDBRuntime.state.value)

    override fun getSetupReason(): String? =
        InitialSetup.reason(settings.configured, KakaoDBRuntime.state.value)

    /**
     * The two things the plugin can't guess, with what is in effect preselected: the same items, ids and keys as in the
     * settings. Shared UID mode also needs KakaoTalk and the helper patched and installed, which is done here too, in
     * the steps that show while that mode is chosen. They are in this category: a visibility rule only sees its siblings.
     * Only the first installation is here; updating the helper and the signing key are in the settings.
     */
    override fun getSetupStructure(): ConfigStructure {
        val connection = settings.connection
        return config {
            category {
                id = CATEGORY_GENERAL
                title = tr("Database", "데이터베이스")
                textColor = color { COLOR_TITLE }
                items {
                    accessMode(connection.accessMode, color { COLOR_ICON })
                    sharedUidSetupSteps(getInternalDataDirectory(), ::getAsset, CONF_ACCESS_MODE, AccessMode.SHARED.key, color { COLOR_ICON })
                    databasePath(connection.path, color { COLOR_ICON })
                }
            }
        }
    }

    override suspend fun onSetup(values: ConfigData): String? {
        val general = values.category(CATEGORY_GENERAL)
        val current = settings.connection
        val answers = InitialSetup.answers(
            modeKey = general.getString(CONF_ACCESS_MODE),
            path = general.getString(CONF_DB_PATH),
            current = InitialSetup.Answers(current.accessMode, current.path),
        )
        // Off the main thread, where this is called: the shared UID helper's binding is answered on it, so waiting for
        // it there never ends.
        val failure = withContext(Dispatchers.IO) {
            when (val result = DatabaseAccess.of(answers.mode, getAsset(RootAccess.HELPER_ASSET)).prepare(File(answers.path))) {
                is AccessResult.Failed -> result.reason
                is AccessResult.Ready  -> null
            }
        }
        if (failure != null)
            return failure

        // Everything is saved, also what was left alone and so isn't in values: without access_mode in the config the
        // plugin would still count as not set up.
        saveToConfig(InMemoryConfig(mapOf(
            CATEGORY_GENERAL to mapOf(
                CONF_ACCESS_MODE to JsonPrimitive(answers.mode.key),
                CONF_DB_PATH to JsonPrimitive(answers.path),
            )
        )))
        // StarLight reports the plugin settings screen's changes to onConfigUpdated, not these.
        settings = readSettings(getPluginConfig())
        pluginScope?.launch { reconnect() }
        return null
    }

    // [tint] comes from the caller: color() belongs to the builder of the whole structure, out of reach here.
    private fun ConfigItemBuilder.databasePath(default: String, tint: Int) {
        string {
            id = CONF_DB_PATH
            title = tr("Database folder", "데이터베이스 폴더")
            description = tr(
                "The folder holding KakaoTalk.db and KakaoTalk2.db. Change it only if KakaoTalk's data is somewhere else, such as a second user or a cloned app.",
                "KakaoTalk.db와 KakaoTalk2.db가 있는 폴더입니다. 보조 사용자나 앱 복제본처럼 카카오톡 데이터가 다른 곳에 있을 때만 바꾸세요.",
            )
            hint = DEFAULT_DB_PATH
            defaultValue = default
            require = { value -> if (value.isBlank()) tr("Enter a folder", "폴더를 입력하세요") else null }
            icon = Icon.FOLDER
            iconTintColor = tint
        }
    }

    private fun ConfigItemBuilder.accessMode(default: AccessMode, tint: Int) {
        choice {
            id = CONF_ACCESS_MODE
            title = tr("Access mode", "접근 방식")
            description = tr(
                "KakaoTalk keeps its database where other apps can't read it. Direct needs the files to be readable already, root starts a helper with su, and shared UID needs KakaoTalk and a helper patched with this plugin's tools (set up in the plugin's initial setup).",
                "카카오톡 데이터베이스는 다른 앱이 읽을 수 없는 곳에 있어요. 직접은 파일을 이미 읽을 수 있을 때, 루트는 su로 도우미를 실행할 때, 공유 UID는 이 플러그인의 도구로 패치한 카카오톡과 도우미가 있을 때 쓰세요 (초기 설정에서 패치·설치해요).",
            )
            option(AccessMode.DIRECT.key, tr("Direct: the files are readable as they are", "직접: 파일을 그대로 읽을 수 있음"))
            option(AccessMode.ROOT.key, tr("Root: read through a helper started with su", "루트: su로 띄운 도우미를 통해 읽기"))
            option(AccessMode.SHARED.key, tr("Shared UID: installed helper", "공유 UID: 설치된 도우미"))
            defaultKey = default.key
            icon = Icon.LOCK
            iconTintColor = tint
        }
    }

    // ------------------------------------------------------------------ events

    private fun dispatch(event: KakaoEvent, mapper: ScriptMapper) {
        if (event is KakaoEvent.LogCreated || !isGlobalPowerOn())
            return
        val settings = settings
        if (event is KakaoEvent.MessageCreated) {
            val chat = event.chat
            val isMine = chat.userId == mapper.selfId || chat.v.isMine
            if (isMine && settings.ignoreOwnMessages)
                return
            if (!isMine)
                KakaoDBRuntime.replies.onStoredLog(chat.id, chat.chatId)
        }
        val obj = mapper.event(event) ?: return
        if (settings.logEvents)
            LOG.info { obj.toString() }

        when (obj) {
            is KakaoMessage                 -> {
                fire<KakaoMessageCreateEvent>(obj)
                if (settings.compatMessage)
                    fire<KakaoCompatMessageEvent>(compatMessage(obj))
            }
            is KakaoMessageDeletedEvent     -> {
                fire<KakaoMessageDeleteEvent>(obj)
                if (settings.compatDelete)
                    fire<KakaoCompatDeleteEvent>(compatDeleted(obj))
            }
            is KakaoMessageHiddenEvent      -> fire<KakaoMessageHideEvent>(obj)
            is KakaoMemberJoinedEvent       -> fire<KakaoMemberJoinEvent>(obj)
            is KakaoMemberLeftEvent         -> fire<KakaoMemberLeaveEvent>(obj)
            is KakaoMemberRoleChangedEvent  -> fire<KakaoMemberRoleEvent>(obj)
            is KakaoFeedEvent               -> fire<KakaoRoomFeedEvent>(obj)
        }
        KakaoDBRuntime.bump()
    }

    private inline fun <reified T : ProjectEvent> fire(arg: Any) {
        try {
            fireProjectEvent<T>(arg, onFailure = ::onEventFailure)
            KakaoDBRuntime.dispatchedEvents.incrementAndGet()
        } catch (e: Exception) {
            LOG.error(e) { tr("Failed to fire ${T::class.simpleName}: $e", "${T::class.simpleName} 이벤트 호출 실패: $e") }
        }
    }

    private fun onEventFailure(project: Project, e: Throwable) =
        LOG.warn { tr("Error in project ${project.info.name}: ${e.message ?: e}", "프로젝트 ${project.info.name}에서 오류 발생: ${e.message ?: e}") }

    private fun compatMessage(message: KakaoMessage): Message =
        Message(
            message = message.text,
            image = null,
            sender = ChatSender(message.sender.name, message.senderId, placeholderProfile),
            room = message.room,
            packageName = KAKAO_TALK,
            hasMention = message.hasMention,
            chatLogId = message.id.toLong(),
        )

    private fun compatDeleted(event: KakaoMessageDeletedEvent): DeletedMessage =
        DeletedMessage(
            message = event.message?.text ?: "",
            sender = event.message?.sender?.name ?: "",
            room = event.room,
            packageName = KAKAO_TALK,
            chatLogId = event.messageId.toLong(),
        )

    private fun isGlobalPowerOn(): Boolean =
        GlobalConfig.category("general").getBoolean("global_power", true)

    // ------------------------------------------------------------------ control panel

    private suspend fun ConfigActionScope.changeConnection(block: suspend () -> Unit) {
        block()
        when (val state = KakaoDBRuntime.state.value) {
            is ConnectionState.Running      -> peek(tr("Connected", "연결됨"), PeekType.SUCCESS)
            is ConnectionState.Reconnecting -> peek(tr("Reconnecting: ${state.reason}", "다시 연결하는 중: ${state.reason}"), PeekType.ERROR)
            is ConnectionState.Failed       -> peek(state.reason, PeekType.ERROR)
            else                            -> peek(tr("Disconnected", "연결 끊김"))
        }
    }

    private fun statusFlow(): Flow<InfoState> =
        KakaoDBRuntime.state.map { state ->
            when (state) {
                is ConnectionState.Running      ->
                    if (state.warnings.isEmpty()) InfoState(tr("Connected", "연결됨"), OptionStatus.OK)
                    else InfoState(tr("Connected", "연결됨"), OptionStatus.WARNING)
                is ConnectionState.Reconnecting -> InfoState(tr("Reconnecting", "재연결 중"), OptionStatus.WARNING)
                ConnectionState.Starting        -> InfoState(null, OptionStatus.LOADING)
                ConnectionState.Stopped         -> InfoState(tr("Stopped", "중지됨"))
                is ConnectionState.Failed       -> InfoState(tr("Failed", "오류"), OptionStatus.ERROR)
            }
        }

    private fun statusDescriptionFlow(): Flow<String?> =
        combine(KakaoDBRuntime.state, KakaoDBRuntime.version) { state, _ ->
            when (state) {
                is ConnectionState.Running -> buildString {
                    append(tr("Account: ", "계정: ")).append(state.selfName.ifEmpty { "?" }).append(" (").append(state.selfId).append(")\n")
                    append(tr("Events delivered: ", "전달한 이벤트: ")).append(KakaoDBRuntime.dispatchedEvents.get())
                    for (warning in state.warnings)
                        append("\n⚠ ").append(warning)
                }
                is ConnectionState.Reconnecting -> tr(
                    "The connection broke: ${state.reason}\nTrying again by itself; messages from meanwhile come once it's back.",
                    "연결이 끊겼어요: ${state.reason}\n자동으로 다시 시도하고, 그동안 온 메시지는 연결되면 전달해요.",
                )
                ConnectionState.Starting   -> tr("Connecting...", "연결 중...")
                ConnectionState.Stopped    -> null
                is ConnectionState.Failed  ->
                    if (state.retrying)
                        state.reason + "\n" + tr(
                            "Trying again by itself, less often for as long as it fails.",
                            "자동으로 다시 시도하고 있어요. 계속 실패하면 간격을 늘려요.",
                        )
                    else
                        state.reason
            }
        }

    /** What the connection button does now: also stops the attempts the plugin makes by itself after a failure. */
    private fun stopsConnection(state: ConnectionState): Boolean =
        state is ConnectionState.Running || state is ConnectionState.Reconnecting ||
            (state is ConnectionState.Failed && state.retrying)

    private fun connectionButtonTitle(state: ConnectionState): String =
        when {
            state is ConnectionState.Failed && state.retrying -> tr("Stop retrying", "재시도 중지")
            stopsConnection(state)                            -> tr("Disconnect", "연결 끊기")
            else                                              -> tr("Connect", "연결")
        }

    private fun sendableRooms(): List<String> =
        KakaoDBRuntime.replies.sendableRooms().map { (id, label) -> "${label ?: "?"} ($id)" }

    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_CONTROL
            title = tr("Connection", "연결")
            textColor = color { COLOR_SUB_TITLE }
            items {
                info {
                    id = "status"
                    title = tr("Status", "상태")
                    icon = Icon.INFO
                    bind(statusFlow())
                    bindDescription(statusDescriptionFlow())
                }
                button {
                    id = "toggle_connection"
                    title = connectionButtonTitle(KakaoDBRuntime.state.value)
                    bindTitle(KakaoDBRuntime.state.map(::connectionButtonTitle))
                    icon = Icon.POWER
                    action {
                        changeConnection {
                            if (KakaoDBRuntime.decoder != null || stopsConnection(KakaoDBRuntime.state.value)) disconnect()
                            else connect()
                        }
                    }
                }
                button {
                    id = "reconnect"
                    title = tr("Reconnect", "다시 연결")
                    icon = Icon.REFRESH
                    action {
                        changeConnection(::reconnect)
                    }
                }
            }
        }
        category {
            id = CATEGORY_GENERAL
            title = tr("Database", "데이터베이스")
            textColor = color { COLOR_TITLE }
            items {
                // The same two items as the initial setup, which holds the shared UID mode's first patch and install.
                // What comes after it, updating the helper and the signing key, shows here while that mode is chosen.
                accessMode(AccessMode.DIRECT, color { COLOR_ICON })
                sharedUidMaintenance(getInternalDataDirectory(), ::getAsset, CONF_ACCESS_MODE, AccessMode.SHARED.key, color { COLOR_ICON })
                databasePath(DEFAULT_DB_PATH, color { COLOR_ICON })
                spinner {
                    id = CONF_POLL_INTERVAL
                    title = tr("Check interval", "변경 확인 주기")
                    description = tr(
                        "File changes are noticed right away; this also checks periodically in case they aren't.",
                        "파일 변경은 바로 감지하고, 놓칠 경우를 대비해 이 주기로도 확인합니다.",
                    )
                    items = listOf(tr("Off", "끔")) + POLL_INTERVAL_LABELS
                    defaultIndex = DEF_POLL_INTERVAL
                    icon = Icon.TIMER
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_AUTO_START
                    title = tr("Connect automatically", "자동 연결")
                    description = tr("Connects when the plugin is enabled.", "플러그인이 켜지면 바로 연결합니다.")
                    defaultValue = true
                    icon = Icon.POWER
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_MESSAGE
            title = tr("Events", "이벤트")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_IGNORE_OWN
                    title = tr("Ignore my messages", "내 메시지 무시")
                    description = tr("Messages you (or your bot) send don't fire onKakaoMessage.", "내가(또는 봇이) 보낸 메시지로는 onKakaoMessage가 호출되지 않아요.")
                    defaultValue = true
                    icon = Icon.ACCOUNT_BOX
                    iconTintColor = color { COLOR_ICON }
                }
                info {
                    id = "compat_events"
                    title = tr("Compatibility events", "호환 이벤트")
                    description = tr(
                        "Also fires StarLight's own onMessage and onMessageDeleted from what the database gives.",
                        "StarLight 기본 이벤트(onMessage, onMessageDeleted)도 데이터베이스로 받은 내용으로 호출해요.",
                    )
                    icon = Icon.LAYERS
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_COMPAT_MESSAGE
                    title = tr("onMessage compatibility event", "onMessage 호환 이벤트")
                    description = tr(
                        "Also fires StarLight's onMessage from the database. Don't allow it in projects that use StarLight's own onMessage: messages would come twice.",
                        "데이터베이스로 받은 메시지로 StarLight의 onMessage도 호출합니다. StarLight 기본 onMessage를 쓰는 프로젝트에서 함께 켜면 메시지를 두 번 받아요.",
                    )
                    defaultValue = false
                    icon = Icon.LAYERS
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_COMPAT_DELETE
                    title = tr("onMessageDeleted compatibility event", "onMessageDeleted 호환 이벤트")
                    defaultValue = false
                    icon = Icon.DELETE
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_SENDING
            title = tr("Sending", "전송")
            textColor = color { COLOR_SUB_TITLE }
            items {
                seekbar {
                    id = CONF_SEND_INTERVAL
                    title = tr("Send interval (ms)", "전송 간격 (ms)")
                    description = tr("Minimum time between messages; KakaoTalk drops replies that come too fast.", "메시지 사이의 최소 간격입니다. 너무 빠르면 카카오톡이 답장을 버려요.")
                    min = 100
                    max = 3000
                    defaultValue = DEF_SEND_INTERVAL
                    icon = Icon.SEND
                    iconTintColor = color { COLOR_ICON }
                }
                textList {
                    id = "sendable_rooms"
                    title = tr("Rooms that can be sent to", "전송 가능한 방")
                    description = tr(
                        "Sending needs a notification from the room since StarLight started.",
                        "StarLight 시작 후 알림을 받은 방에만 보낼 수 있어요.",
                    )
                    icon = Icon.SEND
                    copyable = true
                    emptyText = tr("No notifications seen yet", "아직 받은 알림이 없어요")
                    lines = sendableRooms()
                    bind(KakaoDBRuntime.version.map { sendableRooms() })
                }
            }
        }
        category {
            id = CATEGORY_ACCOUNT
            title = tr("Account", "계정")
            textColor = color { COLOR_SUB_TITLE }
            items {
                string {
                    id = CONF_SELF_ID
                    title = tr("My user id", "내 사용자 ID")
                    description = tr("Leave empty to find it in the database.", "비워 두면 데이터베이스에서 찾아요.")
                    inputType = InputType.TYPE_CLASS_NUMBER
                    require = { value -> if (value.isBlank() || value.trim().toLongOrNull() != null) null else tr("Enter a number", "숫자를 입력하세요") }
                    icon = Icon.KEY
                    iconTintColor = color { COLOR_ICON }
                }
                string {
                    id = CONF_SELF_NAME
                    title = tr("My name", "내 이름")
                    description = tr("Leave empty to use the name in the database.", "비워 두면 데이터베이스의 이름을 사용해요.")
                    icon = Icon.ACCOUNT_BOX
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_LOGGING
            title = tr("Logging", "로그")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_LOG_EVENTS
                    title = tr("Log events", "이벤트 로그 표시")
                    description = tr("Logs every event handed to projects.", "프로젝트에 전달하는 모든 이벤트를 로그에 남깁니다.")
                    defaultValue = false
                    icon = Icon.LIST_BULLETED
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_VERBOSE
                    title = tr("Debug logs", "디버그 로그")
                    description = tr("Logs every row read from the database.", "데이터베이스에서 읽은 모든 기록을 로그에 남깁니다.")
                    defaultValue = false
                    icon = Icon.DEVELOPER_MODE
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
    }

    private companion object {
        const val KAKAO_TALK = "com.kakao.talk"
        const val DEFAULT_DB_PATH = "/data/data/com.kakao.talk/databases"

        const val CATEGORY_GENERAL = "general"
        const val CATEGORY_CONTROL = "control"
        const val CATEGORY_MESSAGE = "message"
        const val CATEGORY_SENDING = "sending"
        const val CATEGORY_ACCOUNT = "account"
        const val CATEGORY_LOGGING = "logging"

        const val CONF_DB_PATH        = "db_path"
        const val CONF_ACCESS_MODE    = "access_mode"
        const val CONF_POLL_INTERVAL  = "poll_interval"
        const val CONF_AUTO_START     = "auto_start"
        const val CONF_IGNORE_OWN     = "ignore_own_messages"
        const val CONF_COMPAT_MESSAGE = "compat_on_message"
        const val CONF_COMPAT_DELETE  = "compat_on_message_deleted"
        const val CONF_SEND_INTERVAL  = "send_interval"
        const val CONF_SELF_ID        = "self_id"
        const val CONF_SELF_NAME      = "self_name"
        const val CONF_LOG_EVENTS     = "log_events"
        const val CONF_VERBOSE        = "verbose_logging"

        val POLL_INTERVALS = longArrayOf(0L, 500L, 1_000L, 2_000L, 5_000L)
        val POLL_INTERVAL_LABELS = listOf("0.5s", "1s", "2s", "5s")
        const val DEF_POLL_INTERVAL = 2

        const val DEF_SEND_INTERVAL = 600

        const val COLOR_TITLE     = "#FEE500"
        const val COLOR_SUB_TITLE = "#E0C200"
        const val COLOR_ICON      = "#FEE500"
    }
}
