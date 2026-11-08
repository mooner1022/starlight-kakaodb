package dev.mooner.kakaodb.send

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import dev.mooner.kakaodb.utils.LOG
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends messages through notification reply actions, one at a time with at least [intervalMillis] between them:
 * KakaoTalk drops replies that come too fast.
 */
class SendQueue(
    private val store: ReplyActionStore,
    private val intervalMillis: () -> Long,
) {

    private class Request(val roomId: Long, val text: String)

    private val channel = Channel<Request>(MAX_PENDING)

    fun start(scope: CoroutineScope) =
        scope.launch {
            for (request in channel) {
                deliver(request)
                delay(intervalMillis())
            }
        }

    /** Queues [text] for [roomId]. False if the room can't be sent to or too many messages are waiting. */
    fun enqueue(roomId: Long, text: String): Boolean {
        if (store.actionsFor(roomId) == null) {
            LOG.warn {
                tr(
                    "Can't send to room $roomId: no notification from it was seen since StarLight started.",
                    "방 $roomId 에 보낼 수 없어요: StarLight 시작 후 이 방의 알림을 받은 적이 없어요.",
                )
            }
            return false
        }
        val queued = channel.trySend(Request(roomId, text)).isSuccess
        if (!queued)
            LOG.warn { tr("Send queue is full: message dropped.", "전송 대기열이 가득 차서 메시지를 버렸어요.") }
        return queued
    }

    fun close() {
        channel.close()
    }

    private fun deliver(request: Request) {
        val actions = store.actionsFor(request.roomId) ?: return
        val inputs = actions.reply.remoteInputs ?: return
        val results = Bundle().apply {
            for (input in inputs)
                putCharSequence(input.resultKey, request.text)
        }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            actions.reply.actionIntent.send(actions.context, 0, intent)
        } catch (_: PendingIntent.CanceledException) {
            store.invalidate(actions)
            LOG.warn {
                tr(
                    "Failed to send to room ${request.roomId}: the reply action expired. A new notification from the room is needed.",
                    "방 ${request.roomId} 에 전송하지 못했어요: 답장 기능이 만료됐어요. 이 방의 새 알림이 필요해요.",
                )
            }
        }
    }

    companion object {
        private const val MAX_PENDING = 256

        /** Marks the room as read through its notification's read action. */
        fun markAsRead(store: ReplyActionStore, roomId: Long): Boolean {
            val actions = store.actionsFor(roomId) ?: return false
            val read = actions.read ?: return false
            return try {
                read.actionIntent.send(actions.context, 1, null)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }
    }
}
