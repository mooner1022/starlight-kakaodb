package party.qwer.hayulgui.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

/**
 * apksig 래퍼 (spec §5.4). raw PrivateKey + cert chain 사용.
 * v2/v3 상시, v1 은 타깃 minSdk < 24 일 때만.
 */
object ApkSigning {

    fun sign(input: File, output: File, key: SigningKey.KeySet, targetMinSdk: Int) {
        val signer = ApkSigner.Builder(
            listOf(SigningKey.signerConfigOf(key)),
        )
            .setInputApk(input)
            .setOutputApk(output)
            // v1(JAR) SHA-256 은 API<18 범위 검증 실패 -> 구버날 문서화. ours/타깃은 minSdk>=26 라 v2/v3 로 충분.
            // minSdk<24 원본이면 그레저리 디바이스 위해 v1 도 병기 (apksig 가 <18 용 SHA-1 도 자동).
            .setV1SigningEnabled(targetMinSdk in 1..23)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(maxOf(targetMinSdk, 1))
            .setOtherSignersSignaturesPreserved(false)
            .setCreatedBy("HayulGUI/1.0")
            .build()
        signer.sign()
    }

    /** 검증 실패 이슈 목록(비어있으면 통과) + 첫 서명자 인증서 DER. */
    class Check(val ok: Boolean, val issues: List<String>, val certDer: ByteArray?)

    fun verify(apk: File, targetMinSdk: Int = 0): Check {
        // floor=18: v2/v3 타깃에서 "v1 없음" 을 에러로 신고하는 레거시 검사대역 제외.
        // >=24: v2 로 충분하므로 "v1 없음" 레거시 요구를 검사하지 않는다.
        val floor = maxOf(24, targetMinSdk)
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(floor).build().verify()
        val issues = result.errors.map { "E:${it.issue}" } + result.warnings.map { "W:${it.issue}" }
        val cert = result.signerCertificates.firstOrNull()?.encoded
        return Check(result.isVerified && result.errors.isEmpty(), issues, cert)
    }

    /** apk 에 담긴 서명 인증서 DER (첫 signer). 파일 입력일 때 원본서명 확보 용도. */
    fun firstCert(apk: File): ByteArray? = verify(apk, 0).certDer
}
