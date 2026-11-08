package party.qwer.hayulgui.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * PackageInstaller session 기반 설치 실행 (spec §6 설치).
 * root/adb/DO 불요 — 시스템 설치 dialogs의 사용자 확인 한 번만 필요.
 * split 세트도 세션 하나에 전부를 쓰고 commit 한 번으로 설치된다.
 * 주의: SessionInfo/EXTRA_INSTALLED_PACKAGE_NAME 등은 framework @hide라서,
 * 브로드캐스트의 public extras(EXTRA_STATUS/EXTRA_STATUS_MESSAGE) 만으로 판정한다.
 */
object ApkInstaller {

    const val ACTION_INSTALL_RESULT = "party.qwer.hayulgui.action.INSTALL_RESULT"

    // PackageInstaller.STATUS_* (public API)
    private const val STATUS_SUCCESS = 0
    private const val STATUS_PENDING_USER_ACTION = -1

    /** 산출물 하나의 설치 가능 상태. */
    data class Plan(
        val pkg: String,
        val files: List<File>,
        val installed: Boolean,
        /** 설치된 서명 == 산출물 서명 */
        val sigMatch: Boolean,
        val label: String?,
    ) {
        /** 원본이 서명 불일치로 남아있어 설치가 막힌 상태 */
        val blocked: Boolean get() = installed && !sigMatch
        val installable: Boolean get() = !blocked
    }

    fun plan(context: Context, meta: Artifacts.Meta): Plan {
        val files = meta.files.filter { it.endsWith(".apk") }.map { File(meta.dir, it) }
        var installed = false
        var sigMatch = false
        var label: String? = null
        runCatching {
            val pm = context.packageManager
            val st = Status.inspect(context, meta.packageName, null)
            installed = st.installed
            label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(meta.packageName, 0)).toString()
            }.getOrNull()
            if (st.installed && files.isNotEmpty()) {
                val cert = ApkSigning.verify(files.first(), 0).certDer
                sigMatch = cert != null && st.certDer?.contentEquals(cert) == true
            }
        }
        return Plan(meta.packageName, files, installed, sigMatch, label)
    }

    /** 세션 커밋 → 시스템 설치 다이얼로그. onResult(성공, 에러문구) 는 메인 스레드. */
    fun install(context: Context, files: List<File>, onResult: (Boolean, String?) -> Unit) {
        val pm = context.packageManager
        val handler = Handler(Looper.getMainLooper())
        var done = false
        var rx: BroadcastReceiver? = null

        fun finish(ok: Boolean, msg: String?) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            rx?.let { runCatching { context.unregisterReceiver(it) } }
            handler.post { onResult(ok, msg) }
        }

        rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when {
                    status == STATUS_PENDING_USER_ACTION -> {
                        // 시스템이 확인 다이얼로그용 Intent 를 EXTRA_INTENT 에 담아 보낸다 —
                        // app 이 startActivity 로 띄워야 한다 (미호출 시 dialogs 없이 영원 대기).
                        val confirm = i.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { c.startActivity(confirm) }
                                .onFailure { finish(false, "설치 창 호출 실패: ${it.message}") }
                        }
                        // 확인 후 FINAL status 가 같은 rx 로 재브로드캐스트됨
                    }
                    status == STATUS_SUCCESS -> finish(true, null)
                    status != Int.MIN_VALUE -> finish(false, describe(status, msg))
                    else -> finish(false, "설치 응답을 해석할 수 없습니다")
                }
            }
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pm.packageInstaller.createSession(params)
            pm.packageInstaller.openSession(sessionId).use { session ->
                files.forEach { f ->
                    if (f.isFile) {
                        session.openWrite(f.name, 0, f.length()).use { out ->
                            f.inputStream().use { it.copyTo(out) }
                        }
                    }
                }
                val filter = IntentFilter(ACTION_INSTALL_RESULT)
                if (Build.VERSION.SDK_INT >= 33) {
                    // API33+: flags 는 마지막 인자 오버로드만 존재
                    context.registerReceiver(rx, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(rx, filter, null, handler)
                }
                handler.postDelayed(
                    { finish(false, "설치 응답 없음(10분) — 설치 창을 확인하세요") }, 600_000
                )
                val cb = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                session.commit(
                    PendingIntent.getBroadcast(context, sessionId, cb, flags).intentSender
                )
            }
        } catch (t: Throwable) {
            finish(false, "세션 실패: ${t.message}")
        }
    }

    private fun describe(status: Int, msg: String?): String {
        // PackageInstaller often puts the actual parser error after a colon. Keep the complete message so a
        // generic system "problem parsing the package" dialog can be diagnosed from the setup screen.
        val detail = msg?.trim().orEmpty()
        return when {
            detail.contains("UPDATE_INCOMPATIBLE") || detail.contains("signatures") ->
                "서명 불일치 — 대상을 먼저 uninstall 하세요"
            detail.contains("UID_CHANGED") -> "uid 가 다름 — 대상을 먼저 uninstall 하세요"
            detail.contains("VERSION_DOWNGRADE") -> "버전 강하는 불가"
            detail.contains("INSUFFICIENT_STORAGE") -> "저장공간 부족"
            detail.isNotBlank() -> "$detail (status=$status)"
            else -> when (status) {
                1 -> "설치 실패"
                2 -> "설치 차단됨 (권한/정책)"
                3 -> "설치 취소됨"
                4 -> "APK 형식 오류"
                5 -> "이미 설치된 세션과 충돌"
                6 -> "저장공간 부족"
                7 -> "호환 불가 (서명/uid 등)"
                8 -> "타임아웃"
                else -> "실패 (status=$status)"
            }
        }
    }
}
