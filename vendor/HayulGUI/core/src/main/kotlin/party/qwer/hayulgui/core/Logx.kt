package party.qwer.hayulgui.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque

/** in-memory ring + filesDir/logs/hayulgui-<yyyy-MM-dd>.log 파일 백업. */
object Logx {
    data class Line(val ts: Long, val level: String, val text: String)

    private const val MAX = 4000
    private val buffer = ArrayDeque<Line>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<Line>) -> Unit>()
    private var logDir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
    private val fmtFile = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)

    @Synchronized
    fun log(level: String, text: String) {
        buffer.addLast(Line(System.currentTimeMillis(), level, text))
        while (buffer.size > MAX) buffer.removeFirst()
        logDir?.let { dir ->
            try {
                File(dir, "hayulgui-${fmtFile.format(Date())}.log")
                    .appendText("${fmt.format(Date())} $level: $text\n")
            } catch (ignored: Exception) { }
        }
        listeners.forEach { runCatching { it(buffer.toList()) } }
    }

    fun d(text: String) = log("D", text)
    fun i(text: String) = log("I", text)
    fun w(text: String) = log("W", text)
    fun e(text: String) = log("E", text)

    fun snapshot(): List<Line> = buffer.toList()

    fun addListener(l: (List<Line>) -> Unit) {
        listeners.add(l)
        l(snapshot())
    }

    fun removeListener(l: (List<Line>) -> Unit) = listeners.remove(l)

    fun attach(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        // 30일 이전 삭제
        val cutoff = System.currentTimeMillis() - 30L * 86400_000
        dir.listFiles { f -> f.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun currentFile(context: Context): File =
        File(File(context.filesDir, "logs"), "hayulgui-${fmtFile.format(Date())}.log")
}
