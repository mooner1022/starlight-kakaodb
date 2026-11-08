package party.qwer.hayulgui

import android.content.Context
import party.qwer.hayulgui.core.ApkSigning
import party.qwer.hayulgui.core.Artifacts
import party.qwer.hayulgui.core.Logx
import party.qwer.hayulgui.core.PatchEngine

/** UI 액션 백엔드 — 무거운 코어 호출을 한 군데로 모은다. */
object HayulOps {

    /** core assets 에 번들된 stub.dex 를 읽는다. */
    fun loadStubDex(context: Context): ByteArray =
        context.assets.open("stub.dex").use { it.readBytes() }

    /** HayulState 에 쌓인 입력/sharedUserId 로 패치 실행 → 산출물 등록. 백그라운드 호출. */
    fun patchCurrentInput(context: Context): PatchEngine.Result {
        val key = HayulState.ensureKey(context)
        val base = HayulState.baseFile
            ?: throw IllegalStateException("입력 APK 가 없습니다")
        val outDir = java.io.File(context.filesDir, "artifacts/_pending")
        outDir.deleteRecursively()
        HayulState.busy = true
        try {
            val origCert = runCatching {
                ApkSigning.verify(base, 0).certDer ?: key.cert.encoded
            }.getOrNull() ?: key.cert.encoded
            val r = PatchEngine.patch(
                PatchEngine.Request(
                    packageName = HayulState.inputPackage.ifBlank { "target" },
                    baseApk = base,
                    splits = HayulState.splitFiles,
                    sharedUserId = HayulState.sharedUserId,
                    originalCertDer = origCert,
                    stubDex = loadStubDex(context),
                    outDir = outDir,
                    key = key,
                ),
                log = { Logx.i(it) },
            )
            Artifacts.register(
                context.filesDir,
                HayulState.inputPackage.ifBlank { "target" },
                HayulState.sharedUserId,
                r.versionCode ?: "?",
                outDir,
            )
            return r
        } finally {
            HayulState.busy = false
        }
    }
}
