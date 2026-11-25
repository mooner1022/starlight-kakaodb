package dev.mooner.kakaodb.send

import kotlin.test.Test
import kotlin.test.assertEquals

/** KakaoTalk 26.10 puts the receiving account's id in front of the room id in its notification tags. */
class RoomIdOfTagTest {

    @Test
    fun `a 26-10 tag gives the room id after the account's`() {
        assertEquals("18452224509561830", roomIdOfTag("423005562_18452224509561830"))
    }

    @Test
    fun `an older tag is the room id itself`() {
        assertEquals("18452224509561830", roomIdOfTag("18452224509561830"))
    }

    @Test
    fun `a tag of another form stays as it is`() {
        for (tag in listOf("abc_123", "1_2_3", "_123", "123_", "chat"))
            assertEquals(tag, roomIdOfTag(tag), tag)
    }
}
