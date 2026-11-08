package party.qwer.hayulgui.core

/**
 * Binary AndroidManifest (AXML) reader + surgical patcher.
 *
 * ## 포맷 (aapt2 산출물: flat chunk stream, re-flow 불필요)
 *   FILE header(16) → STRING_POOL → [RESOURCE_MAP] → node chunks
 *
 * START_ELEMENT chunk 는 attr 배열을 자기 내부에 포함하므로, attr 추가는
 * 해당 chunk 의 attrCount/size만 갱신하면 되고 부모 체인 업데이트가 없다.
 *
 * 패치 연산은 (1) pool 에 문자열 intern, (2) target START_ELEMENT chunk 의
 * attr 배열 추가/교체, (3) pack() 에서 pool 재인코딩 후 헤더 size 수정.
 */
class Axml private constructor(
    private val fileHeader: ByteArray,
    val pool: StringPool,
    val chunks: MutableList<Chunk>,
) {
    /** resmap 의 resource id 목록(test/diag 용). resmap chunk 없으면 null. */
    fun resourceMapIds(): IntArray? = resMapIds()

    companion object {
        const val ANDROID_URI = "http://schemas.android.com/apk/res/android"

        /**
         * android:* 속성 이름 -> (aapt 불변의) 자원 id. resmap 확장/해석에 쓴다.
         * 패치가 쓰는 sharedUserId/appComponentFactory 는 실파일 dump 로 확인한 값.
         */
        internal val ANDROID_ATTR_RES_IDS: Map<String, Int> = mapOf(
            "theme" to 0x01010000,
            "label" to 0x01010001,
            "icon" to 0x01010002,
            "name" to 0x01010003,
            "sharedUserId" to 0x0101000b,
            "enabled" to 0x0101000e,
            "debuggable" to 0x0101000f,
            "exported" to 0x01010010,
            "versionCode" to 0x0101021b,
            "versionName" to 0x0101021c,
            "minSdkVersion" to 0x0101020c,
            "targetSdkVersion" to 0x01010270,
            "compileSdkVersion" to 0x01010572,
            "compileSdkVersionCodename" to 0x01010573,
            "appComponentFactory" to 0x0101057a,
            "requiredSplitTypes" to 0x0101064e,
            "splitTypes" to 0x0101064f,
        )

        private val ID_NAMES: Map<Int, String> =
            ANDROID_ATTR_RES_IDS.entries.associate { (k, v) -> v to k }

        internal const val CHUNK_FILE = 0x0003
        internal const val CHUNK_POOL = 0x0001
        internal const val CHUNK_MAP = 0x0180
        internal const val CHUNK_START_ELEMENT = 0x0102

        private const val TYPE_STRING = 0x03

        fun parse(bytes: ByteArray): Axml {
            require(bytes.size >= 16) { "AXML too short" }
            val type = shortAt(bytes, 0)
            require(type == CHUNK_FILE) { "not AXML: 0x${type.toString(16)}" }
            val hdrSize = shortAt(bytes, 2)
            val fileHeader = bytes.copyOfRange(0, alignUp(hdrSize))

            var pos = alignUp(hdrSize)
            val pool = StringPool.parse(bytes, pos)
            pos += alignUp(pool.chunkSize)

            val chunks = ArrayList<Chunk>()
            while (pos + 8 <= bytes.size) {
                val cType = shortAt(bytes, pos)
                val cSize = intAt(bytes, pos + 4)
                if (cSize < 8 || pos + cSize > bytes.size) break
                chunks.add(Chunk(cType, bytes.copyOfRange(pos, pos + cSize)))
                pos += alignUp(cSize)
            }
            return Axml(fileHeader, pool, chunks)
        }

        internal fun alignUp(n: Int): Int = (n + 3) and 3.inv()
    }

    class Chunk(val type: Int, var data: ByteArray) {
        val size get() = intAt(data, 4)
    }

    // ------------------------------------------------------------------ pool

    class StringPool internal constructor(
        val chunkSize: Int,
        internal val flags: Int,
        internal val items: MutableList<String>,
        internal val utf8: Boolean,
    ) {
        companion object {
            private const val FLAG_UTF8 = 0x100
            private const val FLAG_SORTED = 0x10

            internal fun parse(bytes: ByteArray, off: Int): StringPool {
                val t = shortAt(bytes, off)
                require(t == CHUNK_POOL) { "string pool not found: 0x${t.toString(16)}" }
                val chunkSize = intAt(bytes, off + 4)
                val stringCount = intAt(bytes, off + 8)
                val styleCount = intAt(bytes, off + 12)
                val flags = intAt(bytes, off + 16)
                val stringsStart = intAt(bytes, off + 20)
                require(styleCount == 0) { "styled xml pool not supported" }
                val utf8 = flags and FLAG_UTF8 != 0
                val items = ArrayList<String>(stringCount)
                for (i in 0 until stringCount) {
                    items.add(decodeString(bytes, off + stringsStart + intAt(bytes, off + 28 + 4 * i), utf8))
                }
                return StringPool(chunkSize, flags, items, utf8)
            }

            /** pool chunk 을 재인코딩. SORTED flag 제거(순회참조를 안전하게). */
            internal fun encode(items: List<String>, utf8: Boolean): ByteArray {
                val enc = ArrayList<ByteArray>(items.size)
                for (s in items) enc.add(if (utf8) encodeUtf8(s) else encodeUtf16(s))
                val headerLen = 28
                val offsetsLen = items.size * 4
                val pad = (-headerLen - offsetsLen) and 3
                var dataSize = 0
                for (e in enc) dataSize += e.size + (-e.size and 3)
                val total = alignUp(headerLen + offsetsLen + pad + dataSize)

                val out = ByteArray(total)
                putShort(out, 0, CHUNK_POOL)
                putShort(out, 2, headerLen)
                putInt(out, 4, total)
                putInt(out, 8, items.size)
                putInt(out, 12, 0)
                putInt(out, 16, if (utf8) FLAG_UTF8 else 0)
                putInt(out, 20, headerLen + offsetsLen + pad)
                putInt(out, 24, 0)
                val dataStart = headerLen + offsetsLen + pad
                var dataOff = dataStart
                for (i in items.indices) {
                    putInt(out, headerLen + 4 * i, dataOff - dataStart)
                    enc[i].copyInto(out, dataOff)
                    dataOff += enc[i].size + (-enc[i].size and 3)
                }
                return out
            }

            private fun decodeString(b: ByteArray, o: Int, utf8: Boolean): String {
                var p = o
                if (utf8) {
                    var ch = b[p].toInt() and 0xFF
                    p++
                    if (ch and 0x80 != 0) {
                        ch = ((ch and 0x7F) shl 8) or (b[p].toInt() and 0xFF)
                        p++
                    }
                    var bl = b[p].toInt() and 0xFF
                    p++
                    if (bl and 0x80 != 0) {
                        bl = ((bl and 0x7F) shl 8) or (b[p].toInt() and 0xFF)
                        p++
                    }
                    return String(b, p, bl, Charsets.UTF_8)
                }
                var n = shortAt(b, p)
                p += 2
                if (n and 0x8000 != 0) {
                    n = ((n and 0x7FFF) shl 16) or (shortAt(b, p) and 0xFFFF)
                    p += 2
                }
                val sb = StringBuilder(n)
                for (i in 0 until n) {
                    sb.append(((b[p + 2 * i].toInt() and 0xFF) or ((b[p + 2 * i + 1].toInt() and 0xFF) shl 8)).toChar())
                }
                return sb.toString()
            }

            private fun encodeUtf8(s: String): ByteArray {
                val data = s.toByteArray(Charsets.UTF_8)
                val charLen = s.length
                val head = ArrayList<Byte>(4)
                if (charLen >= 0x80) {
                    head.add((0x80 or (charLen shr 8)).toByte())
                    head.add((charLen and 0xFF).toByte())
                } else head.add(charLen.toByte())
                if (data.size >= 0x80) {
                    head.add((0x80 or (data.size shr 8)).toByte())
                    head.add((data.size and 0xFF).toByte())
                } else head.add(data.size.toByte())
                val out = ByteArray(head.size + data.size + 1)
                head.forEachIndexed { i, b -> out[i] = b }
                data.copyInto(out, head.size)
                return out
            }

            private fun encodeUtf16(s: String): ByteArray {
                val n = s.length
                val headSize = if (n >= 0x8000) 4 else 2
                val out = ByteArray(headSize + 2 * n + 2)
                if (n >= 0x8000) {
                    out[0] = (n shr 16).toByte()
                    out[1] = (n shr 24 or 0x80).toByte()
                    out[2] = n.toByte()
                    out[3] = (n shr 8).toByte()
                } else {
                    putShort(out, 0, n)
                }
                for (i in s.indices) putShort(out, headSize + 2 * i, s[i].code)
                putShort(out, headSize + 2 * n, 0)
                return out
            }
        }

        val size get() = items.size

        /** 없으면 추가. 항상 유효한 인덱스 반환. */
        fun intern(s: String): Int {
            val i = items.indexOf(s)
            if (i >= 0) return i
            items.add(s)
            return items.size - 1
        }

        fun at(i: Int): String? = if (i in items.indices) items[i] else null

        fun encode(): ByteArray = encode(items, utf8)
    }

    // ----------------------------------------------------------- element ops

    /** resmap(RES_XML_RESOURCE_MAP) chunk 의 id 배열. 원본에 없으면 null. */
    private fun resMapIds(): IntArray? {        val c = chunks.firstOrNull { it.type == CHUNK_MAP } ?: return null
        val n = (intAt(c.data, 4) - 8) / 4
        if (n <= 0) return IntArray(0)
        return IntArray(n) { intAt(c.data, 8 + 4 * it) }
    }

    /**
     * 추가하는 android:* 속성의 `name` 필드 값 = attr 이름 문자열의 POOL 인덱스.
     *
     * 실측(Android 12):
     *  - Java XmlBlock 패키지파서(PackageParser/SharedUser 조인경로)는
     *    indexOfAttribute 에서 속성 name 필드를 POOL 인덱스로 보고
     *    문자열로 속성을 찾는다. 즉 name 은 반드시 그 문자열의 pool ref.
     *  - libandroidfw 는 id 를 map[name] 으로 조달하므로, 포지셔널
     *    불변식 map[k] == id(pool[k]) 가 성립해야 한다.
     *  두 규칙을 동시에 만족시키는게 aapt1 산출물 레이아웃: 이름 문자열을
     *    pool 에 intern 하고, resmap 의 그 슬롯에 attr id 를 둔다(gap 은 0).
     *
     * 실패사례 복기: raw=-1(값 못읽음) > name=map-index(풀 문자열 일치 실패)
     * > id 재사용만(슬롯 불일치) 모두 조용히 무시됐다. 세 조건 다 만족해야
     * sharedUserId 가 실제로 적용된다.
     */
    private fun nameRefFor(attr: String, poolNameRef: Int): Int {
        val id = ANDROID_ATTR_RES_IDS[attr] ?: return poolNameRef
        val nameRef = pool.intern(attr) // attr 이름 문자열 = POOL 인덱스 (이게 name 필드)
        val c = chunks.firstOrNull { it.type == CHUNK_MAP } ?: return nameRef
        val ids = resMapIds() ?: return nameRef
        if (nameRef < ids.size) {
            if (ids[nameRef] != id) putInt(c.data, 8 + 4 * nameRef, id)
            return nameRef
        }
        // map 을 nameRef 를 덮도록 확장. 중간 gap 은 0(=그 위치가 attr 이름 아님).
        val ext = ByteArray(8 + (nameRef + 1) * 4)
        putShort(ext, 0, CHUNK_MAP)
        putShort(ext, 2, 8)
        putInt(ext, 4, ext.size)
        for (i in ids.indices) putInt(ext, 8 + 4 * i, ids[i])
        putInt(ext, 8 + 4 * nameRef, id)
        c.data = ext
        return nameRef
    }

    /** attr slot 이름 해석: name 은 pool ref. 범위 밖이면 resmap id->이름 폴백(과거 생성물/관측용). */
    internal fun attrNameAt(e: ByteArray, o: Int): String? {
        val name = intAt(e, o + 4)
        pool.at(name)?.let { return it }
        val ns = intAt(e, o)
        if (ns >= 0 && pool.at(ns) == ANDROID_URI) {
            val ids = resMapIds()
            if (ids != null && name in ids.indices) return ID_NAMES[ids[name]]
        }
        return null
    }

    private fun indexOfElement(name: String): Int {
        for (i in chunks.indices) {
            val c = chunks[i]
            if (c.type != CHUNK_START_ELEMENT) continue
            if (c.data.size >= 24 && pool.at(intAt(c.data, 20)) == name) return i
        }
        return -1
    }

    /** attr 의 값 문자열. (원시타입은 render, 없음= null) */
    fun readAttribute(element: String, attr: String): String? {
        val ei = indexOfElement(element)
        if (ei < 0) return null
        val e = chunks[ei].data
        val attrCount = shortAt(e, 28)
        val attrStart = shortAt(e, 24)
        val attrSize = shortAt(e, 26)
        repeat(attrCount) { i ->
            val o = 16 + attrStart + attrSize * i
            if (attrNameAt(e, o) == attr) {
                val raw = intAt(e, o + 8)
                if (raw != -1) pool.at(raw)?.let { return it }
                val type = e[o + 15].toInt() and 0xFF
                val data = intAt(e, o + 16)
                return when (type) {
                    0x00 -> null
                    TYPE_STRING -> pool.at(data)
                    0x10, 0x11 -> Integer.toString(data)
                    0x12 -> if (data != 0) "true" else "false"
                    0x02 -> java.lang.Float.intBitsToFloat(data).toString()
                    else -> "0x" + Integer.toHexString(data)
                }
            }
        }
        return null
    }

    /** attr 값과 ns reference 를 (값문자열, nsIndex, 타입, data, raw) 으로 상세 조회. */
    data class AttrView(val value: String?, val nsRef: Int, val dataType: Int, val data: Int, val rawRef: Int)

    fun attributeView(element: String, attr: String): AttrView? {
        val ei = indexOfElement(element)
        if (ei < 0) return null
        val e = chunks[ei].data
        val attrCount = shortAt(e, 28)
        val attrStart = shortAt(e, 24)
        val attrSize = shortAt(e, 26)
        repeat(attrCount) { i ->
            val o = 16 + attrStart + attrSize * i
            if (attrNameAt(e, o) == attr) {
                val raw = intAt(e, o + 8)
                val rawS = if (raw != -1) pool.at(raw) else null
                val type = e[o + 15].toInt() and 0xFF
                val data = intAt(e, o + 16)
                val shown = rawS ?: when (type) {
                    TYPE_STRING -> pool.at(data)
                    0x10, 0x11 -> Integer.toString(data)
                    0x12 -> if (data != 0) "true" else "false"
                    else -> "0x" + Integer.toHexString(data)
                }
                return AttrView(shown, intAt(e, o), type, data, raw)
            }
        }
        return null
    }

    /** element 에 android:* 속성(TypeString)을 추가/교체. */
    fun setStringAttribute(element: String, attr: String, value: String) {
        val ei = indexOfElement(element)
        if (ei < 0) throw IllegalArgumentException("element not found: $element")
        val nameRef = nameRefFor(attr, pool.intern(attr))
        val valueRef = pool.intern(value)

        val e = chunks[ei].data
        val attrCount = shortAt(e, 28)
        val attrStart = shortAt(e, 24)
        val attrSize = shortAt(e, 26)

        repeat(attrCount) { i ->
            val o = 16 + attrStart + attrSize * i
            if (attrNameAt(e, o) == attr) {
                // rawValue must be the value's string-pool reference: platform
                // XmlBlock resolves *string* attribute values via rawValue
                // (nativeGetAttributeStringValue), NOT via typedValue.data.
                // Writing -1 makes getAttributeValue() return null (the
                // typedValue fallback coerceToString(TYPE_STRING, ref) is null),
                // so PMS silently ignores e.g. android:sharedUserId.
                putInt(e, o + 8, valueRef)
                putShort(e, o + 12, 8)
                e[o + 14] = 0
                e[o + 15] = TYPE_STRING.toByte()
                putInt(e, o + 16, valueRef)
            }
        }

        // 교체 대상이 없으면 append
        val already = (0 until attrCount).any { i ->
            attrNameAt(e, 16 + attrStart + attrSize * i) == attr
        }
        if (!already) {
            val ns = androidNsRef()
            val entry = ByteArray(attrSize)
            putInt(entry, 0, ns)
            putInt(entry, 4, nameRef)
            putInt(entry, 8, valueRef) // see note above: rawValue = pool ref, not -1
            putShort(entry, 12, 8)
            entry[14] = 0
            entry[15] = TYPE_STRING.toByte()
            putInt(entry, 16, valueRef)

            // [kakaodb] Inserted where its resource id sorts, not at the front. The framework reads an element's
            // attributes in one pass that assumes ascending resource ids (libandroidfw RetrieveAttributes, behind
            // obtainAttributes), so an attribute out of order hides every attribute after it with a smaller id.
            // Front-inserted, appComponentFactory (0x0101057a) hid <application>'s theme, label, icon and
            // allowBackup in APKs that don't declare one (KakaoTalk does, through AndroidX, and gets it replaced
            // in place). The front happened to be right for sharedUserId (0x0101000b), the smallest id on
            // <manifest>: what upstream observed when appending it failed. Attributes without an id
            // (package...) are skipped; aapt2 puts them last.
            val slot = insertionSlot(e, attrStart, attrSize, attrCount, ANDROID_ATTR_RES_IDS[attr])
            val insertAt = 16 + attrStart + attrSize * slot
            val nd = ByteArray(e.size + attrSize)
            e.copyInto(nd, 0, 0, insertAt) // 노드 헤더 + attr장 + 앞쪽 속성 유지
            entry.copyInto(nd, insertAt)
            e.copyInto(nd, insertAt + attrSize, insertAt, e.size)
            putShort(nd, 28, attrCount + 1)
            // idIndex/classIndex/styleIndex (1-based, 0 = none) of attributes after the slot move along.
            for (indexOffset in intArrayOf(30, 32, 34)) {
                val index = shortAt(nd, indexOffset)
                if (index > slot) putShort(nd, indexOffset, index + 1)
            }
            putInt(nd, 4, intAt(nd, 4) + attrSize)
            chunks[ei] = Chunk(chunks[ei].type, nd)
        }
    }

    /**
     * [kakaodb] Where an attribute of resource id [id] goes among an element's [count] attributes: before the first
     * with a larger id, else after the last that has an id. An attribute without a known id goes last.
     */
    private fun insertionSlot(e: ByteArray, attrStart: Int, attrSize: Int, count: Int, id: Int?): Int {
        if (id == null) return count
        val ids = resMapIds() ?: return 0
        var afterLastId = 0
        for (i in 0 until count) {
            val name = intAt(e, 16 + attrStart + attrSize * i + 4)
            val existing = if (name in ids.indices) ids[name] else 0
            if (existing == 0) continue
            if (Integer.compareUnsigned(existing, id) > 0) return i
            afterLastId = i + 1
        }
        return afterLastId
    }

    /** 문서 안의 android 속성들이 쓰는 ns pool index. 없으면 URI를 추가. */
    fun androidNsRef(): Int {
        for (c in chunks) {
            if (c.type != CHUNK_START_ELEMENT) continue
            val e = c.data
            if (e.size < 36) continue
            val attrCount = shortAt(e, 28)
            val attrStart = shortAt(e, 24)
            val attrSize = shortAt(e, 26)
            repeat(attrCount) { i ->
                if (pool.at(intAt(e, 16 + attrStart + attrSize * i)) == ANDROID_URI) {
                    return intAt(e, 16 + attrStart + attrSize * i)
                }
            }
        }
        return pool.intern(ANDROID_URI)
    }

    // ---------------------------------------------------------------- output

    /** pool 재인코딩 + 전체 재조합. file header size 를 total 로 보정. */
    fun pack(): ByteArray {
        val poolBytes = pool.encode()
        var total = fileHeader.size + poolBytes.size
        for (c in chunks) total += c.data.size
        val out = ByteArray(total)
        fileHeader.copyInto(out, 0)
        putInt(out, 4, total)
        var off = fileHeader.size
        poolBytes.copyInto(out, off)
        off += poolBytes.size
        for (c in chunks) {
            c.data.copyInto(out, off)
            off += c.data.size
        }
        return out
    }
}

// ---------------------------------------------------------------- byte utils

internal fun shortAt(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun intAt(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun putShort(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte()
    b[o + 1] = (v shr 8).toByte()
}

internal fun putInt(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte()
    b[o + 1] = (v shr 8).toByte()
    b[o + 2] = (v shr 16).toByte()
    b[o + 3] = (v shr 24).toByte()
}
