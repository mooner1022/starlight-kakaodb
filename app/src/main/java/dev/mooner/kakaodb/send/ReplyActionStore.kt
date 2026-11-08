package dev.mooner.kakaodb.send

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.mooner.kakaodb.utils.LOG
import dev.mooner.kakaodb.utils.tr

/**
 * The reply and mark-as-read actions of KakaoTalk's message notifications, by room.
 *
 * The database can't send, so sending goes through these actions the way StarLight does: a room can be sent to only
 * after a notification from it was seen (since StarLight started, and with the room's notifications on).
 *
 * KakaoTalk tags a room's notifications with the room id. That isn't documented, so it is checked: a notification
 * carries the log id of its message (`chatLogId`), and when the database reports the same log, the room it was in
 * must match the tag. A mismatch is logged and the tag is used for that room from then on.
 */
class ReplyActionStore {

    class RoomActions(
        val tag: String,
        val reply: Notification.Action,
        val read: Notification.Action?,
        val context: Context,
        /** The room name the notification showed. */
        val label: String?,
        val capturedAt: Long,
    )

    private val lock = Any()
    private val actions = hashMapOf<String, RoomActions>()
    /** Room id → notification tag, for rooms whose tag isn't their id. */
    private val tagOverrides = hashMapOf<Long, String>()
    /** Log ids seen in one source but not yet in the other, to correlate tags with room ids. */
    private val notifiedLogs = lruMap<Long, String>(CORRELATION_SIZE)
    private val storedLogs   = lruMap<Long, Long>(CORRELATION_SIZE)
    private val reportedMismatches = hashSetOf<String>()

    @Volatile
    var confirmedCount: Int = 0
        private set

    /** Takes the actions of a KakaoTalk message notification. */
    fun capture(sbn: StatusBarNotification, context: Context) {
        if (sbn.packageName != KAKAO_TALK)
            return
        val tag = sbn.tag ?: return
        val notification = sbn.notification ?: return
        val all = notification.actions ?: return
        val reply = all.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val read = all.firstOrNull { it !== reply && it.remoteInputs.isNullOrEmpty() }
        val extras = notification.extras
        val label = extras?.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_SUMMARY_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val logId = extras?.getLong(EXTRA_CHAT_LOG_ID, 0L) ?: 0L

        synchronized(lock) {
            actions[tag] = RoomActions(tag, reply, read, context.applicationContext ?: context, label, System.currentTimeMillis())
            if (logId != 0L) {
                val roomId = storedLogs.remove(logId)
                if (roomId != null) correlate(tag, roomId) else notifiedLogs[logId] = tag
            }
        }
    }

    /** Tells a message log the database found, to check notification tags against. */
    fun onStoredLog(logId: Long, roomId: Long) {
        synchronized(lock) {
            val tag = notifiedLogs.remove(logId)
            if (tag != null) correlate(tag, roomId) else storedLogs[logId] = roomId
        }
    }

    fun actionsFor(roomId: Long): RoomActions? =
        synchronized(lock) { actions[tagOf(roomId)] }

    /** Drops actions KakaoTalk canceled (it restarted, or the notification is gone for good). */
    fun invalidate(actions: RoomActions) {
        synchronized(lock) {
            if (this.actions[actions.tag] === actions)
                this.actions.remove(actions.tag)
        }
    }

    /** Rooms that can be sent to, with the name their notification showed. */
    fun sendableRooms(): List<Pair<String, String?>> =
        synchronized(lock) { actions.values.sortedByDescending { it.capturedAt }.map { it.tag to it.label } }

    fun clear() {
        synchronized(lock) {
            actions.clear()
            notifiedLogs.clear()
            storedLogs.clear()
        }
    }

    private fun tagOf(roomId: Long): String =
        tagOverrides[roomId] ?: roomId.toString()

    private fun correlate(tag: String, roomId: Long) {
        if (tag == roomId.toString()) {
            if (confirmedCount++ == 0)
                LOG.debug { "Notification tags match room ids (room $roomId)" }
            return
        }
        tagOverrides[roomId] = tag
        if (reportedMismatches.add(tag))
            LOG.warn {
                tr(
                    "A KakaoTalk notification tag ($tag) isn't the id of its room ($roomId): using the tag for that room.",
                    "카카오톡 알림 태그($tag)가 방 ID($roomId)와 달라요. 이 방에는 알림 태그를 사용할게요.",
                )
            }
    }

    private companion object {
        const val KAKAO_TALK = "com.kakao.talk"
        const val EXTRA_CHAT_LOG_ID = "chatLogId"
        const val CORRELATION_SIZE = 128

        fun <K, V> lruMap(maxSize: Int): LinkedHashMap<K, V> =
            object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
            }
    }
}
