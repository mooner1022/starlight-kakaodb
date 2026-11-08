package party.qwer.hayulgui.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

/**
 * APK shared-uid 패치 엔진 (spec §5.3).
 *
 * base(+splits) 를 받아:
 *  1. base manifest: manifest@sharedUserId, application@appComponentFactory 만 surgical patch.
 *     (split 은 기존 sharedUserId 가 있을 때만 동일값으로 맞춤 — 실측상 없음)
 *  2. classesN.dex 뒤에 stub.dex 를 classes(N+1).dex 로 append.
 *  3. `hayulgui.cfg` 마커: 재패치 시 원본서명/원본factory 보존, idempotency.
 *  4. zipalign semantics 로 재조립 → apksig 서명(v2+v3[,v1]) → 검증.
 */
object PatchEngine {

    /** stub 과 공유하는 APK 루트 마커 파일명. */
    const val MARKER = "hayulgui.cfg"

    const val STUB_FACTORY_CLASS = "party.qwer.hayulgui.stub.PatcherAppComponentFactory"

    class Request(
        val packageName: String,
        val baseApk: File,
        val splits: List<File> = emptyList(),
        val sharedUserId: String,
        /** 이미 패치되지 않은 원본의 서명 인증서 DER (파일 입력 시 apk 검증으로 얻음) */
        val originalCertDer: ByteArray,
        val stubDex: ByteArray,
        val outDir: File,
        val key: SigningKey.KeySet,
    )

    class Result(
        val outputs: List<File>,
        val issues: List<String>,
        val rePatch: Boolean,
        val targetMinSdk: Int,
        val versionCode: String?,
    )

    /** base+split 들을 하나의 산출물 세트로 패치한다. */
    fun patch(req: Request, log: (String) -> Unit = {}): Result {
        req.outDir.mkdirs()
        val allFiles = buildList {
            add("base" to req.baseApk)
            req.splits.forEachIndexed { i, f -> add("split${i + 1}" to f) }
        }

        // 원본 manifest 미리 읽어 판단 정보 확보
        val baseManifest = readManifest(req.baseApk)
            ?: throw IllegalArgumentException("base.apk 에 AndroidManifest.xml 가 없습니다")
        val baseAxml = Axml.parse(baseManifest)
        val existingShared = baseAxml.readAttribute("manifest", "sharedUserId")
        val versionCode = baseAxml.readAttribute("manifest", "versionCode")
        val targetMinSdk = baseAxml.readAttribute("uses-sdk", "minSdkVersion")?.toIntOrNull() ?: 0
        val origFactory = baseAxml.readAttribute("application", "appComponentFactory")
        log("target=$existingShared vcode=$versionCode minSdk=$targetMinSdk factory=$origFactory")

        // 이미 패치된 set 인지 (idempotent 재패치)
        val firstCfg = readMarker(req.baseApk)
        val rePatch = firstCfg != null
        val origSigHex = firstCfg?.getProperty("sig")?.takeIf { it.isNotBlank() }
            ?: req.originalCertDer.toHexString()
        if (rePatch) log("재패치 감지: 원본 서명 보존 (${origSigHex.take(16)}…)")
        val factoryKey = firstCfg?.getProperty("factory") ?: origFactory ?: ""

        val outputs = ArrayList<File>()
        val issues = ArrayList<String>()

        allFiles.forEachIndexed { idx, (label, apk) ->
            val isBase = idx == 0
            val out = File(req.outDir, if (isBase) "base.apk" else apk.name)
            patchOne(apk, out, isBase, req.sharedUserId, req.stubDex, origSigHex, factoryKey,
                req.packageName, log, issues)
            // align+sign: apksigner 가 output 재조합. tmp 쓰고 swap.
            val tmp = File(req.outDir, ".signing-$label.apk")
            try {
                ApkSigning.sign(out, tmp, req.key, targetMinSdk)
                out.delete()
                check(tmp.renameTo(out)) { "output rename failed" }
            } catch (t: Throwable) {
                throw IllegalStateException("$label 서명 실패: ${t.message}", t)
            } finally {
                tmp.delete()
            }
            val check = ApkSigning.verify(out, targetMinSdk)
            if (!check.ok) issues += "$label 검증 이슈: ${check.issues}"
            log("$label → ${out.name} (${out.length() / 1024} KB) verify=${check.ok}")
            outputs.add(out)
        }
        writeInstallTxt(req, outputs, targetMinSdk)
        return Result(outputs, issues, rePatch, targetMinSdk, versionCode)
    }

    // ---------------------------------------------------------------- internals

