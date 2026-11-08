package dev.mooner.kakaodb.setup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import party.qwer.hayulgui.core.ApkInstaller
import party.qwer.hayulgui.core.ApkSigning
import party.qwer.hayulgui.core.ApkSources
import party.qwer.hayulgui.core.Axml
import party.qwer.hayulgui.core.PatchEngine
import party.qwer.hayulgui.core.SigningKey
import party.qwer.hayulgui.core.Zip
import java.io.File
import kotlin.concurrent.thread

/** An independent installer keeps its signing key while KakaoTalk and the helper are reinstalled. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var patchHelper: Button
    private lateinit var installKakao: Button
    private lateinit var installHelper: Button
    private lateinit var exportKey: Button
    private lateinit var importKey: Button
    private var keyPassword: CharArray? = null
    private val output get() = File(filesDir, "patched")
    private val keyDir get() = File(filesDir, "signing")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        status = TextView(this).apply {
            text = "카카오톡 APK 또는 XAPK/APKM/APKS를 선택하면 카카오톡과 내장 도우미를 같은 UID와 키로 패치합니다. 설치 전에 카카오톡 데이터를 백업하세요. 설정 도구를 삭제하면 서명 키도 삭제됩니다.\n\n" +
                "플러그인 업데이트로 도우미만 바뀌었다면 \"도우미만 다시 패치\"를 쓰세요. 내장 도우미 빌드: ${BuildConfig.SHARED_HELPER_BUILD}"
            textSize = 16f
        }
        column.addView(status)
        choose = button(column, "카카오톡 APK 선택 및 패치") { selectApk() }
        patchHelper = button(column, "도우미만 다시 패치") { patchHelperOnly() }
        installKakao = button(column, "패치된 카카오톡 설치") { install(KAKAO_DIR) }
        installHelper = button(column, "패치된 도우미 설치") { install(HELPER_DIR) }
        exportKey = button(column, "서명 키 백업") { keyFileOperation(true) }
        importKey = button(column, "서명 키 복원") { keyFileOperation(false) }
        setContentView(ScrollView(this).apply { addView(column) })
        migrateReadyMarker()
        updateButtons(false)
    }

    /** Earlier versions patched both targets at once and marked the whole folder ready: carry that over to each. */
    private fun migrateReadyMarker() {
        val old = File(output, READY)
        if (!old.isFile) return
        for (dir in listOf(KAKAO_DIR, HELPER_DIR)) {
            val target = File(output, dir)
            if (target.isDirectory && !File(target, READY).exists())
                old.copyTo(File(target, READY))
        }
        old.delete()
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun updateButtons(busy: Boolean) {
        choose.isEnabled = !busy
        patchHelper.isEnabled = !busy && hasKey()
        installKakao.isEnabled = !busy && ready(KAKAO_DIR).isFile
        installHelper.isEnabled = !busy && ready(HELPER_DIR).isFile
        exportKey.isEnabled = !busy
        importKey.isEnabled = !busy
    }

    @Suppress("DEPRECATION")
    private fun selectApk() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK_APK)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_KEY || requestCode == IMPORT_KEY) {
            val password = keyPassword
            keyPassword = null
            try {
                if (resultCode != RESULT_OK || data?.data == null || password == null) return
                val uri = data.data!!
                if (requestCode == EXPORT_KEY) {
                    val key = readExistingKey()
                    val bytes = SigningKey.exportPkcs12(key, password)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("백업 파일을 열지 못했습니다")
                    status.text = "서명 키를 백업했습니다. 비밀번호와 파일을 안전한 곳에 보관하세요."
                } else {
                    check(!File(keyDir, SigningKey.KEY_FILE).exists() && !File(keyDir, SigningKey.CERT_FILE).exists()) {
                        "이미 서명 키가 있습니다. 기존 키를 덮어쓸 수 없습니다."
                    }
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("백업 파일을 열지 못했습니다")
                    val key = SigningKey.importFromKeystore(bytes, password, "PKCS12")
                    val probe = "kakaodb-key-check".toByteArray()
                    val signature = java.security.Signature.getInstance("SHA256withRSA")
                    signature.initSign(key.keyPair.private); signature.update(probe)
                    val signed = signature.sign()
                    signature.initVerify(key.cert.publicKey); signature.update(probe)
                    check(signature.verify(signed)) { "개인 키와 인증서가 일치하지 않습니다" }
                    check(keyDir.isDirectory || keyDir.mkdirs()) { "키 디렉터리를 만들지 못했습니다" }
                    File(keyDir, SigningKey.KEY_FILE).writeBytes(key.keyPair.private.encoded)
                    File(keyDir, SigningKey.CERT_FILE).writeBytes(key.cert.encoded)
                    status.text = "서명 키를 복원했습니다: ${key.fingerprint}"
                }
            } catch (e: Exception) {
                status.text = "서명 키 작업 실패: ${e.message ?: e}"
            } finally { password?.fill('\u0000') }
            return
        }
        val uri = data?.data ?: return
        if (requestCode != PICK_APK || resultCode != RESULT_OK) return
        runPatch("APK를 검증하고 패치하는 중입니다. 시간이 걸릴 수 있습니다.", { patch(uri) }) {
            "패치 완료. 기존 설치의 서명 또는 UID가 다르면 기존 앱을 제거한 뒤 설치하세요. 카카오톡과 도우미를 각각 설치하고 도우미를 한 번 실행하세요. 서명 지문: $it"
        }
    }

    /** Patches the bundled helper alone with the existing key: after a plugin update, KakaoTalk needn't be patched again. */
    private fun patchHelperOnly() {
        runPatch("도우미를 패치하는 중입니다.", {
            check(hasKey()) { "서명 키가 없습니다. 먼저 카카오톡을 패치하거나 서명 키를 복원하세요." }
            val key = readExistingKey()
            patchHelper(key)
            key.fingerprint
        }) {
            "도우미 패치 완료. \"패치된 도우미 설치\"로 설치한 뒤 StarLight에서 다시 연결하세요. 서명 지문: $it"
        }
    }

    private fun runPatch(progress: String, work: () -> String, done: (String) -> String) {
        updateButtons(true)
        status.text = progress
        thread(name = "kakaodb-patch") {
            val result = runCatching(work)
            runOnUiThread {
                status.text = result.fold(done) { "패치 실패: ${it.message ?: it}" }
                updateButtons(false)
            }
        }
    }

    private fun hasKey(): Boolean =
        File(keyDir, SigningKey.KEY_FILE).isFile && File(keyDir, SigningKey.CERT_FILE).isFile

    private fun ready(target: String): File =
        File(File(output, target), READY)

    private fun readExistingKey(): SigningKey.KeySet = SigningKey.loadFrom(
        File(keyDir, SigningKey.KEY_FILE).readBytes(), File(keyDir, SigningKey.CERT_FILE).readBytes(),
    )

    @Suppress("DEPRECATION")
    private fun keyFileOperation(export: Boolean) {
        val password = EditText(this).apply {
            hint = "백업 비밀번호 (8자 이상)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(if (export) "서명 키 백업" else "서명 키 복원")
            .setView(password).setNegativeButton("취소", null)
            .setPositiveButton("계속") { _, _ ->
                val chars = password.text.toString().toCharArray()
                if (chars.size < 8) { chars.fill('\u0000'); status.text = "8자 이상의 비밀번호를 입력하세요." }
                else {
                    keyPassword?.fill('\u0000')
                    keyPassword = chars
                    val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        if (export) putExtra(Intent.EXTRA_TITLE, "kakaodb-signing-key.p12")
                    }
                    startActivityForResult(intent, if (export) EXPORT_KEY else IMPORT_KEY)
                }
            }.show()
    }

    private fun patch(uri: Uri): String {
        val stage = File(cacheDir, "input")
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "input.apk"
        val input = ApkSources.stageUri(this, uri, name, stage)
        require(input.isNotEmpty() && ApkSources.packageOf(input.first()) == KAKAO) { "카카오톡 base APK가 아닙니다" }
        checkMinimumSdk(input.first())
        val original = ApkSigning.verify(input.first())
        require(original.ok && original.certDer != null) { "카카오톡 APK 서명을 검증하지 못했습니다: ${original.issues}" }
        for (split in input.drop(1)) {
            require(ApkSources.packageOf(split) == KAKAO) { "다른 패키지의 split: ${split.name}" }
            val check = ApkSigning.verify(split)
            require(check.ok && check.certDer?.contentEquals(original.certDer) == true) { "split 서명이 일치하지 않습니다: ${split.name}" }
        }
        keyDir.mkdirs()
        val keyFile = File(keyDir, SigningKey.KEY_FILE)
        val certFile = File(keyDir, SigningKey.CERT_FILE)
        val key = when {
            keyFile.exists() && certFile.exists() -> SigningKey.loadFrom(keyFile.readBytes(), certFile.readBytes())
            keyFile.exists() || certFile.exists() -> error("서명 키 파일이 불완전합니다. 기존 키를 복구하세요.")
            else -> SigningKey.loadOrCreate(keyDir)
        }
        patchTarget(KAKAO, KAKAO_DIR, input, original.certDer!!, key)
        patchHelper(key)
        return key.fingerprint
    }

    private fun patchHelper(key: SigningKey.KeySet) {
        val helper = File(cacheDir, "sharedd.apk")
        assets.open("sharedd.apk").use { source -> helper.outputStream().use { source.copyTo(it) } }
        val helperCert = ApkSigning.verify(helper)
        require(helperCert.ok && helperCert.certDer != null) { "내장 도우미 서명을 검증하지 못했습니다" }
        patchTarget(HELPER, HELPER_DIR, listOf(helper), helperCert.certDer!!, key)
    }

    /** Patches [apks] (base first) of [pkg] into [output]/[dir], marked ready only once every output verifies. */
    private fun patchTarget(pkg: String, dir: String, apks: List<File>, cert: ByteArray, key: SigningKey.KeySet) {
        // Left by versions that patched both into one folder at once.
        File(output, READY).delete()
        val dest = File(output, dir)
        dest.deleteRecursively()
        val stub = assets.open("stub.dex").use { it.readBytes() }
        val result = PatchEngine.patch(PatchEngine.Request(pkg, apks.first(), apks.drop(1), SHARED_UID, cert, stub, dest, key))
        require(result.issues.isEmpty()) { "패치 검증 실패: ${result.issues.joinToString()}" }
        for (apk in result.outputs) {
            val check = ApkSigning.verify(apk)
            require(check.ok && check.certDer?.contentEquals(key.cert.encoded) == true) { "출력 APK 서명 검증 실패: ${apk.name}" }
        }
        File(dest, READY).writeText(key.fingerprint)
    }

    private fun checkMinimumSdk(base: File) {
        val manifest = Zip.readEntry(base, "AndroidManifest.xml") ?: error("APK에 매니페스트가 없습니다")
        val minSdk = Axml.parse(manifest).readAttribute("uses-sdk", "minSdkVersion") ?: return
        val minApi = minSdk.toIntOrNull()
        if (minApi != null) {
            require(minApi <= Build.VERSION.SDK_INT) {
                "이 카카오톡 APK는 Android API $minApi 이상이 필요하지만 현재 기기는 API ${Build.VERSION.SDK_INT}입니다. 기기에 맞는 이전 버전의 APK 세트를 선택하세요."
            }
        } else {
            require(minSdk == Build.VERSION.CODENAME && minSdk != "REL") {
                "이 카카오톡 APK는 Android 프리뷰 ${minSdk}가 필요하지만 현재 기기는 ${Build.VERSION.CODENAME} (API ${Build.VERSION.SDK_INT})입니다."
            }
        }
    }

    private fun install(target: String) {
        val files = File(output, target).listFiles { f -> f.isFile && f.extension == "apk" }
            ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy { it.name }).orEmpty()
        if (!ready(target).isFile || files.isEmpty()) { status.text = "먼저 APK를 패치하세요."; return }
        val label = if (target == KAKAO_DIR) "카카오톡" else "도우미"
        updateButtons(true)
        status.text = "$label 설치 파일을 준비하는 중입니다."
        // The session gets a copy of every APK (well over 100MB for KakaoTalk): not on the main thread. ApkInstaller
        // reports back on the main thread.
        val app = applicationContext
        thread(name = "kakaodb-install") {
            ApkInstaller.install(app, files) { success, message ->
                status.text = if (success) "설치 완료: $label" else "설치 실패: $message"
                updateButtons(false)
            }
        }
    }

    companion object {
        private const val PICK_APK = 1
        private const val EXPORT_KEY = 2
        private const val IMPORT_KEY = 3
        private const val KAKAO = "com.kakao.talk"
        private const val HELPER = "dev.mooner.kakaodb.sharedd"
        private const val SHARED_UID = "dev.mooner.kakaodb.shared"
        private const val KAKAO_DIR = "kakao"
        private const val HELPER_DIR = "helper"
        private const val READY = "ready"
    }
}
