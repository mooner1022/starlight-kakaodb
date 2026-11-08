package dev.mooner.kakaodb.db

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.mooner.kakaodb.BuildConfig
import dev.mooner.kakaodb.core.DecoderOptions
import dev.mooner.kakaodb.rootd.Protocol
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared UID mode: a helper (:sharedd), installed patched into KakaoTalk's sharedUserId by the setup tool, runs as
 * KakaoTalk's UID and opens its databases where they are. The plugin binds its service, which hands out a socket pair
 * speaking the root helper's protocol (see [Protocol]); sessions and watches are each a binding and a socket.
 *
 * Unlike the root helper, which the plugin starts from its own asset, this one is installed by hand and can be left
 * behind by a plugin update: it reports its build, and [prepare] warns when it isn't [BuildConfig.SHARED_HELPER_BUILD].
 */
internal class SharedAccess : DatabaseAccess {

    override val mode = AccessMode.SHARED

    override val sessionFactory = RootSqliteSession.factory { connect(RootHelper.SESSION_TIMEOUT_MILLIS).channel }

    override fun prepare(dir: File): AccessResult {
        val warnings = ArrayList<String>()
        try {
            connect(RootHelper.SESSION_TIMEOUT_MILLIS).use { helper ->
                if (helper.build != BuildConfig.SHARED_HELPER_BUILD)
                    warnings += tr(
                        "The installed shared UID helper is build ${helper.build}, this plugin's is ${BuildConfig.SHARED_HELPER_BUILD}. Use \"Patch helper only\" in the plugin settings, then \"Install patched helper\".",
                        "설치된 공유 UID 도우미의 빌드(${helper.build})가 이 플러그인의 빌드(${BuildConfig.SHARED_HELPER_BUILD})와 달라요. 플러그인 설정에서 \"도우미만 다시 패치\"한 뒤 \"패치된 도우미 설치\"로 설치하세요.",
                    )
                val channel = helper.channel
                val names = listOf(DecoderOptions.MAIN_DB, DecoderOptions.SUB_DB)
                channel.send(Protocol.OP_CHECK) {
                    writeString(dir.path)
                    writeNames(names)
                }
                channel.expectOk()
                if (!channel.input.readBoolean())
                    return AccessResult.Failed(tr("${dir.path} doesn't exist", "${dir.path} 폴더가 없어요"))
                for (name in names) {
                    val mask = channel.input.readUnsignedByte()
                    if (mask and Protocol.FILE_EXISTS == 0)
                        return AccessResult.Failed(tr(
                            "${File(dir, name).path} doesn't exist. Open KakaoTalk and sign in, then connect again.",
                            "${File(dir, name).path} 파일이 없어요. 카카오톡을 실행해 로그인한 뒤 다시 연결하세요.",
                        ))
                    if (mask and Protocol.FILE_UNSAFE != 0) {
                        val missing = listOfNotNull(
                            "-wal".takeIf { mask and Protocol.FILE_WAL == 0 },
                            "-shm".takeIf { mask and Protocol.FILE_SHM == 0 },
                        ).joinToString(", ")
                        return AccessResult.Failed(walFilesMissing(name, missing))
                    }
                }
            }
        } catch (e: Exception) {
            return AccessResult.Failed(tr(
                "Couldn't use the shared UID helper: ${e.message ?: e}",
                "공유 UID 도우미를 사용할 수 없어요: ${e.message ?: e}",
            ))
        }
        return AccessResult.Ready(warnings)
    }

