package party.qwer.hayulgui.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * byte-level zip IO (APK 실측 범위: entries < 65536, 단일 entry < 4GB, zip64 읽기만 지원).
 *
 * [readEntries] 는 각 엔트리의 raw compressed stream 위치를 준다 — 압축 재없이
 * 그대로 복사할 수 있게. [Builder] 는 엔트리를 순서대로 쓰되 uncompressed entry 의
 * 데이터 시작을 (출처 alignment 승계로 ≥4) 정렬 배치한다 — zipalign semantics.
 */
object Zip {

    const val METHOD_STORE = 0
    const val METHOD_DEFLATE = 8
    private const val ALIGN_MIN = 4L
    private const val PAGE = 16384L

    data class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compSize: Long,
        val uncompSize: Long,
        /** 실제 데이터 오프셋 (로컬 헤더 기준 계산) */
        val dataOffset: Long,
        /** 원본 data-start mod PAGE — libs page-align 승계용 */
        val srcAlign: Long,
        val dosTime: Int,
        val dosDate: Int,
    )

    fun readEntries(file: File): LinkedHashMap<String, Entry> {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEocd(raf)
            var cdCount = readU2(raf, eocd + 10).toLong()
            var cdOff = readU4(raf, eocd + 16)

            val eocd64 = findZip64Eocd(raf, eocd)
            if (eocd64 != null) {
                cdOff = readU8(raf, eocd64 + 48)
                val cnt = readU2(raf, eocd64 + 32).toLong()
                if (cnt == 0xFFFFL) cdCount = readU8(raf, eocd64 + 20)
            }

            val map = LinkedHashMap<String, Entry>()
            var p = cdOff
            repeat(cdCount.toInt()) {
                check(readU4(raf, p) == 0x02014B50L) { "bad CD record @ $p" }
                val flags = readU2(raf, p + 8)
                val method = readU2(raf, p + 10)
                val dosTime = readU2(raf, p + 12)
                val dosDate = readU2(raf, p + 14)
                val crc = readU4(raf, p + 16)
                var compSize = readU4(raf, p + 20)
                var uncompSize = readU4(raf, p + 24)
                val nameLen = readU2(raf, p + 28)
                val extraLen = readU2(raf, p + 30)
                val commentLen = readU2(raf, p + 32)
                var localOff = readU4(raf, p + 42)
                val name = ByteArray(nameLen).also { raf.seek(p + 46); raf.readFully(it) }
                    .toString(Charsets.UTF_8)

                parseZip64Extra(ByteArray(extraLen).also {
                    raf.seek(p + 46 + nameLen); raf.readFully(it)
                }, compSize, uncompSize, localOff)?.let { (cs, us, lo) ->
                    compSize = cs; uncompSize = us; localOff = lo
                }

                check(readU4(raf, localOff) == 0x04034B50L) { "bad local header for $name" }
                val lNameLen = readU2(raf, localOff + 26)
                val lExtraLen = readU2(raf, localOff + 28)
                val dataOff = localOff + 30 + lNameLen + lExtraLen

                map[name] = Entry(name, method, flags, crc, compSize, uncompSize,
                    dataOff, dataOff % PAGE, dosTime, dosDate)
                p += 46 + nameLen + extraLen + commentLen
            }
            return map
        }
    }

    /** 엔트리를 쓰는Builder. finish() 호출 필수. */
    class Builder(outFile: File) {
        private val raf = RandomAccessFile(outFile, "rw").apply { setLength(0) }
        private data class CdItem(val entry: Entry, val localOffset: Long, val extra: ByteArray)
        private val cd = ArrayList<CdItem>(64)

        /** 원본 raw compressed stream 복사. alignment 승계. */
        fun copyRaw(entry: Entry, src: RandomAccessFile) {
            val align = desiredAlign(entry)
            val (start, pad) = place(entry.name, entry.method, entry.flags and 0x08.inv(), entry.crc,
                entry.compSize, entry.uncompSize, entry.dosTime, entry.dosDate, align)
            var remaining = entry.compSize
            val buf = ByteArray(65536)
            src.seek(entry.dataOffset)
            while (remaining > 0) {
                val n = minOf(remaining, buf.size.toLong()).toInt()
                val got = src.read(buf, 0, n)
                if (got <= 0) break
                raf.write(buf, 0, got)
                remaining -= got
            }
            cd.add(CdItem(entry.copy(flags = entry.flags and 0x08.inv()), start, ByteArray(pad)))
        }

        /** deflate 로 새 content. (dex/cfg/manifest 용) */
        fun putDeflated(name: String, data: ByteArray, methodWanted: Int = METHOD_DEFLATE,
                        time: Pair<Int, Int> = nowDos()) {
            var payload = data
            var method = METHOD_STORE
            if (methodWanted == METHOD_DEFLATE) {
                val out = ByteArrayOutputStream(data.size / 4 + 64)
                val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)  // raw deflate (zip method 8 규격)
                d.setInput(data)
                d.finish()
                val buf = ByteArray(32768)
                while (!d.finished()) out.write(buf, 0, d.deflate(buf))
                d.end()
                if (out.size() < data.size) {
                    payload = out.toByteArray()
                    method = METHOD_DEFLATE
                }
            }
            val crc = CRC32().apply { update(data) }.value  // CRC 는 항상 uncompressed 데이터 기준
            val entry = Entry(name, method, 0, crc, payload.size.toLong(), data.size.toLong(),
                0, 0, time.first, time.second)
            val (start, pad) = place(name, method, 0, crc, payload.size.toLong(), data.size.toLong(),
                time.first, time.second, if (method == METHOD_STORE) ALIGN_MIN else ALIGN_MIN)
            raf.write(payload)
            cd.add(CdItem(entry, start, ByteArray(pad)))
        }

        /** 정렬 배치된 local header + name + zero-pad extra. returns entry-start. */
        private fun place(name: String, method: Int, flags: Int, crc: Long, compSize: Long,
                          uncompSize: Long, dosTime: Int, dosDate: Int, align: Long): Pair<Long, Int> {
            val nameB = name.toByteArray(Charsets.UTF_8)
            val start = raf.filePointer
            // data 위치 = start + 30 + nameLen + pad → align 승
            val pad = (align - (start + 30 + nameB.size) % align) % align
            raf.writeInt32LE(0x04034B50)
            raf.writeInt16LE(20)
            raf.writeInt16LE(flags)
            raf.writeInt16LE(method)
            raf.writeInt16LE(dosTime)
            raf.writeInt16LE(dosDate)
            raf.writeU32(crc)
            raf.writeU32(compSize)
            raf.writeU32(uncompSize)
            raf.writeInt16LE(nameB.size)
            raf.writeInt16LE(pad.toInt())
            raf.write(nameB)
            if (pad > 0) raf.write(ByteArray(pad.toInt()))
            return start to pad.toInt()
        }

        /** 압축엔트리는 header-end만, 비압축은 [align] 에 data 정렬. */
        private fun desiredAlign(entry: Entry): Long = when {
            entry.method != METHOD_STORE -> ALIGN_MIN
            entry.srcAlign == 0L -> PAGE // uncompressed at aligned start originally? treat as PAGE
            entry.srcAlign < ALIGN_MIN -> ALIGN_MIN
            PAGE % entry.srcAlign == 0L -> maxOf(entry.srcAlign, ALIGN_MIN)
            else -> ALIGN_MIN
        }

        fun finish() {
            val cdStart = raf.filePointer
            for (item in cd) {
                val e = item.entry
                val nameB = e.name.toByteArray(Charsets.UTF_8)
                raf.writeInt32LE(0x02014B50)
                raf.writeInt16LE(0x031E)
                raf.writeInt16LE(20)
                raf.writeInt16LE(e.flags)
                raf.writeInt16LE(e.method)
                raf.writeInt16LE(e.dosTime)
                raf.writeInt16LE(e.dosDate)
                raf.writeU32(e.crc)
                raf.writeU32(e.compSize)
                raf.writeU32(e.uncompSize)
                raf.writeInt16LE(nameB.size)
                raf.writeInt16LE(item.extra.size)
                raf.writeInt16LE(0)
                raf.writeInt16LE(0)
                raf.writeInt16LE(0)
                raf.writeInt32LE(0)
                raf.writeU32(item.localOffset)
                raf.write(nameB)
                raf.write(item.extra)   // CD extra = local extra 거울상 (apksig 오프셋 계산)
            }
            val cdSize = raf.filePointer - cdStart
            val total = cd.size
            if (total >= 0xFFFF || cdStart >= 0xFFFFFFFFL || cdSize >= 0xFFFFFFFFL) {
                writeZip64(cdStart, cdSize, total)
            } else {
                raf.writeInt32LE(0x06054B50)
                raf.writeInt16LE(0)   // this disk
                raf.writeInt16LE(0)   // CD start disk
                raf.writeInt16LE(total)
                raf.writeInt16LE(total)
                raf.writeU32(cdSize)
                raf.writeU32(cdStart)
                raf.writeInt16LE(0)   // comment len
            }
            raf.close()
        }

        private fun writeZip64(cdStart: Long, cdSize: Long, total: Int) {
            val recStart = raf.filePointer
            raf.writeInt32LE(0x06064B50)
            raf.writeU64(44)                      // size of zip64 EOCD - 12
            raf.writeInt16LE(45)
            raf.writeInt16LE(45)
            raf.writeInt16LE(0)
            raf.writeInt16LE(0)
            raf.writeU32(total.toLong())
            raf.writeU32(total.toLong())
            raf.writeU64(cdSize)
            raf.writeU64(cdStart)
            raf.writeInt32LE(0x07064B50)          // zip64 EOCD locator
            raf.writeInt32LE(0)
            raf.writeU64(recStart)
            raf.writeInt32LE(1)
            raf.writeInt32LE(0x06054B50)          // EOCD (zip64 markers)
            raf.writeInt16LE(0)
            raf.writeInt16LE(0xFFFF)
            raf.writeInt16LE(0xFFFF)
            raf.writeInt16LE(0xFFFF)
            raf.writeU32(0xFFFFFFFFL)
            raf.writeU32(0xFFFFFFFFL)
            raf.writeInt16LE(0)
            raf.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun findEocd(raf: RandomAccessFile): Long {
        val len = raf.length()
        val start = maxOf(0L, len - 66000)
        val buf = ByteArray((len - start).toInt())
        raf.seek(start); raf.readFully(buf)
        var i = buf.size - 22
        while (i >= 0) {
            if (intAt(buf, i) == 0x06054B50) return start + i
            i--
        }
        throw IllegalStateException(
            "EOCD not found in zip (len=$len start=$start tail=" +
                buf.takeLast(16).joinToString("") { "%02x".format(it) } + ")",
        )
    }

    private fun findZip64Eocd(raf: RandomAccessFile, eocd: Long): Long? {
        val pos = eocd - 20
        if (pos < 0) return null
        if (readU4(raf, pos) == 0x07064B50L) {
            val recPos = readU8(raf, pos + 8)
            if (readU4(raf, recPos) == 0x06064B50L) return recPos
        }
        return null
    }

    private fun parseZip64Extra(extra: ByteArray, comp: Long, uncomp: Long, off: Long): Triple<Long, Long, Long>? {
        var p = 0
        while (p + 4 <= extra.size) {
            val id = shortAt(extra, p)
            val size = shortAt(extra, p + 2)
            if (id == 0x0001) {
                var q = p + 4
                var c = comp; var u = uncomp; var o = off
                if (comp == 0xFFFFFFFFL && q + 8 <= extra.size) { c = read8(extra, q); q += 8 }
                if (uncomp == 0xFFFFFFFFL && q + 8 <= extra.size) { u = read8(extra, q); q += 8 }
                if (off == 0xFFFFFFFFL && q + 8 <= extra.size) { o = read8(extra, q); q += 8 }
                return Triple(c, u, o)
            }
            p += 4 + size
        }
        return null
    }

    private fun readU2(raf: RandomAccessFile, p: Long): Int {
        raf.seek(p)
        return raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    }

    private fun readU4(raf: RandomAccessFile, p: Long): Long {
        raf.seek(p)
        return raf.readUnsignedByte().toLong() or
            (raf.readUnsignedByte().toLong() shl 8) or
            (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 24)
    }

    private fun readU8(raf: RandomAccessFile, p: Long): Long {
        raf.seek(p)
        var v = 0L
        for (i in 0 until 8) v = v or (raf.readUnsignedByte().toLong() shl (8 * i))
        return v
    }

    private fun RandomAccessFile.writeInt32LE(v: Int) =
        write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))

    private fun RandomAccessFile.writeInt16LE(v: Int) =
        write(byteArrayOf(v.toByte(), (v shr 8).toByte()))

    private fun RandomAccessFile.writeU32(v: Long) =
        write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))

    private fun RandomAccessFile.writeU64(v: Long) =
        write(byteArrayOf(
            v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte(),
            (v shr 32).toByte(), (v shr 40).toByte(), (v shr 48).toByte(), (v shr 56).toByte(),
        ))

    private fun read8(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun nowDos(): Pair<Int, Int> {
        val c = java.util.Calendar.getInstance()
        val t = (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (c.get(java.util.Calendar.MINUTE) shl 5) or (c.get(java.util.Calendar.SECOND) / 2)
        val d = ((c.get(java.util.Calendar.YEAR) - 1980) shl 9) or
            ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or c.get(java.util.Calendar.DAY_OF_MONTH)
        return t to d
    }

    /** 파일 안의 single entry content (deflate 시 raw inflate). */
    fun readEntry(file: File, name: String): ByteArray? {
        val e = readEntries(file)[name] ?: return null
        return RandomAccessFile(file, "r").use { raf ->
            val comp = ByteArray(e.compSize.toInt())
            raf.seek(e.dataOffset); raf.readFully(comp)
            if (e.method == METHOD_STORE) comp else java.util.zip.InflaterInputStream(
                java.io.ByteArrayInputStream(comp), java.util.zip.Inflater(true)
            ).use { it.readBytes() }
        }
    }
}