    private fun patchOne(
        apk: File,
        out: File,
        isBase: Boolean,
        sharedUserId: String,
        stubDex: ByteArray,
        origSigHex: String,
        factoryKey: String,
        packageName: String,
        log: (String) -> Unit,
        issues: MutableList<String>,
    ) {
        val entries = Zip.readEntries(apk)
        RandomAccessFile(apk, "r").use { src ->
            Zip.Builder(out).use2 { builder ->
                // classes.dex=1, classesN.dex=N
                val dexIndex = entries.keys.mapNotNull { n ->
                    Regex("classes(\\d*)\\.dex").matchEntire(n)?.let { m ->
                        (if (m.groupValues[1].isEmpty()) 1 else m.groupValues[1].toInt()) to n
                    }
                }.toMap()
                var maxDex = dexIndex.keys.maxOrNull() ?: 1
                val alreadyPatched = entries.containsKey(MARKER)
                var skipDex: String? = null
                if (alreadyPatched) {
                    // previous stub dex 는 제거하고 다시 append
                    skipDex = dexIndex[maxDex]
                    maxDex -= 1
                    log("existing stub dex 제거: $skipDex")
                }

                for ((name, e) in entries) {
                    when {
                        name == MARKER -> { /* regenerated below */ }
                        skipDex != null && name == skipDex -> { /* skip old stub dex */ }
                        name == "AndroidManifest.xml" -> {
                            val raw = readBytes(src, e)
                            val axml = Axml.parse(
                                if (e.method == Zip.METHOD_STORE) raw else inflate(raw))
                            var touched = false
                            if (isBase) {
                                axml.setStringAttribute("manifest", "sharedUserId", sharedUserId)
                                axml.setStringAttribute("application", "appComponentFactory", STUB_FACTORY_CLASS)
                                touched = true
                            } else {
                                val has = axml.readAttribute("manifest", "sharedUserId") != null
                                if (has) {
                                    axml.setStringAttribute("manifest", "sharedUserId", sharedUserId)
                                    touched = true
                                    log("split manifest 의 sharedUserId 도 맞춤")
                                }
                                if (axml.readAttribute("application", "appComponentFactory") != null) {
                                    issues += "${apk.name}: split manifest 가 appComponentFactory 를 선언 — 확인 필요"
                                }
                            }
                            builder.putDeflated(name, axml.pack(), if (touched) Zip.METHOD_DEFLATE else Zip.METHOD_DEFLATE)
                        }
                        else -> builder.copyRaw(e, src)
                    }
                }
                // stub dex append (maxN+1)
                val stubName = if (maxDex + 1 <= 1) "classes.dex" else "classes${maxDex + 1}.dex"
                builder.putDeflated(stubName, stubDex)
                log("stub dex: $stubName")

                val cfg = Properties().apply {
                    setProperty("sig", origSigHex)
                    if (factoryKey.isNotEmpty()) setProperty("factory", factoryKey)
                    setProperty("pkg", packageName)
                    setProperty("sharedUserId", sharedUserId)
                    setProperty("ts", System.currentTimeMillis().toString())
                    setProperty("by", "HayulGUI")
                }
                builder.putDeflated(MARKER, Properties2.toBytes(cfg))
            }
        }
    }

    /** split install 시 필요한 install.txt 생성 */
    private fun writeInstallTxt(req: Request, outputs: List<File>, targetMinSdk: Int) {
        val txt = buildString {
            appendLine("HayulGUI patched set — ${req.packageName}")
            appendLine("sharedUserId: ${req.sharedUserId}")
            appendLine()
            appendLine("⚠ 먼저 대상 앱을 uninstall 하세요 (INSTALL_FAILED_UID_CHANGED 방지)")
            appendLine("   adb -s <serial> uninstall ${req.packageName}")
            appendLine()
            if (outputs.size == 1) {
                appendLine("설치: base.apk 를 file manager/adb 로 설치")
                appendLine("   adb -s <serial> install base.apk")
            } else {
                appendLine("split 설치: local install 경로 설치(uninstall 로 uid 정리된 뒤):")
                appendLine("   adb -s <serial> install-multiple -r ${outputs.joinToString(" ") { it.name }}")
            }
            appendLine()
            appendLine("PC 없이 설치: HayulGUI 설치 탭의 세션 설치(base+split 단일 커밋)를 사용")
        }
        File(req.outDir, "install.txt").writeText(txt)
    }

    // helpers
    private fun readBytes(raf: RandomAccessFile, e: Zip.Entry): ByteArray {
        val buf = ByteArray(e.compSize.toInt())
        raf.seek(e.dataOffset)
        raf.readFully(buf)
        return buf
    }

    private fun readManifest(apk: File): ByteArray? {
        val entries = Zip.readEntries(apk)
        val e = entries["AndroidManifest.xml"] ?: return null
        RandomAccessFile(apk, "r").use {
            val comp = ByteArray(e.compSize.toInt())
            it.seek(e.dataOffset); it.readFully(comp)
            return if (e.method == Zip.METHOD_STORE) comp else inflate(comp)
        }
    }

    internal fun readMarker(apk: File): Properties? = try {
        val entries = Zip.readEntries(apk)
        val e = entries[MARKER] ?: return null
        RandomAccessFile(apk, "r").use {
            val comp = ByteArray(e.compSize.toInt())
            it.seek(e.dataOffset); it.readFully(comp)
            val raw = if (e.method == Zip.METHOD_STORE) comp else inflate(comp)
            Properties().apply { load(raw.inputStream()) }
        }
    } catch (t: Throwable) {
        null
    }

    internal fun readEntryBytes(apk: File, name: String): ByteArray? {
        val entries = Zip.readEntries(apk)
        val e = entries[name] ?: return null
        RandomAccessFile(apk, "r").use {
            val comp = ByteArray(e.compSize.toInt())
            it.seek(e.dataOffset); it.readFully(comp)
            return if (e.method == Zip.METHOD_STORE) comp else inflate(comp)
        }
    }

    private fun inflate(deflated: ByteArray): ByteArray {
        // zip method 8 = raw deflate (no zlib wrapper)
        val inf = java.util.zip.InflaterInputStream(
            java.io.ByteArrayInputStream(deflated), java.util.zip.Inflater(true),
        )
        return inf.use { it.readBytes() }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /** Zip.Builder 에 Closeable 사용법 설탕. */
    private inline fun Zip.Builder.use2(block: (Zip.Builder) -> Unit) {
        try {
            block(this)
        } finally {
            this.finish()
        }
    }
}

private object Properties2 {
    fun toBytes(p: Properties): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        p.store(bo, "HayulGUI patch marker")
        return bo.toByteArray()
    }
}
