package party.qwer.hayulgui.core

/**
 * JVM test 용 minimal AXML 생성기 (aapt2 산출물 레이아웃 모사).
 *
 * 구조: FILE hdr → pool → [resmap] → START_NS(android) →
 *       <manifest> → <application> → (<uses-sdk> 자식 있으면) → END app → END manifest → END_NS.
 *
 * 각 START/END node 는 자기 size(=바이트 길이) 를 header 에 싣는 별개 chunk.
 * resmap 은 포지셔널: resmap[k] = id(pool[k]) (attr 이름인 슬롯만), 미사용은 0.
 */
object AxmlTestBuilder {

    sealed class TVal {
        class Str(val v: String) : TVal()
        class IntV(val v: Int) : TVal()
    }

    class TAttr(val name: String, val value: TVal, val ns: String = "android")

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10

    /** 테스트용 표준 mini manifest: package + versionCode, application 은 debuggable 정도. */
    fun miniManifest(
        packageName: String = "com.example.app",
        versionCode: Int = 1,
        extraManifestAttrs: List<TAttr> = emptyList(),
        appAttrs: List<TAttr> = emptyList(),
        utf8: Boolean = false,
        minSdkVersion: Int? = 26,
    ): ByteArray {
        val pool = mutableListOf(Axml.ANDROID_URI, "manifest", "application",
            "uses-sdk", "minSdkVersion", packageName)
        val mAttrs = ArrayList<ByteArray>()
        mAttrs.add(attr(pool, "package", TVal.Str(packageName), ns = "auto"))
        mAttrs.add(attr(pool, "versionCode", TVal.IntV(versionCode), ns = "android"))
        extraManifestAttrs.forEach { mAttrs.add(attr(pool, it.name, it.value, it.ns)) }
        val aAttrs = ArrayList<ByteArray>()
        appAttrs.forEach { aAttrs.add(attr(pool, it.name, it.value, it.ns)) }
        val childChunks: List<ByteArray>? = minSdkVersion?.let {
            val ab = attr(pool, "minSdkVersion", TVal.IntV(it), ns = "android")
            listOf(startChunk(pool.indexOf("uses-sdk"), listOf(ab)),
                   endChunk(pool.indexOf("uses-sdk")))
        }
        return assemble(pool, mAttrs, aAttrs, utf8, childChunks)
    }

    fun parse(bytes: ByteArray): Axml = Axml.parse(bytes)

    private fun attr(pool: MutableList<String>, name: String, value: TVal, ns: String): ByteArray {
        val b = ByteArray(20)
        putInt(b, 0, when (ns) {
            "android" -> internAdd(pool, Axml.ANDROID_URI)
            else -> -1
        })
        putInt(b, 4, internAdd(pool, name))
        putInt(b, 8, if (value is TVal.Str) internAdd(pool, value.v) else -1)
        putShort(b, 12, 8)
        when (value) {
            is TVal.Str -> {
                b[15] = TYPE_STRING.toByte()
                putInt(b, 16, internAdd(pool, value.v))
            }
            is TVal.IntV -> {
                b[15] = TYPE_INT_DEC.toByte()
                putInt(b, 16, value.v)
            }
        }
        return b
    }

    /** RES_XML_START_ELEMENT: header(16)+ns(4)+name(4)+attr장(12)+attrs */
    private fun startChunk(nameRef: Int, attrs: List<ByteArray>): ByteArray {
        val b = ByteArray(36 + attrs.size * 20)
        putShort(b, 0, 0x0102); putShort(b, 2, 16); putInt(b, 4, b.size)
        putInt(b, 8, 2); putInt(b, 12, -1)
        putInt(b, 16, -1); putInt(b, 20, nameRef)
        putShort(b, 24, 20); putShort(b, 26, 20); putShort(b, 28, attrs.size)
        var o = 36
        attrs.forEach { it.copyInto(b, o); o += 20 }
        return b
    }

    /** RES_XML_END_ELEMENT */
    private fun endChunk(nameRef: Int): ByteArray {
        val b = ByteArray(24)
        putShort(b, 0, 0x0103); putShort(b, 2, 16); putInt(b, 4, 24)
        putInt(b, 8, 3); putInt(b, 12, -1)
        putInt(b, 16, -1); putInt(b, 20, nameRef)
        return b
    }

    private fun nodeChunk(type: Int, a: Int, b2: Int): ByteArray {
        val b = ByteArray(24)
        putShort(b, 0, type); putShort(b, 2, 16); putInt(b, 4, 24)
        putInt(b, 8, 3); putInt(b, 12, -1)
        putInt(b, 16, a); putInt(b, 20, b2)
        return b
    }

    private fun assemble(poolStrings: List<String>, mAttrs: List<ByteArray>,
                         aAttrs: List<ByteArray>, utf8: Boolean,
                         childChunks: List<ByteArray>? = null): ByteArray {
        val manifestRef = poolStrings.indexOf("manifest")
        val appRef = poolStrings.indexOf("application")
        val uriRef = poolStrings.indexOf(Axml.ANDROID_URI)

        // resmap: 포지셔널 모델 (resmap[k] = id(pool[k]), attr 이름 슬롯만 채움)
        val slots = HashMap<Int, Int>()
        fun scanAttrs(list: List<ByteArray>) {
            list.forEach { ab ->
                val nsRef = intAt(ab, 0)
                if (nsRef >= 0 && nsRef < poolStrings.size &&
                    poolStrings[nsRef] == Axml.ANDROID_URI) {
                    val id = Axml.ANDROID_ATTR_RES_IDS[
                        poolStrings.getOrNull(intAt(ab, 4))]
                    if (id != null) slots[intAt(ab, 4)] = id
                }
            }
        }
        scanAttrs(mAttrs)
        scanAttrs(aAttrs)
        childChunks?.filter { intAt(it, 0) == 0x0102 }?.forEach { s ->
            val acnt = (s[28].toInt() and 0xFF) or ((s[29].toInt() and 0xFF) shl 8)
            for (i in 0 until acnt) scanAttrs(listOf(s.copyOfRange(36 + i * 20, 56 + i * 20)))
        }
        val resourceMap: ByteArray? = if (slots.isEmpty()) null else {
            val maxI = slots.keys.maxOrNull()!!
            ByteArray(8 + (maxI + 1) * 4).also { m ->
                putShort(m, 0, 0x0180); putShort(m, 2, 8); putInt(m, 4, m.size)
                slots.forEach { (k, v) -> putInt(m, 8 + 4 * k, v) }
            }
        }

        val header = ByteArray(16).also { putShort(it, 0, 3); putShort(it, 2, 16) }
        val pool = Axml.StringPool.encode(poolStrings, utf8)

        val parts = ArrayList<ByteArray>()
        parts.add(header)
        parts.add(pool)
        resourceMap?.let { parts.add(it) }
        parts.add(nodeChunk(0x0100, -1, uriRef))
        parts.add(startChunk(manifestRef, mAttrs))
        parts.add(startChunk(appRef, aAttrs))
        childChunks?.let { parts.addAll(it) }
        parts.add(endChunk(appRef))
        parts.add(endChunk(manifestRef))
        parts.add(nodeChunk(0x0101, -1, uriRef))

        val total = parts.sumOf { it.size }
        putInt(header, 4, total)
        return parts.fold(ByteArray(0)) { x, y -> x + y }
    }

    private fun internAdd(pool: MutableList<String>, s: String): Int {
        pool.indexOf(s).let { if (it >= 0) return it }
        pool.add(s)
        return pool.size - 1
    }

    private fun shortAt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun intAt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
}
