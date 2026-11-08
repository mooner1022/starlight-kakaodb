package party.qwer.hayulgui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** adbPullCommand 는 호스트 OS 에 무관해야 한다 (zip/mkdir/tmp 금지, adb 만 사용). */
class PullCommandTest {

    private fun app(splits: List<String>) = ApkSources.InstalledApp(
        packageName = "com.kakao.talk",
        label = "KakaoTalk",
        versionCode = 1,
        base = "/data/app/~~h=/com.kakao.talk-x=/base.apk",
        splits = splits,
        systemApp = false,
        ownPackage = false,
        readable = false,
    )

    @Test fun singleApkPullsToDownloads() {
        val cmd = app(emptyList()).adbPullCommand()
        assertTrue(cmd, cmd.contains("/sdcard/Download/com.kakao.talk.apk"))
    }

    @Test fun splitSetCreatesDirAndPullsAll() {
        val cmd = app(listOf("/data/app/x/split_config.arm64_v8a.apk",
                             "/data/app/x/split_config.ko.apk")).adbPullCommand()
        assertTrue(cmd, cmd.startsWith("adb shell mkdir -p /sdcard/Download/com.kakao.talk"))
        assertTrue(cmd, cmd.contains("/sdcard/Download/com.kakao.talk/base.apk"))
        assertTrue(cmd, cmd.contains("/sdcard/Download/com.kakao.talk/split_config.arm64_v8a.apk"))
        assertTrue(cmd, cmd.contains("/sdcard/Download/com.kakao.talk/split_config.ko.apk"))
        // 호스트 OS 의존 요소 금지
        assertFalse(cmd, cmd.contains("zip"))
        assertFalse(cmd, cmd.contains("/tmp"))
                // 구분자는 줄바꿈 — cmd/PS/bash/zsh 어디에서도 그대로 동작
        assertTrue(cmd, cmd.contains("\nadb pull"))
    }
}
