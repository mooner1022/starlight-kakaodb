package party.qwer.hayulgui.core

import java.io.File
import java.util.Properties

/** 산출물 저장소: filesDir/artifacts/<id>/ + meta.properties (spec §5.3/§6). */
object Artifacts {

    data class Meta(
        val id: String,
        val dir: File,
        val packageName: String,
        val sharedUserId: String,
        val versionCode: String,
        val createdAt: Long,
        val files: List<String>,
    )

    private fun dirId(ts: Long, pkg: String) =
        "${ts / 1000}-$pkg".replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun createRoots(filesDir: File): File = File(filesDir, "artifacts")

    /** 패치 산출물 디렉터리를 registre登记 (meta.properties 작성). */
    fun register(filesDir: File, pkg: String, sharedUserId: String, versionCode: String,
                 outDir: File): Meta {
        val id = dirId(System.currentTimeMillis(), pkg)
        val meta = Meta(id, outDir, pkg, sharedUserId, versionCode,
            System.currentTimeMillis(), outDir.listFiles()?.map { it.name } ?: emptyList())
        val p = Properties().apply {
            setProperty("id", id)
            setProperty("pkg", pkg)
            setProperty("sharedUserId", sharedUserId)
            setProperty("versionCode", versionCode)
            setProperty("createdAt", meta.createdAt.toString())
            setProperty("files", meta.files.joinToString(","))
        }
        File(outDir, "meta.properties").outputStream().use { p.store(it, "HayulGUI artifact") }
        // artifacts-root 아래로 이동(이름 통일) — 같은 디렉터리면 유지
        val target = File(createRoots(filesDir), id)
        if (!outDir.canonicalFile.equals(target.canonicalFile)) {
            createRoots(filesDir).mkdirs()
            outDir.copyRecursively(target, overwrite = true)
            // 임시 staging(예: _pending)은 정리하지 않으면 list 에 중복 항목으로 잡힌다
            outDir.deleteRecursively()
        }
        return meta.copy(id = id, dir = target)
    }

    fun list(filesDir: File): List<Meta> {
        val root = createRoots(filesDir)
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith("_") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            try {
                val p = Properties().apply { File(d, "meta.properties").inputStream().use { load(it) } }
                Meta(
                    id = p.getProperty("id", d.name),
                    dir = d,
                    packageName = p.getProperty("pkg", "?"),
                    sharedUserId = p.getProperty("sharedUserId", "?"),
                    versionCode = p.getProperty("versionCode", "?"),
                    createdAt = p.getProperty("createdAt", "0").toLong(),
                    files = p.getProperty("files", "").split(",").filter { it.isNotBlank() },
                )
            } catch (t: Throwable) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun delete(meta: Meta) {
        meta.dir.deleteRecursively()
    }
}
