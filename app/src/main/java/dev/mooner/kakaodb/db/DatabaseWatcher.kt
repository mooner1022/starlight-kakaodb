package dev.mooner.kakaodb.db

import android.os.FileObserver
import dev.mooner.kakaodb.core.DecoderOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** When to check the database: on file changes, and every [pollMillis] in case those aren't seen (0: never). */
object DatabaseWatcher {

    /**
     * How long after the last change one more check runs. SQLite makes a commit visible right after writing it to the
     * -wal, and the check the write itself set off may come just before that; nothing else would signal it then.
     */
    private const val SETTLE_MILLIS = 50L

    /**
     * KakaoTalk commits to the -wal files; the main files change on checkpoints. crypto_user_database holds the friend
     * list of newer KakaoTalk versions.
     */
    val WATCHED_FILES = listOf(DecoderOptions.MAIN_DB, DecoderOptions.SUB_DB, DecoderOptions.USER_DB)
        .flatMap { listOf(it, "$it-wal") }
        .toSet()

    private const val MASK = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.MOVED_TO

    /**
     * [changes] (from the access mode, see [DatabaseAccess.fileChanges]) as they come, plus one more check when they
     * settle, plus the polling ticks. The decoder merges triggers that come while it checks, and spaces its checks
     * (DecoderOptions.minPollSpacingMillis), so passing every change on costs little.
     *
     * Debouncing instead held every message back until the files had been quiet for a while, at least that long.
     */
    fun triggers(changes: Flow<Unit>, pollMillis: Long): Flow<Unit> =
        merge(changes.withSettleCheck(SETTLE_MILLIS), ticks(pollMillis))

    /** Each value at once, and one more [settleMillis] after the last of a burst. */
    internal fun Flow<Unit>.withSettleCheck(settleMillis: Long): Flow<Unit> = channelFlow {
        var settle: Job? = null
        collect {
            trySend(Unit)
            settle?.cancel()
            settle = launch {
                delay(settleMillis)
                send(Unit)
            }
        }
    }

    /**
     * inotify on the directory from this process, which needs read access to it. Without that, watching fails
     * silently and only the polling finds changes.
     */
    fun localChanges(dir: File): Flow<Unit> = callbackFlow {
        val path = dir.absolutePath
        val listener: (String) -> Unit = { name ->
            if (name in WATCHED_FILES)
                trySend(Unit)
        }
        SharedObservers.add(path, listener)
        awaitClose { SharedObservers.remove(path, listener) }
    }

    /**
     * One FileObserver per directory for the whole process, shared by every watch on it. FileObserver keeps a single
     * observer per inotify watch, and the kernel gives a process the same watch for the same directory: a second
     * FileObserver would take the first one's events, and stopping either would stop both. That happens on a
     * reconnect when the new watch starts before the old one has stopped.
     */
    private object SharedObservers {

        private class Entry(val observer: FileObserver, val listeners: MutableSet<(String) -> Unit>)

        private val entries = hashMapOf<String, Entry>()

        @Synchronized
        fun add(dir: String, listener: (String) -> Unit) {
            val existing = entries[dir]
            if (existing != null) {
                existing.listeners += listener
                return
            }
            val listeners = CopyOnWriteArraySet<(String) -> Unit>().apply { add(listener) }
            @Suppress("DEPRECATION") // FileObserver(File) needs API 29.
            val observer = object : FileObserver(dir, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null)
                        for (each in listeners)
                            each(path)
                }
            }
            entries[dir] = Entry(observer, listeners)
            observer.startWatching()
        }

        @Synchronized
        fun remove(dir: String, listener: (String) -> Unit) {
            val entry = entries[dir] ?: return
            if (!entry.listeners.remove(listener))
                return
            if (entry.listeners.isEmpty()) {
                entry.observer.stopWatching()
                entries -= dir
            }
        }
    }

    private fun ticks(pollMillis: Long): Flow<Unit> =
        if (pollMillis <= 0)
            emptyFlow()
        else
            flow {
                while (true) {
                    delay(pollMillis)
                    emit(Unit)
                }
            }
}
