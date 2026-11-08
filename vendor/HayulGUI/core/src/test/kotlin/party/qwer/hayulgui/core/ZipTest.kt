package party.qwer.hayulgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream

class ZipTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test fun buildAndReadBack() {
        val f = tmp.newFile("test.zip")
        Zip.Builder(f).finishTest(listOf("AndroidManifest.xml", "classes.dex")) { b ->
            b.putDeflated("AndroidManifest.xml", AxmlTestBuilder.miniManifest())
            b.putDeflated("classes.dex", ByteArray(1024) { 7 })
            b.putDeflated("lib/x.so", ByteArray(12345), Zip.METHOD_STORE)
        }
        run {
            val all = f.readBytes()
            println("ZIPSZ " + all.size)
            println("TAIL " + all.takeLast(40).joinToString("") { "%02x".format(it) })
            println("HEAD " + all.take(48).joinToString("") { "%02x".format(it) })
        }
        val entries = Zip.readEntries(f)
        assertEquals(3, entries.size)
        val m = entries["AndroidManifest.xml"]!!
        assertEquals(Zip.METHOD_DEFLATE, m.method)
        // manifest 파싱 가능 확인
        RandomAccessFile(f, "r").use { raf ->
            val comp = ByteArray(m.compSize.toInt())
            raf.seek(m.dataOffset); raf.readFully(comp)
            val raw = if (m.method == Zip.METHOD_STORE) comp else InflaterInputStream(comp.inputStream(), java.util.zip.Inflater(true)).readAllBytes()
            val axml = Axml.parse(raw)
            assertEquals("com.example.app", axml.readAttribute("manifest", "package"))
        }
        val so = entries["lib/x.so"]!!
        assertEquals(Zip.METHOD_STORE, so.method)
        assertEquals(0L, so.dataOffset % 4L)
    }

    // Builder 에 close-safe helper 없음 → finish 수동 호출
    private inline fun Zip.Builder.finishTest(names: List<String>, block: (Zip.Builder) -> Unit) {
        try { block(this) } finally { finish() }
    }
}
