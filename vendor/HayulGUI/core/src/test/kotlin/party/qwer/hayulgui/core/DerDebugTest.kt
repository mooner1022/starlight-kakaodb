package party.qwer.hayulgui.core
import org.junit.Test
import java.security.KeyPairGenerator
class DerDebugTest {
    @Test fun dumpDer() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(512) }
        val der = SigningKey.SelfSignedCertificate.encode(gen.generateKeyPair(), "CN=HayulGUI", 10000)
        println("CERTDER " + der.joinToString("") { "%02x".format(it) })
        try {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(der))
            println("PARSE OK")
        } catch (e: Exception) { println("PARSE FAIL " + e) }
    }
}
