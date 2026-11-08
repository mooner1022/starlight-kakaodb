package dev.mooner.kakaodb.db

import dev.mooner.kakaodb.utils.tr
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs shell scripts as root through the root manager's su (Magisk, KernelSU...). Blocks: call off the main thread. */
internal object Su {

    private const val TIMEOUT_SECONDS = 30L

    /** What the script printed (stdout and stderr), line by line. */
    class Output(val exitCode: Int, val lines: List<String>) {

        /** The rest of the first line starting with "[key] ". */
        fun value(key: String): String? =
            lines.firstOrNull { it.startsWith("$key ") }?.substringAfter(' ')
    }

    /**
     * Runs [script] in a root shell, fed through stdin so nothing of it shows in a process list. Blocks until su exits,
     * which includes the root manager's prompt the first time. Throws [RootException] when su is missing or too slow.
     */
    fun run(script: String): Output {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (_: IOException) {
            throw RootException(tr("su wasn't found. Is the device rooted?", "su를 찾을 수 없어요. 루팅된 기기인지 확인해 주세요."))
        }

        var text = ""
        val reader = thread(isDaemon = true, name = "su-output") {
            text = process.inputStream.bufferedReader().readText()
        }
        try {
            process.outputStream.bufferedWriter().use {
                it.write(script)
                it.write("\nexit\n")
            }
        } catch (_: IOException) {
            // su quit before reading the script (e.g. denied); the output tells the rest.
        }

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            throw RootException(
                tr(
                    "su didn't respond within ${TIMEOUT_SECONDS}s. Check the root manager's prompt.",
                    "su가 ${TIMEOUT_SECONDS}초 안에 응답하지 않았어요. 루팅 관리 앱의 권한 요청을 확인해 주세요.",
                )
            )
        }
        reader.join()
        return Output(process.exitValue(), text.lines())
    }
}

/** Root access failed; the message is meant for the user. */
class RootException(message: String) : Exception(message)
