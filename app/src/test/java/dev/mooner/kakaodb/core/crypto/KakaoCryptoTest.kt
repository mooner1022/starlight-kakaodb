package dev.mooner.kakaodb.core.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.Security
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class KakaoCryptoTest {

    /**
     * KakaoDBRouter (and libdream) derived keys with BouncyCastle's PBEWITHSHAAND256BITAES-CBC-BC. The plugin derives
     * them itself, so this pins it to BouncyCastle for every enc and for user ids of all shapes.
     */
    @Test
    fun derivesTheSameKeysAsBouncyCastle() {
        Security.addProvider(BouncyCastleProvider())
        val factory = SecretKeyFactory.getInstance("PBEWITHSHAAND256BITAES-CBC-BC", BouncyCastleProvider.PROVIDER_NAME)
        val userIds = listOf("0", "-1", "7", "1000000001", "18000000000000030", "123456789012345678")
        for (enc in 0..31) {
            for (userId in userIds) {
                val salt = KakaoCrypto.salt(userId, enc)
                val expected = factory.generateSecret(PBEKeySpec(KakaoCrypto.PASSWORD, salt, 2, 256)).encoded
                assertArrayEquals("enc=$enc userId=$userId", expected, KakaoCrypto.deriveKey(userId, enc))
            }
        }
    }

    @Test
    fun roundTripsAndRejectsForeignValues() {
        val text = "안녕하세요 {\"json\": true} 🎉"
        val encrypted = KakaoCrypto.encrypt(1000000001L, 31, text)
        assertEquals(text, KakaoCrypto.decryptOrNull(1000000001L, 31, encrypted))
        // Another user's key yields garbage padding, which must read as "not encrypted for this user".
        assertNull(KakaoCrypto.decryptOrNull(1000000002L, 31, encrypted))
        assertEquals("plain text", KakaoCrypto.decryptOrRaw(1000000001L, 31, "plain text"))
        assertEquals("{}", KakaoCrypto.decryptOrRaw(1000000001L, 31, "{}"))
    }
}
