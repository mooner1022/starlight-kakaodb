package dev.mooner.kakaodb.setup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import party.qwer.hayulgui.core.ApkInstaller
import party.qwer.hayulgui.core.ApkSigning
import party.qwer.hayulgui.core.ApkSources
import party.qwer.hayulgui.core.Axml
import party.qwer.hayulgui.core.PatchEngine
import party.qwer.hayulgui.core.SigningKey
import party.qwer.hayulgui.core.Zip
import java.io.File
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The key and patch outputs belong to the plugin, not its replaceable assets. */
internal class EmbeddedSetup(
    context: Context,
    private val dataDir: File,
    private val asset: (String) -> File,
) {
    private val app = context.applicationContext
    private val output get() = File(dataDir, "patch-output")
    private val keyDir get() = File(dataDir, "signing")
    private val keyFile get() = File(keyDir, SigningKey.KEY_FILE)
    private val certFile get() = File(keyDir, SigningKey.CERT_FILE)
    fun hasKey() = keyFile.isFile && certFile.isFile

    private fun existingKey(): SigningKey.KeySet {
        check(hasKey()) { "서명 키가 없습니다. 기존 설정 도구의 키를 복원하거나 카카오톡을 먼저 패치하세요." }
        return SigningKey.loadFrom(keyFile.readBytes(), certFile.readBytes())
    }

    private suspend fun <T> work(block: () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock { block() }
    }

    suspend fun patch(uri: Uri): String = work {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "input.apk"
        val input = ApkSources.stageUri(app, uri, name, File(app.cacheDir, "kakaodb-setup-input"))
        require(input.isNotEmpty() && ApkSources.packageOf(input.first()) == KAKAO) { "카카오톡 base APK가 아닙니다" }
        checkMinimumSdk(input.first())
        val original = ApkSigning.verify(input.first())
        require(original.ok && original.certDer != null) { "카카오톡 APK 서명을 검증하지 못했습니다: ${original.issues}" }
        for (split in input.drop(1)) {
            require(ApkSources.packageOf(split) == KAKAO) { "다른 패키지의 split: ${split.name}" }
            val checked = ApkSigning.verify(split)
            require(checked.ok && checked.certDer?.contentEquals(original.certDer) == true) { "split 서명이 일치하지 않습니다: ${split.name}" }
        }
        check(keyDir.isDirectory || keyDir.mkdirs()) { "키 폴더를 만들지 못했습니다" }
        val key = when {
            hasKey() -> existingKey()
            keyFile.exists() || certFile.exists() -> error("서명 키 파일이 불완전합니다. 기존 키를 복구하세요.")
            else -> SigningKey.loadOrCreate(keyDir)
        }
        patchTarget(KAKAO, KAKAO_DIR, input, original.certDer!!, key)
        patchHelper(key)
        key.fingerprint
    }

    suspend fun patchHelper(): String = work {
        val key = existingKey()
        patchHelper(key)
        key.fingerprint
    }

    private fun patchHelper(key: SigningKey.KeySet) {
        val helper = asset("sharedd.apk")
        val checked = ApkSigning.verify(helper)
        require(checked.ok && checked.certDer != null) { "내장 도우미 서명을 검증하지 못했습니다" }
        patchTarget(HELPER, HELPER_DIR, listOf(helper), checked.certDer!!, key)
    }

    private fun patchTarget(pkg: String, target: String, apks: List<File>, cert: ByteArray, key: SigningKey.KeySet) {
        val dest = File(output, target)
        dest.deleteRecursively()
        val result = PatchEngine.patch(PatchEngine.Request(pkg, apks.first(), apks.drop(1), SHARED_UID, cert,
            asset("stub.dex").readBytes(), dest, key))
        require(result.issues.isEmpty()) { "패치 검증 실패: ${result.issues.joinToString()}" }
        for (apk in result.outputs) {
            val checked = ApkSigning.verify(apk)
            require(checked.ok && checked.certDer?.contentEquals(key.cert.encoded) == true) { "출력 APK 서명 검증 실패: ${apk.name}" }
        }
        File(dest, READY).writeText(key.fingerprint)
    }

    private fun checkMinimumSdk(base: File) {
        val manifest = Zip.readEntry(base, "AndroidManifest.xml") ?: error("APK에 매니페스트가 없습니다")
        val minSdk = Axml.parse(manifest).readAttribute("uses-sdk", "minSdkVersion") ?: return
        val api = minSdk.toIntOrNull()
        if (api != null) require(api <= Build.VERSION.SDK_INT) {
            "이 APK는 Android API $api 이상이 필요합니다. 현재 기기는 API ${Build.VERSION.SDK_INT}입니다."
        } else require(minSdk == Build.VERSION.CODENAME && minSdk != "REL") {
            "이 APK는 Android 프리뷰 $minSdk 가 필요합니다."
        }
    }

    suspend fun exportKey(uri: Uri, password: CharArray) = work {
        val bytes = SigningKey.exportPkcs12(existingKey(), password)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("백업 파일을 열지 못했습니다")
    }

    suspend fun importKey(uri: Uri, password: CharArray): String = work {
        check(!keyFile.exists() && !certFile.exists()) { "이미 서명 키가 있습니다. 기존 키를 덮어쓸 수 없습니다." }
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("백업 파일을 열지 못했습니다")
        val key = SigningKey.importFromKeystore(bytes, password, "PKCS12")
        val probe = "kakaodb-key-check".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key.keyPair.private); signature.update(probe)
        val signed = signature.sign()
        signature.initVerify(key.cert.publicKey); signature.update(probe)
        check(signature.verify(signed)) { "개인 키와 인증서가 일치하지 않습니다" }
        check(keyDir.isDirectory || keyDir.mkdirs()) { "키 폴더를 만들지 못했습니다" }
        try {
            keyFile.writeBytes(key.keyPair.private.encoded)
            certFile.writeBytes(key.cert.encoded)
        } catch (e: Exception) {
            keyFile.delete()
            certFile.delete()
            throw e
        }
        key.fingerprint
    }

    suspend fun install(target: String): String = withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                val dir = File(output, target)
                check(File(dir, READY).isFile) { tr("Patch the APKs first.", "먼저 APK를 패치하세요.") }
                val files = dir.listFiles { f -> f.isFile && f.extension == "apk" }
                    ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy { it.name })
                    ?.takeIf { it.isNotEmpty() } ?: error(tr("There are no patched APKs.", "패치된 APK가 없습니다."))
                suspendCancellableCoroutine { cont ->
                    ApkInstaller.install(app, files) { ok, message ->
                        if (cont.isActive) cont.resume(
                            if (ok) tr("Installed.", "설치 완료")
                            else tr("Installation failed: $message", "설치 실패: $message")
                        )
                    }
                }
            }
        }

    companion object {
        private val lock = Mutex()
        const val KAKAO_DIR = "kakao"
        const val HELPER_DIR = "helper"
        private const val KAKAO = "com.kakao.talk"
        private const val HELPER = "dev.mooner.kakaodb.sharedd"
        private const val SHARED_UID = "dev.mooner.kakaodb.shared"
        private const val READY = "ready"
    }
}