    override fun fileChanges(dir: File): Flow<Unit> = callbackFlow {
        val closed = AtomicBoolean(false)
        val current = AtomicReference<RootChannel?>()
        val worker = thread(isDaemon = true, name = "kakaodb-shared-watch") {
            while (!closed.get()) {
                try {
                    connect(0).channel.use { channel ->
                        current.set(channel)
                        if (closed.get())
                            return@use
                        channel.send(Protocol.OP_WATCH) {
                            writeString(dir.path)
                            writeNames(DatabaseWatcher.WATCHED_FILES)
                        }
                        channel.expectOk()
                        while (!closed.get())
                            if (channel.input.readUnsignedByte() == Protocol.EVENT_CHANGED)
                                trySend(Unit)
                    }
                } catch (_: Exception) {
                    // Not installed, or it went away: polling finds the changes meanwhile.
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

    /** A channel to the helper, and the build the helper reported. Closing it closes the channel. */
    private class Helper(val channel: RootChannel, val build: String) : AutoCloseable {
        override fun close() = channel.close()
    }

    /**
     * Binds the helper and gets a session socket from it. [timeoutMillis] bounds each read on the channel (0: none).
     * Blocks until the service connects, which Android reports on the main thread: never call this from there.
     */
    private fun connect(timeoutMillis: Int): Helper {
        val context = starLightContext()
        // Before binding: a service that isn't KakaoTalk's UID isn't talked to at all.
        val packages = context.packageManager
        val kakaoUid = uidOf(packages, Protocol.KAKAO_PACKAGE)
            ?: throw IOException(tr("KakaoTalk isn't installed", "카카오톡이 설치되어 있지 않아요"))
        val helperUid = uidOf(packages, Protocol.SHARED_PACKAGE)
            ?: throw IOException(helperMissing())
        if (helperUid != kakaoUid)
            throw SecurityException(tr(
                "the helper doesn't share KakaoTalk's UID. Install KakaoTalk and the helper patched with the same key, in the plugin's initial setup (Setup in the plugin list).",
                "도우미와 카카오톡의 UID가 달라요. 플러그인 목록의 '초기 설정'에서 같은 키로 패치한 카카오톡과 도우미를 설치하세요.",
            ))

        val latch = CountDownLatch(1)
        val remote = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = remote.set(null)
            override fun onBindingDied(name: ComponentName) = latch.countDown()
            override fun onNullBinding(name: ComponentName) = latch.countDown()
        }
        val unbound = AtomicBoolean(false)
        val unbind = {
            if (unbound.compareAndSet(false, true))
                runCatching { context.unbindService(connection) }
        }
        val intent = Intent().setClassName(Protocol.SHARED_PACKAGE, Protocol.SHARED_SERVICE)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            unbind()
            throw IOException(helperMissing())
        }

        var socket: ParcelFileDescriptor? = null
        try {
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw IOException(tr("the shared UID helper didn't start in time", "공유 UID 도우미가 제때 시작되지 않았어요"))
            val binder = remote.get()
                ?: throw IOException(tr("the shared UID helper refused the binding", "공유 UID 도우미가 연결을 거부했어요"))
            val build: String
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(Protocol.SHARED_DESCRIPTOR)
                data.writeInt(Protocol.VERSION)
                if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                    throw IOException(helperOutdated("no session transaction"))
                try {
                    reply.readException()
                } catch (e: IllegalArgumentException) {
                    if (Protocol.SHARED_MISMATCH in e.message.orEmpty())
                        throw IOException(helperOutdated(e.message.orEmpty()), e)
                    throw e
                }
                build = reply.readString().orEmpty()
                socket = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            } finally {
                data.recycle()
                reply.recycle()
            }

            val fd = socket!!
            val input = PollingInputStream(fd.fileDescriptor, timeoutMillis)
            val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))
            val channel = RootChannel.fromStreams(input, output) {
                // Shutting down first wakes a read blocked on another thread.
                runCatching { Os.shutdown(fd.fileDescriptor, OsConstants.SHUT_RDWR) }
                runCatching { output.close() }
                runCatching { fd.close() }
                unbind()
            }
            return Helper(channel, build)
        } catch (e: Exception) {
            runCatching { socket?.close() }
            unbind()
            throw e
        }
    }

    private fun helperMissing(): String =
        tr(
            "the shared UID helper isn't installed, or isn't patched into KakaoTalk's UID. Patch and install it in the plugin's initial setup (Setup in the plugin list).",
            "공유 UID 도우미가 설치되어 있지 않거나 카카오톡의 UID로 패치되지 않았어요. 플러그인 목록의 '초기 설정'에서 패치하고 설치하세요.",
        )

    private fun helperOutdated(reason: String): String =
        tr(
            "the installed helper doesn't match this plugin ($reason). Use \"Patch helper only\" in the plugin settings, then \"Install patched helper\".",
            "설치된 도우미가 이 플러그인과 맞지 않아요 ($reason). 플러그인 설정에서 \"도우미만 다시 패치\"한 뒤 \"패치된 도우미 설치\"로 설치하세요.",
        )

    private fun uidOf(packages: PackageManager, packageName: String): Int? =
        try {
            packages.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** PluginCore doesn't hand plugins a Context: StarLight's application publishes one. */
    private fun starLightContext(): Context {
        val app = Class.forName("dev.mooner.starlight.core.GlobalApplication")
        val companion = app.getField("Companion").get(null)
        return companion.javaClass.getMethod("requireContext").invoke(companion) as Context
    }

    private companion object {
        const val WATCH_RETRY_MILLIS = 5_000L
        const val BIND_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Reads [fd], a blocking socket, giving up with a [SocketTimeoutException] when nothing arrives for [timeoutMillis]
 * (0: waits as long as it takes).
 *
 * A FileInputStream with SO_RCVTIMEO can't do this: libcore turns the timeout (EAGAIN) into a read of 0 bytes, which
 * BufferedInputStream takes for the end of the stream on small reads, and DataInputStream.readFully retries forever
 * on large ones. Doesn't own [fd]: the channel's cleanup shuts it down (waking a blocked read) and closes it.
 */
internal class PollingInputStream(
    private val fd: FileDescriptor,
    private val timeoutMillis: Int,
) : InputStream() {

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0)
            return 0
        while (true) {
            if (timeoutMillis > 0 && !awaitReadable())
                throw SocketTimeoutException("no answer from the shared UID helper within ${timeoutMillis}ms")
            try {
                val read = Os.read(fd, b, off, len)
                return if (read == 0) -1 else read
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN)
                    continue
                throw IOException(e.message, e)
            }
        }
    }

    /** Whether [fd] has something to read (data, the end of the stream or an error) within the timeout. */
    private fun awaitReadable(): Boolean {
        val poll = StructPollfd().apply {
            fd = this@PollingInputStream.fd
            events = OsConstants.POLLIN.toShort()
        }
        while (true) {
            try {
                return Os.poll(arrayOf(poll), timeoutMillis) > 0
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR)
                    throw IOException(e.message, e)
            }
        }
    }
}
