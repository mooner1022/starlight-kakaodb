package party.qwer.hayulgui.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 실제 대형 APK end-to-end. src/test/resources 또는 ref_project 에 샘플이
 * 있는 경우에만 실행되고, 아니면 조용히 skip 된다.
 */
class RealApkTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test fun patchKakaoBase() {
        val td = listOf(".", "..", "../..").map { File(it, "ref_project/testdata") }
            .firstOrNull { File(it, "kakao_base.apk").exists() }
            ?: File("ref_project/testdata")
        val src = File(td, "kakao_base.apk")
        Assume.assumeTrue("kakao_base.apk not present", src.exists())
        val stubDex = listOf(".", "..").map { File(it, "stub/build/stub-assets/stub.dex") }
            .firstOrNull { it.exists() } ?: File("stub/build/stub-assets/stub.dex")
        Assume.assumeTrue("stub.dex not built", stubDex.exists())

        val key = SigningKey.loadOrCreate(tmp.newFolder("keys")) {}
        val outDir = tmp.newFolder("out")
        val t0 = System.currentTimeMillis()
        val r = PatchEngine.patch(
            PatchEngine.Request(
                packageName = "com.kakao.talk",
                baseApk = src,
                splits = emptyList(),
                sharedUserId = "party.qwer.irisgui",
                originalCertDer = key.cert.encoded,
                stubDex = stubDex.readBytes(),
                outDir = outDir,
                key = key,
            ),
            log = { println("[real] $it") },
        )
        val out = r.outputs.first()
        println("[real] patched -> ${out.name} (${out.length() / 1024 / 1024}MB, " +
            "${System.currentTimeMillis() - t0}ms) issues=${r.issues}")

        val entries = Zip.readEntries(out)
        val cfg = PatchEngine.readMarker(out)!!
        assertEquals("party.qwer.irisgui", cfg.getProperty("sharedUserId"))

        val axml = Axml.parse(PatchEngine.readEntryBytes(out, "AndroidManifest.xml")!!)
        assertEquals("party.qwer.irisgui", axml.readAttribute("manifest", "sharedUserId"))
        // 실물 aapt2 파일은 resmap 을 쓰므로 sharedUserId 의 name 은 resmap index 여야
        // 한다(libandroidfw 의 id 조달 조회). pool ref 로 남아있으면 PMS 가 무시한다.
        val ids = axml.resourceMapIds()
        assertNotNull(ids)
        org.junit.Assert.assertTrue("resmap must contain 0x0101000b", ids!!.contains(0x0101000b))
        assertEquals(PatchEngine.STUB_FACTORY_CLASS,
            axml.readAttribute("application", "appComponentFactory"))

        // stub dex 가 마지막 classesN 으로 들어갔는가
        val n = Zip.readEntries(src).keys.count { it.matches(Regex("classes\\d*\\.dex")) }
        assertTrue("stub dex missing", entries.containsKey("classes${n + 1}.dex"))

        val chk = ApkSigning.verify(out, 21)
        assertTrue("verify: ${chk.issues}", chk.ok)
        assertArrayEquals(key.cert.encoded, chk.certDer)
    }
}
