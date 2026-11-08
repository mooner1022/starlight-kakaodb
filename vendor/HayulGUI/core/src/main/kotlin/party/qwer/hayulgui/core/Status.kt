package party.qwer.hayulgui.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * 설치 상태 확인 (spec §5.5). HayulGUI 는 다른 앱의 uid 를 읽을 수 없으므로
 * "HayulGUI 키로 서명된 대상 앱 =HayulGUI 가 패치해 설치한 상태" 로 판단한다.
 * (서명 재위조/재설치 이력을 proof 함)
 */
object Status {

    data class TargetState(
        val packageName: String,
        val installed: Boolean,
        val versionCode: Long,
        /** 설치된 APK 서명 DER (첫 signer). 미설치/미확인 = null */
        val certDer: ByteArray?,
        /** ourCertDer 와 일치 → HayulGUI 키로 설치(패치)됨 */
        val signedWithOurKey: Boolean,
        val ownUid: Int,
    )

    @Suppress("DEPRECATION")
    fun inspect(context: Context, packageName: String, ourCertDer: ByteArray?): TargetState {
        var installed = false
        var versionCode = 0L
        var der: ByteArray? = null
        try {
            val pi = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            installed = true
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            der = pi.signatures?.firstOrNull()?.toByteArray()
        } catch (e: Exception) {
            installed = false
        }
        val same = der != null && ourCertDer != null && der.contentEquals(ourCertDer)
        return TargetState(packageName, installed, versionCode, der, same, Process.myUid())
    }

    /** 산출물 기준 stale: 원본 target 버전이 산출물의 versionCode 와 다른가? */
    fun versionStale(target: TargetState, artifactVersionCode: String?): Boolean =
        artifactVersionCode != null && target.installed && artifactVersionCode != target.versionCode.toString()
}
