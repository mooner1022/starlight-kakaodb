package party.qwer.hayulgui.core

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PatchEngine 경로 타기 전에 sign 단독 검사하는 디버그 테스트 (실 검증은 PatchEngineTest). */
class SignDebugTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test fun miniSign() {
        val key = SigningKey.loadOrCreate(tmp.newFolder("keys")) {}
        val apk = tmp.newFile("a.apk")
        Zip.Builder(apk).finishWith {
            it.putDeflated("AndroidManifest.xml", AxmlTestBuilder.miniManifest())
            it.putDeflated("classes.dex", ByteArray(1024))
            it.putDeflated("lib/x.so", ByteArray(65536), Zip.METHOD_STORE)
        }
        val out = tmp.newFile("b.apk")
        ApkSigning.sign(apk, out, key, 21)
        val chk = ApkSigning.verify(out)
        println("SIGN OK verified=${chk.ok} issues=${chk.issues} cert=${chk.certDer?.size}")
        check(chk.ok)
    }

    private inline fun Zip.Builder.finishWith(block: (Zip.Builder) -> Unit) {
        block(this)
        finish()
    }
}
