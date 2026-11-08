package party.qwer.hayulgui.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date

/**
 * HayulGUI 영속 서명키 (spec §5.1).
 *
 * 생성은 표준라이브러리만으로 완결: RSA-2048 keypair + minimal ASN.1/DER 인코더로
 * self-signed X.509 (SHA256withRSA) 직접 작성. 저장은 PKCS#8 DER + cert DER.
 * JKS/PKCS12 import/export 도 가능 (키 이사/백업 대응).
 */
object SigningKey {

    const val KEY_FILE = "hayulgui-key.pk8.der"
    const val CERT_FILE = "hayulgui-cert.x509.der"
    private const val VALIDITY_DAYS = 10000

    data class KeySet(val keyPair: KeyPair, val cert: X509Certificate) {
        val fingerprint: String
            get() = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

        fun der(): ByteArray = cert.encoded
    }

    // ------------------------------------------------------------ persistence

    /** filesDir 에 키가 있으면 로드, 없으면 생성해 저장하고 반환. */
    fun loadOrCreate(filesDir: File, log: (String) -> Unit = {}): KeySet {
        val keyFile = File(filesDir, KEY_FILE)
        val certFile = File(filesDir, CERT_FILE)
        if (keyFile.exists() && certFile.exists()) {
            return try {
                val ks = loadFrom(keyFile.readBytes(), certFile.readBytes())
                log("서명키 로드됨 (지문 ${ks.fingerprint})")
                ks
            } catch (t: Throwable) {
                log("키 로드 실패(${t.message}) — 새로 생성합니다")
                generateAndSave(filesDir, log)
            }
        }
        return generateAndSave(filesDir, log)
    }

    fun exists(filesDir: File) =
        File(filesDir, KEY_FILE).exists() && File(filesDir, CERT_FILE).exists()

    private fun generateAndSave(filesDir: File, log: (String) -> Unit): KeySet {
        log("RSA-2048 키쌍 + self-signed 인증서 생성 중…")
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val cert = SelfSignedCertificate.generate(pair, "CN=HayulGUI", VALIDITY_DAYS)
        File(filesDir, KEY_FILE).writeBytes(pair.private.encoded)
        File(filesDir, CERT_FILE).writeBytes(cert.encoded)
        val ks = KeySet(pair, cert)
        log("서명키 생성 완료 (지문 ${ks.fingerprint})")
        return ks
    }

    fun loadFrom(pkcs8Der: ByteArray, certDer: ByteArray): KeySet {
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
        val cert = certificateFromDer(certDer)
        val pub = kf.generatePublic(X509EncodedKeySpec(cert.publicKey.encoded))
        return KeySet(KeyPair(pub, priv), cert)
    }

    fun certificateFromDer(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(der)) as X509Certificate

    /** JKS/PKCS12 keystore 에서 첫 RSA 키 항목을 읽어 HayulGUI 키셋으로. */
    fun importFromKeystore(storeBytes: ByteArray, password: CharArray, type: String): KeySet {
        val ks = KeyStore.getInstance(type)
        ks.load(java.io.ByteArrayInputStream(storeBytes), password)
        for (alias in ks.aliases()) {
            if (!ks.isKeyEntry(alias)) continue
            val priv = ks.getKey(alias, password)
            val chain = ks.getCertificateChain(alias)
            val cert = (chain?.firstOrNull() ?: ks.getCertificate(alias)) as? X509Certificate ?: continue
            if (priv is java.security.PrivateKey && cert.publicKey.algorithm == "RSA") {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(cert.publicKey.encoded))
                return KeySet(KeyPair(pub, priv), cert)
            }
        }
        throw IllegalArgumentException("RSA 키 항목을 찾을 수 없습니다")
    }

    /** export: PKCS12 바이트 (alias "hayulgui") */
    fun exportPkcs12(ks: KeySet, password: CharArray): ByteArray {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("hayulgui", ks.keyPair.private, password, arrayOf(ks.cert))
        val out = ByteArrayOutputStream()
        store.store(out, password)
        return out.toByteArray()
    }

    /** apksig 용 raw keypair config */
    fun signerConfigOf(ks: KeySet, name: String = "hayulgui") =
        com.android.apksig.ApkSigner.SignerConfig.Builder(name, ks.keyPair.private, listOf(ks.cert)).build()

    // -------------------------------------------------------- DER encoder

    /** minimal ASN.1/DER self-signed X.509 생성기. */
    internal object SelfSignedCertificate {
        /** SHA256-RSA digest algorithm identifier OID */
        private val SHA256_RSA_OID = byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
        )

        // 2.5.4.3 commonName
        private val CN_OID = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

        fun encode(keyPair: KeyPair, subject: String, validityDays: Int): ByteArray {
            val notBefore = Date(System.currentTimeMillis() - 24L * 3600_000)
            val notAfter = Date(System.currentTimeMillis() + validityDays * 24L * 3600_000L)
            val serial = BigInteger.valueOf(System.currentTimeMillis()).or(BigInteger.ONE)
            val spki = keyPair.public.encoded

            val name = encodeName(subject)
            val sigAlg = der(0x30, SHA256_RSA_OID + byteArrayOf(0x05, 0x00))
            val validity = der(0x30, utc(notBefore) + utc(notAfter))
            val version = byteArrayOf(0xA0.toByte(), 3, 2, 1, 2) // [0]{ INTEGER 2 }

            // TBSCertificate ::= version, serialNumber, signature, issuer, validity, subject, SPKI
            val tbs = der(
                0x30,
                version + derInt(serial) + sigAlg + name + validity + name + spki,
            )
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(keyPair.private)
            sig.update(tbs)
            return der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + sig.sign()))
        }

        fun generate(keyPair: KeyPair, subject: String, validityDays: Int): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(encode(keyPair, subject, validityDays)))
                as X509Certificate

        private fun encodeName(subject: String): ByteArray {
            val cn = subject.substringAfterLast("CN=", subject).trim()
            val ascii = cn.toByteArray(Charsets.US_ASCII)
            val atv = der(0x30, CN_OID + der(0x13, ascii)) // PrintableString
            return der(0x30, der(0x31, atv))
        }

        private fun utc(d: Date): ByteArray {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.time = d
            val year = cal.get(java.util.Calendar.YEAR)
            return if (year >= 2050 || year < 1950) {
                // GeneralizedTime (X.509 규칙: 2050- 이후 / 1950- 이전)
                der(0x18, java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'")
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(d).toByteArray(Charsets.US_ASCII))
            } else {
                der(0x17, java.text.SimpleDateFormat("yyMMddHHmmss'Z'")
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(d).toByteArray(Charsets.US_ASCII))
            }
        }

        private fun derInt(v: BigInteger): ByteArray = der(0x02, v.toByteArray())

        /** tag + DER 길이 + content */
        private fun der(tag: Int, content: ByteArray): ByteArray = when {
            content.size < 0x80 -> byteArrayOf(tag.toByte(), content.size.toByte()) + content
            content.size < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), content.size.toByte()) + content
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte()) + content
        }
    }
}
