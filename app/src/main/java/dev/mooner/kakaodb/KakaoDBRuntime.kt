package dev.mooner.kakaodb

import dev.mooner.kakaodb.core.DatabaseDecoder
import dev.mooner.kakaodb.script.KakaoClient
import dev.mooner.kakaodb.send.ReplyActionStore
import dev.mooner.kakaodb.send.SendQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** State shared by the plugin and the objects scripts get. */
object KakaoDBRuntime {

    private val mState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = mState.asStateFlow()

    /** Set while connected. */
    @Volatile
    var decoder: DatabaseDecoder? = null
        internal set

    @Volatile
    internal var sendQueue: SendQueue? = null

    val replies = ReplyActionStore()

    val client = KakaoClient()

    /** Events handed to projects since the plugin was enabled. */
    val dispatchedEvents = AtomicLong()

    /** Bumped when something the control panel shows changes (events, sendable rooms). */
    val version = MutableStateFlow(0L)

    val selfId: Long
        get() = decoder?.selfID ?: -1L

    internal fun setState(state: ConnectionState) {
        mState.value = state
    }

    internal fun bump() {
        version.value++
    }

    /** Queues [text] for [roomId]; false if the room can't be sent to. */
    fun send(roomId: Long, text: String): Boolean =
        sendQueue?.enqueue(roomId, text) ?: false

    fun markAsRead(roomId: Long): Boolean =
        SendQueue.markAsRead(replies, roomId)

    fun canSend(roomId: Long): Boolean =
        replies.actionsFor(roomId) != null
}

sealed class ConnectionState {

    data object Stopped : ConnectionState()

    data object Starting : ConnectionState()

    data class Running(
        val selfId: Long,
        val selfName: String,
        val since: Long,
        /** Problems that don't stop the connection: missing tables, unwritable -shm... */
        val warnings: List<String>,
    ) : ConnectionState()

    /** Was connected, but the connection broke and couldn't be reopened yet; the decoder keeps trying. */
    data class Reconnecting(val reason: String, val retryAt: Long) : ConnectionState()

    data class Failed(val reason: String) : ConnectionState()
}
