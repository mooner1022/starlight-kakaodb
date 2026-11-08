package party.qwer.hayulgui

import android.content.Context
import party.qwer.hayulgui.core.Artifacts
import party.qwer.hayulgui.core.Logx
import party.qwer.hayulgui.core.SigningKey
import java.io.File

/**
 * HayulState — UI 가 공유하는 전역 상태. 앱 생명주기 동안 살아있는 단순 싱글턴.
 */
object HayulState {

    // ---- 키 상태 ----
    @Volatile var keySet: SigningKey.KeySet? = null
        private set

    val keyExists: Boolean get() = keySet != null

    /** thumbprint — cert DER 의 SHA-256 을 4글자씩 묶은 짧은 지문. */
    fun keyFingerprint(): String = try {
        keySet?.let { ks ->
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(ks.cert.encoded)
            d.take(8).joinToString(" ") { "%02X".format(it) }
        } ?: "—"
    } catch (t: Throwable) { "—" }

    fun loadKey(context: Context): SigningKey.KeySet? {
        val dir = context.filesDir
        keySet = if (SigningKey.exists(dir)) runCatching { SigningKey.loadOrCreate(dir) {} }.getOrNull() else null
        return keySet
    }

    fun ensureKey(context: Context): SigningKey.KeySet =
        keySet ?: SigningKey.loadOrCreate(context.filesDir) { Logx.i(it) }.also { keySet = it }

    /** 불러온 키(가져오기)를 filesDir 에 저장하고 상태 갱신. */
    fun putKey(context: Context, ks: SigningKey.KeySet) {
        val dir = context.filesDir
        File(dir, "key.pem").writeBytes(ks.keyPair.private.encoded)
        File(dir, "cert.pem").writeBytes(ks.cert.encoded)
        keySet = ks
    }

    fun exportPkcs12(ks: SigningKey.KeySet, pw: CharArray): ByteArray =
        SigningKey.exportPkcs12(ks, pw)

    fun importKey(bytes: ByteArray, pw: CharArray = "aaaaaa".toCharArray()): SigningKey.KeySet =
        SigningKey.importFromKeystore(bytes, pw, "PKCS12")

    // ---- 설정 (persisted) ----
    private fun prefs(context: Context) =
        context.getSharedPreferences("hayulgui", Context.MODE_PRIVATE)

    @Volatile var sharedUserId: String = DEFAULT_SHARED_ID

    fun loadPrefs(context: Context) {
        sharedUserId = prefs(context).getString("sharedId", DEFAULT_SHARED_ID) ?: DEFAULT_SHARED_ID
    }

    fun saveSharedId(context: Context, v: String) {
        sharedUserId = v
        prefs(context).edit().putString("sharedId", v).apply()
    }

    // ---- 입력 (패치 대상) ----
    @Volatile var inputFiles: List<File> = emptyList()
    @Volatile var inputPackage: String = ""
    val hasInput: Boolean get() = inputFiles.isNotEmpty()
    val baseFile: File? get() = inputFiles.firstOrNull()
    val splitFiles: List<File> get() = inputFiles.drop(1)

    // ---- 실행 ----
    @Volatile var busy: Boolean = false

    /** Downloads 항목 등으로 지정된 입력을 current key 로 패치 → 산출물 등록. 백그라운드 호출. */
    fun artifacts(context: Context): List<Artifacts.Meta> = Artifacts.list(context.filesDir)

    const val DEFAULT_SHARED_ID = "party.qwer.shared"

    fun resetInput() {
        inputFiles = emptyList()
        inputPackage = ""
    }
}
