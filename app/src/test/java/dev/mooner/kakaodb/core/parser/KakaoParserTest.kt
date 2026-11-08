package dev.mooner.kakaodb.core.parser

import dev.mooner.kakaodb.core.model.ChatType
import dev.mooner.kakaodb.core.model.FeedType
import dev.mooner.kakaodb.core.sqlite.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoParserTest {

    private class MapRow(private val values: Map<String, Any?>) : Row {
        override fun has(column: String) = column in values
        override fun value(column: String) = values[column]
        override fun value(index: Int) = values.values.toList()[index]
    }

    private fun chat(type: Int, message: String = "", attachment: String? = null, extra: Map<String, Any?> = emptyMap()) =
        MapRow(mapOf(
            "_id" to 1L, "id" to 100L, "type" to type, "chat_id" to 5L, "user_id" to 7L,
            "message" to message, "attachment" to attachment, "created_at" to 1700000000L, "v" to """{"enc":0}""",
        ) + extra).toChatData()

    @Test
    fun readsThreadColumns() {
        // A thread message as KakaoTalk stores it: a plain TEXT row, the answered message only in thread_id.
        val inThread = chat(1, "답글", extra = mapOf("thread_id" to 3963860340598970370L, "scope" to 3L))
        assertEquals(3963860340598970370L, inThread.threadId)
        assertEquals(3, inThread.scope)
        assertTrue(inThread.isThreadMessage)
        assertNull(inThread.reply)

        val inRoom = chat(1, "일반", extra = mapOf("thread_id" to null, "scope" to 1L))
        assertNull(inRoom.threadId)
        assertEquals(1, inRoom.scope)
        assertFalse(inRoom.isThreadMessage)

        assertNull(chat(1, extra = mapOf("thread_id" to 0L)).threadId)
        // The message a thread hangs from, should it point at itself, isn't in the thread.
        assertFalse(chat(1, extra = mapOf("thread_id" to 100L)).isThreadMessage)

        // KakaoTalk versions without threads.
        val old = chat(1, "옛 버전")
        assertNull(old.threadId)
        assertNull(old.scope)
        assertFalse(old.isThreadMessage)
    }

    @Test
    fun keepsBothReplyAndThreadOfAReplyInAThread() {
        val chat = chat(26, "답장", """{"src_logId":3963860387064014849,"src_userId":6745167440951600721,"src_linkId":430278541,"src_type":1}""",
            extra = mapOf("thread_id" to 3963860340598970370L, "scope" to 3L))
        assertEquals(3963860387064014849L, chat.reply?.logId)
        assertEquals(6745167440951600721L, chat.reply?.userId)
        assertEquals(3963860340598970370L, chat.threadId)
    }

    @Test
    fun parsesMentionsWithNumberOrStringIds() {
        val chat = chat(1, "@철수 @영희 안녕", """{"mentions":[{"user_id":123,"at":[1],"len":2},{"user_id":"18000000000000030","at":[2,3],"len":2}]}""")
        assertEquals(listOf(123L, 18000000000000030L), chat.mentions.map { it.userId })
        assertEquals(listOf(2, 3), chat.mentions[1].positions)
    }

    @Test
    fun parsesReplies() {
        val chat = chat(26, "답장", """{"attach_only":false,"src_logId":"3000000000000000001","src_userId":42,"src_message":"원본","src_type":1,"mentions":[]}""")
        assertEquals(ChatType.REPLY, chat.type)
        val reply = chat.reply!!
        assertEquals(3000000000000000001L, reply.logId)
        assertEquals(42L, reply.userId)
        assertEquals("원본", reply.message)
    }

    @Test
    fun parsesMedia() {
        val photo = chat(2, "사진", """{"url":"https://p/1.jpg","w":640,"h":480,"thumbnailUrl":"https://p/t.jpg","s":1234,"mt":"image/jpeg"}""")
        assertEquals("https://p/1.jpg", photo.media.single().url)
        assertEquals(640, photo.media.single().width)

        val multi = chat(27, "사진 2장", """{"imageUrls":["https://p/a","https://p/b"],"thumbnailUrls":["t1","t2"],"wl":[1,2],"hl":[3,4],"sl":[5,6],"mtl":["image/png","image/jpeg"]}""")
        assertEquals(listOf("https://p/a", "https://p/b"), multi.media.map { it.url })
        assertEquals(listOf(3, 4), multi.media.map { it.height })

        val file = chat(18, "파일", """{"name":"a.pdf","url":"https://f/a.pdf","size":99,"expire":1700100000}""")
        assertEquals("a.pdf", file.media.single().name)
        assertEquals(1700100000L, file.media.single().expiresAt)

        val map = chat(16, "지도", """{"lat":"37.5","lng":127.0,"a":"서울","t":"광화문"}""")
        assertEquals(37.5, map.location!!.latitude, 0.0)
        assertEquals("광화문", map.location?.title)
    }

    @Test
    fun parsesFeedsAndKeepsUnknownFields() {
        val feed = chat(0, """{"feedType":15,"prevHost":{"userId":1,"nickName":"A"},"newHost":{"userId":2,"nickName":"B"},"extra":1}""").feed!!
        assertEquals(FeedType.OPENLINK_HAND_OVER_HOST, feed.feedType)
        assertEquals(2L, feed.newHost!!.userId)
        assertTrue("extra" in feed.raw!!)

        val hidden = chat(0, """{"feedType":13,"logIds":[11,12],"logId":12,"hidden":true}""").feed!!
        assertEquals(listOf(11L, 12L), hidden.targetLogIds)
    }

    @Test
    fun ignoresDeletedFlagInType() {
        val chat = chat(0x4000 or 1, "지워진 메시지")
        assertTrue(chat.deleted)
        assertEquals(ChatType.TEXT, chat.type)
        assertNull(chat.feed)
    }
}
