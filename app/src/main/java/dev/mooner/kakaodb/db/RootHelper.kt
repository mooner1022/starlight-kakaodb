package dev.mooner.kakaodb.db

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import dev.mooner.kakaodb.rootd.Protocol
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Starts and reaches the root helper ([dev.mooner.kakaodb.rootd.RootDaemon]).
 *
 * The helper is the plugin asset [HelperApk] (:rootd's APK, see rootdApk in app/build.gradle), copied by su out of
 * StarLight's data and run under app_process. It listens on an abstract socket named after this app's uid, and trust has to go both
 * ways there:
 * - the helper only answers this uid holding [token]: every StarLight plugin runs as this uid;
 * - this side only talks to a socket owned by root: any app could bind the name first.
 *
 * A helper left by an earlier StarLight process (another token) or another plugin build fails the handshake and is
 * replaced. It exits by itself shortly after its last connection closes.
 */
internal object RootHelper {

    /** Bounds each read on a session; a query taking longer means the helper is stuck. */
    const val SESSION_TIMEOUT_MILLIS = 30_000

    private const val WATCH_RETRY_MILLIS = 5_000L

    private const val TRANSIENT_RETRY_MILLIS = 500L

    /** Neither "nothing listens" nor "that's not our helper": a connected helper that didn't answer. */
    private val IOException.isTransient: Boolean
        get() = this !is HelperUnavailable && this !is HelperMismatch && this !is HelperError

    private const val WORK_DIR = "/data/local/tmp/dev.mooner.kakaodb"
    private const val PROCESS_NAME = "kakaodb-rootd"
    private const val MAIN_CLASS = "dev.mooner.kakaodb.rootd.RootDaemon"

    /** How long the launch script waits for the helper to listen, in tenths of a second. */
    private const val START_WAIT_TENTHS = 150

    /** Per StarLight process: a helper started by an earlier one can't be talked to, and is replaced. */
    private val token: String by lazy {
        ByteArray(24).also(SecureRandom()::nextBytes).toHex()
    }

    private val socketName: String
        get() = Protocol.socketName(Process.myUid())

    private val launchLock = Any()

    /**
     * Connects to the helper, starting it first when none of this build answers. [timeoutMillis] bounds each read on
     * the channel (0: none). Blocks, su included: call off the main thread. Throws [RootException].
     */
    fun connect(apk: HelperApk, timeoutMillis: Int): RootChannel {
        var firstFailure = try {
            return open(apk, timeoutMillis)
        } catch (e: IOException) {
            e
        }
        // Connected to this build's helper, but it didn't answer in time or hung up. Replacing it would also cut this
        // process's other sessions off it, so a helper that is only busy gets a second chance first.
        if (firstFailure.isTransient) {
            try {
                Thread.sleep(TRANSIENT_RETRY_MILLIS)
            } catch (_: InterruptedException) {
            }
            firstFailure = try {
                return open(apk, timeoutMillis)
            } catch (e: IOException) {
                e
            }
        }
        synchronized(launchLock) {
            // Another thread may have started it while this one waited.
            try {
                return open(apk, timeoutMillis)
            } catch (_: IOException) {
            }
            launch(apk, firstFailure)
        }
        try {
            return open(apk, timeoutMillis)
        } catch (e: IOException) {
            val reason = e.message ?: e.toString()
            val hint = if ("denied" in reason.lowercase())
                tr(" SELinux may be blocking connections to it.", " SELinux가 도우미 연결을 막고 있을 수 있어요.")
            else ""
            throw RootException(
                tr("Started the root helper but couldn't connect to it: $reason.", "루트 도우미를 시작했지만 연결하지 못했어요: $reason.") + hint
            )
        }
    }

    /**
     * Signals whenever one of [names] in [dir] changes, as the helper's inotify sees it. Never starts the helper (the
     * sessions do, and su may show a toast each time): while it is down this stays silent and retries, and the
     * polling finds the changes meanwhile.
     */
    fun changes(apk: HelperApk, dir: File, names: Set<String>): Flow<Unit> = callbackFlow {
        val closed = AtomicBoolean(false)
        val current = AtomicReference<RootChannel?>()
        val worker = thread(isDaemon = true, name = "kakaodb-root-watch") {
            while (!closed.get()) {
                try {
                    open(apk, 0).use { channel ->
                        current.set(channel)
                        if (closed.get())
                            return@use
                        channel.send(Protocol.OP_WATCH) {
                            writeString(dir.path)
                            writeNames(names)
                        }
                        channel.expectOk()
                        while (true) {
                            if (channel.input.readUnsignedByte() == Protocol.EVENT_CHANGED)
                                trySend(Unit)
                        }
                    }
                } catch (_: Exception) {
                    // Not running, or it went away: try again later. (Nothing may escape: this is a raw thread.)
                } finally {
                    current.set(null)
                }
                if (!closed.get()) {
                    try {
                        Thread.sleep(WATCH_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        awaitClose {
            closed.set(true)
            // Unblocks the worker's read.
            current.get()?.close()
            worker.interrupt()
        }
    }

    private fun open(apk: HelperApk, timeoutMillis: Int): RootChannel =
        RootChannel.open(socketName, token, apk.build, timeoutMillis)

    /**
     * Replaces whatever helper this uid had with one of [apk]'s build, holding [token]. The token goes through su's
     * stdin, so it doesn't show in a process list. [cause] is why the running one (if any) didn't do.
     *
     * Each build gets its own directory in [WORK_DIR] (the helper extracts its native libraries next to its APK);
     * those of other builds are removed.
     */
    private fun launch(apk: HelperApk, cause: IOException) {
        val uid = Process.myUid()
        val build = apk.build
        val script = $$"""
            echo "UID $(id -u)"
            D=$${WORK_DIR}
            P="$D/rootd-$${uid}.pid"
            L="$D/rootd-$${uid}.log"
            B="$D/build/$${build}"
            A="$B/rootd.apk"

            # The helper's APK and native libraries run as root from here, so only root may own it. /data/local/tmp is
            # writable by the shell user: a directory (or link) someone else made there first is removed, not reused.
            if [ -L "$D" ] || { [ -e "$D" ] && [ "$(stat -c %u "$D")" != 0 ]; }; then rm -rf "$D"; fi
            mkdir -p "$D" && chmod 700 "$D" || { echo "FAIL mkdir $D"; exit 1; }

            # Stop this uid's previous helpers: the one in the pid file, and any whose pid file is gone (it would hold
            # the socket name, and the new one couldn't listen). Only processes of the helper started for this uid.
            for p in $(cat "$P" 2>/dev/null) $(pidof $${PROCESS_NAME} 2>/dev/null); do
              grep -q $${PROCESS_NAME} "/proc/$p/cmdline" 2>/dev/null || continue
              tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null | grep -qx "$${Protocol.ENV_CLIENT_UID}=$${uid}" || continue
              kill "$p"
              i=0
              while [ -d "/proc/$p" ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i+1)); done
            done

            # Other builds, and the jars helpers were before they were APKs.
            rm -f "$D"/rootd-*.jar
            for b in "$D"/build/*; do [ "$b" = "$B" ] || rm -rf "$b"; done
            # The build is the APK's hash: an APK there that doesn't match it is copied again.
            if [ -f "$A" ] && command -v sha256sum > /dev/null && [ "$(sha256sum "$A" | cut -c1-16)" != "$${build}" ]; then
              rm -f "$A"
            fi
            if [ ! -f "$A" ]; then
              mkdir -p "$B" && cp $${shellQuote(apk.file.path)} "$A.tmp" && mv -f "$A.tmp" "$A" && chmod 600 "$A" \
                || { echo "FAIL copy $${shellQuote(apk.file.path)}"; exit 1; }
            fi

            # sh -c records how the helper ended, so a crash shows at once instead of after the timeout. The pid
            # comes from the helper's READY line: $! may be setsid's, if it had to fork.
            : > "$L"
            $${Protocol.ENV_TOKEN}=$${token} $${Protocol.ENV_CLIENT_UID}=$${uid} $${Protocol.ENV_BUILD}=$${build} CLASSPATH="$A" \
              setsid sh -c 'app_process / --nice-name=$${PROCESS_NAME} $${MAIN_CLASS}; echo "ROOTD_EXIT $?"' < /dev/null >> "$L" 2>&1 &
            i=0
            while [ $i -lt $${START_WAIT_TENTHS} ]; do
              r=$(grep $${Protocol.READY} "$L")
              if [ -n "$r" ]; then
                echo "${r##* }" > "$P"
                echo STARTED
                exit 0
              fi
              grep -q ROOTD_EXIT "$L" && break
              sleep 0.1
              i=$((i+1))
            done
            echo FAILED
            grep -q ROOTD_EXIT "$L" || echo ROOTD_TIMEOUT
            tail -n 20 "$L"
        """.trimIndent()

        val output = Su.run(script)
        if (output.value("UID") != "0")
            throw RootException(tr("su access was denied.", "su 권한이 거부됐어요."))
        if ("STARTED" in output.lines)
            return
        output.value("FAIL")?.let {
            throw RootException(tr("Couldn't set up the root helper ($it).", "루트 도우미를 준비하지 못했어요 ($it)."))
        }
        val log = output.lines.dropWhile { it != "FAILED" }.drop(1).filter(String::isNotBlank)
        val timedOut = "ROOTD_TIMEOUT" in log
        val details = log.filter { it != "ROOTD_TIMEOUT" }
        // The status shows the first line: put the telling part of the log there.
        val key = details.lastOrNull { it.startsWith("rootd:") || "Exception" in it || "Error" in it } ?: details.lastOrNull()
        throw RootException(
            buildString {
                if (timedOut)
                    append(tr("The root helper didn't answer within ${START_WAIT_TENTHS / 10}s", "루트 도우미가 ${START_WAIT_TENTHS / 10}초 안에 응답하지 않았어요"))
                else
                    append(tr("The root helper didn't start", "루트 도우미가 시작되지 않았어요"))
                if (key != null)
                    append(": ").append(key)
                for (line in details)
                    append('\n').append(line)
                append('\n').append(tr("(before starting it: ${cause.message})", "(시작 전 연결 실패: ${cause.message})"))
            },
            // A helper that was only slow (a device still booting) may make it next time; one that ended would again.
            retryable = timedOut,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

/**
 * The helper's APK: the plugin asset [ASSET], which StarLight extracts into the plugin's data (StarlightPlugin.getAsset)
 * whenever assetRevision changes (app/build.gradle derives it from :rootd's sources).
 */
internal class HelperApk(val file: File) {

    /**
     * Identifies the APK (the start of its SHA-256): a running helper of another build is replaced. Throws
     * [RootException] if it's missing.
     */
    val build: String by lazy {
        if (!file.isFile)
            throw RootException(
                tr(
                    "The root helper isn't among the plugin's assets (${file.path}). Reinstall the plugin.",
                    "플러그인 asset에 루트 도우미가 없어요 (${file.path}). 플러그인을 다시 설치해 주세요.",
                )
            )
        // Every connection makes a new HelperApk: the APK (several MB) is hashed once per version of the file.
        builds.getOrPut("${file.path}:${file.length()}:${file.lastModified()}") { hash(file) }
    }

    companion object {
        /** :rootd's APK, copied by rootdApk in app/build.gradle. */
        const val ASSET = "rootd.apk"

        private val builds = ConcurrentHashMap<String, String>()

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0)
                        break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex().take(16)
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

/** One connection to the root helper: a database session, or a watch. Not thread-safe. */
internal class RootChannel private constructor(
    val input: DataInputStream,
    private val output: DataOutputStream,
    private val cleanup: () -> Unit,
) : Closeable {


    /** Sends [op] with what [args] writes. */
    fun send(op: Int, args: DataOutputStream.() -> Unit = {}) {
        output.writeByte(op)
        output.args()
        output.flush()
    }

    /** Reads a reply's status. The helper's error message becomes a [HelperError]. */
    fun expectOk() {
        when (val status = input.readUnsignedByte()) {
            Protocol.STATUS_OK    -> Unit
            Protocol.STATUS_ERROR -> throw HelperError(Protocol.readString(input))
            else                  -> throw IOException("Unexpected status from the root helper: $status")
        }
    }

    override fun close() {
        // Shutting down first unblocks a read in progress on another thread.
        runCatching { cleanup() }
    }

    companion object {

        fun fromStreams(input: java.io.InputStream, output: java.io.OutputStream, cleanup: () -> Unit): RootChannel =
            RootChannel(DataInputStream(BufferedInputStream(input)), DataOutputStream(BufferedOutputStream(output)), cleanup)

        private fun fromSocket(socket: LocalSocket): RootChannel = fromStreams(socket.inputStream, socket.outputStream) {
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            socket.close()
        }

        private const val HANDSHAKE_TIMEOUT_MILLIS = 5_000

        /**
         * Connects and shakes hands. Throws [HelperUnavailable] when nothing listens, [HelperMismatch] or [HelperError]
         * when that isn't this plugin's helper of [build], and other [IOException]s when it didn't answer.
         */
        fun open(name: String, token: String, build: String, timeoutMillis: Int): RootChannel {
            val socket = LocalSocket()
            try {
                try {
                    socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                } catch (e: IOException) {
                    throw HelperUnavailable("nothing listens on $name (${e.message})", e)
                }
                val owner = socket.peerCredentials.uid
                if (owner != 0)
                    throw HelperMismatch("$name is held by uid $owner, not by root")
                socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                val channel = fromSocket(socket)
                channel.send(Protocol.OP_HELLO) {
                    writeString(token)
                    writeInt(Protocol.VERSION)
                }
                channel.expectOk()
                val theirs = Protocol.readString(channel.input)
                if (theirs != build)
                    throw HelperMismatch("the running helper is build $theirs, not $build")
                socket.soTimeout = timeoutMillis
                return channel
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/** An error the helper reported (a failed query, a refused handshake...), as opposed to the connection failing. */
internal class HelperError(message: String) : IOException(message)

/** No helper listens on the socket name. */
internal class HelperUnavailable(message: String, cause: IOException) : IOException(message, cause)

/** Something answers on the socket name, but not this plugin's helper of this build (or not as root). */
internal class HelperMismatch(message: String) : IOException(message)

internal fun DataOutputStream.writeString(value: String) =
    Protocol.writeString(this, value)

internal fun DataOutputStream.writeNames(names: Collection<String>) {
    writeInt(names.size)
    for (name in names)
        writeString(name)
}
