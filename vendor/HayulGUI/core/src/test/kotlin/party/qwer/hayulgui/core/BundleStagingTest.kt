package party.qwer.hayulgui.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 서쪽 번들(xapk/apkm/apks) staging 포맷 호환 검증.
 * 실제 서드파티 산물 없이도: 번들 zip 을 조립해서 base 인식 + 패키지 필터를 확인한다.
 */
class BundleStagingTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    /** mini manifest 를 AndroidManifest.xml 로 가진 fake apk bytes. */
    private fun apkBytes(pkg: String): ByteArray =
        java.io.ByteArrayOutputStream().let { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml"))
                z.write(AxmlTestBuilder.miniManifest(packageName = pkg))
                z.closeEntry()
            }
            bos.toByteArray()
        }

    private fun bundle(name: String, entries: List<Pair<String, ByteArray>>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    @Test fun apkPureXapk() {
        val b = bundle("game.xapk", listOf(
            "meta.json" to "{}".toByteArray(),
            "base.apk" to apkBytes("com.game"),
            "split_config.arm64_v8a.apk" to apkBytes("com.game"),
            "split_config.ko.apk" to apkBytes("com.game"),
        ))
        val out = ApkSources.finishStaging(b, File(tmp.root, "st1"))
        assertEquals(3, out.size)
        assertEquals("base.apk", out[0].name)
    }

    @Test fun apkMirrorBaseDotXApk() {
        // apkmirror apks 번들: base.x.apk + *.x.apk 네이밍
        val b = bundle("app.apkm", listOf(
            "manifest.json" to "{}".toByteArray(),
            "base.x.apk" to apkBytes("com.mirror"),
            "config.arm64_v8a.x.apk" to apkBytes("com.mirror"),
        ))
        val out = ApkSources.finishStaging(b, File(tmp.root, "st2"))
        assertEquals(2, out.size)
        assertEquals("base.x.apk", out[0].name)
    }

    @Test fun installerJunkExcluded() {
        // apkm 안에 들어있는 pure_installer.apk 는 다른 패키지 → split 에서 빠져야 함
        val b = bundle("app2.apkm", listOf(
            "manifest.json" to "{}".toByteArray(),
            "pure_installer.apk" to apkBytes("org.apkpure.pureinstaller"),
            "base.apk" to apkBytes("com.app2"),
            "split_config.xxhdpi.apk" to apkBytes("com.app2"),
        ))
        val out = ApkSources.finishStaging(b, File(tmp.root, "st3"))
        assertEquals(2, out.size)
        assertEquals("base.apk", out[0].name)
        assertEquals(listOf("split_config.xxhdpi.apk"), out.drop(1).map { it.name })
    }

    /** apkpure 스타일 회귀: STORED + data-descriptor 항목은 ZipInputStream 은 거부하므로 ZipFile 로 푼다. */
    @Test fun storesWithDescriptorUnpacks() {
        val f = File(tmp.root, "storeddesc.xapk")
        val payload = "PAYLOAD".toByteArray()
        val name = "com.test.app.apk".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(payload) }.value
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = le16(v) + le16(v shr 16)
        java.io.RandomAccessFile(f, "rw").use { raf ->
            // local header: flag=0x08, sizes=0, then data + descriptor
            raf.write(le32(0x04034b50)); raf.write(le16(20)); raf.write(le16(0x0008)); raf.write(le16(0))
            raf.write(le16(0)); raf.write(le16(0)); raf.write(le32(0)); raf.write(le32(0)); raf.write(le32(0))
            raf.write(le16(name.size)); raf.write(le16(0)); raf.write(name); raf.write(payload)
            raf.write(le32(0x08074b50)); raf.write(le32(crc.toInt())); raf.write(le32(payload.size)); raf.write(le32(payload.size))
            val cdStart = raf.filePointer
            // central directory entry: sizes/offsets known here
            raf.write(le32(0x02014b50)); raf.write(le16(20)); raf.write(le16(20)); raf.write(le16(0x0008)); raf.write(le16(0))
            raf.write(le16(0)); raf.write(le16(0)); raf.write(le32(crc.toInt())); raf.write(le32(payload.size)); raf.write(le32(payload.size))
            raf.write(le16(name.size)); raf.write(le16(0)); raf.write(le16(0)); raf.write(le16(0)); raf.write(le16(0))
            raf.write(le32(0)); raf.write(le32(0)); raf.write(name)
            val cdSize = raf.filePointer - cdStart
            raf.write(le32(0x06054b50)); raf.write(le16(0)); raf.write(le16(0))  // disk, cd-disk
            raf.write(le16(1)); raf.write(le16(1))                                 // entries, total
            raf.write(le32(cdSize.toInt())); raf.write(le32(cdStart.toInt())); raf.write(le16(0))
        }
        val staging = File(tmp.root, "staging")
        val out = ApkSources.finishStaging(f, staging)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0].readBytes())
    }
}
