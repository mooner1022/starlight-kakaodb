package dev.mooner.kakaodb.setup

import android.content.Context
import dev.mooner.configdsl.ConfigActionScope
import dev.mooner.configdsl.ConfigItemBuilder
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.options.button
import dev.mooner.configdsl.options.info
import dev.mooner.kakaodb.utils.tr
import java.io.File

/*
 * The shared UID mode's tools: KakaoTalk and the bundled helper are patched with one signing key and installed under
 * the same UID. The initial setup holds only what the first installation needs (sharedUidSetupSteps); updating the
 * helper and the key's backup and restore are in the plugin settings (sharedUidMaintenance).
 *
 * The setup's steps are shown only while the access mode item (modeId) is shared UID (modeKey), in the same category
 * as it, since a visibility rule can only look at items next to it. The settings' items are a category of their own
 * ("Shared UID helper"), always shown: ConfigDSL's categories don't nest, open no screen of their own, and can't be
 * hidden by an item of another category.
 *
 * Parameters of both:
 * - dataDir: where the plugin keeps the signing key and the patched APKs.
 * - asset: resolves the plugin assets (the helper APK and the stub).
 * - tint: the icon color; [dev.mooner.configdsl.ConfigBuilder.color] is out of reach of an extension function.
 */

/** The initial setup's part: patch KakaoTalk and the helper, then install both, in that order. */
internal fun ConfigItemBuilder.sharedUidSetupSteps(
    dataDir: File,
    asset: (String) -> File,
    modeId: String,
    modeKey: String,
    tint: Int,
) {
    fun embedded(context: Context) = EmbeddedSetup(context, dataDir, asset)

    info {
        id = "shared_steps"
        title = tr("Shared UID setup", "공유 UID 설정")
        description = tr(
            "KakaoTalk and the helper are patched with one key and installed under the same UID. Do the steps in order, then press Done. Back up KakaoTalk's data first: the patched KakaoTalk replaces the original.",
            "카카오톡과 도우미를 같은 키로 패치해 같은 UID로 설치해요. 아래 단계를 순서대로 진행한 뒤 완료를 누르세요. 패치한 카카오톡은 원래 앱을 대체하니 카카오톡 데이터를 먼저 백업하세요.",
        )
        icon = Icon.INFO
        iconTintColor = tint
        visibleWhen(modeId, modeKey)
    }
    button {
        id = "patch_shared_kakao"
        title = tr("1. Patch KakaoTalk and helper", "1. 카카오톡과 도우미 패치")
        description = tr("Choose a KakaoTalk APK set.", "카카오톡 APK 묶음을 선택하세요.")
        icon = Icon.DOWNLOAD
        iconTintColor = tint
        visibleWhen(modeId, modeKey)
        action {
            val setup = embedded(context)
            // Only someone moving from the standalone setup tool has a key to bring: the restore is in the settings.
            if (!setup.hasKey() && !confirm(
                    tr("Signing key", "서명 키"),
                    tr(
                        "A new signing key will be made. If you patched KakaoTalk with the previous setup app, cancel: back up its key there, restore it with \"Restore signing key\" in the plugin settings, then come back. Continue with a new key?",
                        "새 서명 키를 만들어요. 기존 설정 도구로 카카오톡을 패치한 적이 있다면 취소하고, 그 앱에서 키를 백업해 플러그인 설정의 \"서명 키 복원\"으로 가져온 뒤 다시 진행하세요. 새 키로 계속할까요?",
                    ),
                )
            ) return@action
            val uri = chooseDocument(context) ?: return@action
            setDescription(tr("Patching the APK. This can take a while.", "APK를 패치하는 중입니다. 시간이 걸릴 수 있습니다."))
            try {
                val fingerprint = setup.patch(uri)
                setDescription(tr(
                    "Patched. Install KakaoTalk and the helper with the next steps. Signing key: $fingerprint",
                    "패치 완료. 다음 단계에서 카카오톡과 도우미를 각각 설치하세요. 서명 지문: $fingerprint",
                ))
            } catch (e: Exception) {
                setDescription(tr("Patching failed: ${e.message ?: e}", "패치 실패: ${e.message ?: e}"))
                throw e
            }
        }
    }
    button {
        id = "install_patched_kakao"
        title = tr("2. Install patched KakaoTalk", "2. 패치된 카카오톡 설치")
        description = tr(
            "Opens Android's installer. A KakaoTalk signed by another key has to be removed first.",
            "안드로이드 설치 화면이 열려요. 다른 키로 서명된 카카오톡이 설치되어 있으면 먼저 제거하세요.",
        )
        icon = Icon.DOWNLOAD
        iconTintColor = tint
        visibleWhen(modeId, modeKey)
        action { install(embedded(context), EmbeddedSetup.KAKAO_DIR) }
    }
    button {
        id = "install_patched_helper"
        title = tr("3. Install patched helper", "3. 패치된 도우미 설치")
        description = tr("Opens Android's installer.", "안드로이드 설치 화면이 열려요.")
        icon = Icon.DOWNLOAD
        iconTintColor = tint
        visibleWhen(modeId, modeKey)
        action { install(embedded(context), EmbeddedSetup.HELPER_DIR) }
    }
}

/**
 * The plugin settings' part, for after the first installation: patch and install the helper again when a plugin
 * update changed it, and back up or restore the signing key. The items of a category of their own, whose title says
 * what they are about; each says in a line when it is needed.
 */
internal fun ConfigItemBuilder.sharedUidMaintenance(
    dataDir: File,
    asset: (String) -> File,
    tint: Int,
) {
    fun embedded(context: Context) = EmbeddedSetup(context, dataDir, asset)

    button {
        id = "patch_shared_helper"
        title = tr("Patch helper only", "도우미만 다시 패치")
        description = tr(
            "When the status says the installed helper doesn't match, after a plugin update.",
            "플러그인 업데이트 뒤 설치된 도우미가 맞지 않다고 나올 때 써요.",
        )
        icon = Icon.REFRESH
        iconTintColor = tint
        action {
            setDescription(tr("Patching the helper.", "도우미를 패치하는 중입니다."))
            try {
                val fingerprint = embedded(context).patchHelper()
                setDescription(tr(
                    "Patched. Install it with \"Install patched helper\". Signing key: $fingerprint",
                    "패치 완료. \"패치된 도우미 설치\"로 설치하세요. 서명 지문: $fingerprint",
                ))
            } catch (e: Exception) {
                setDescription(tr("Patching failed: ${e.message ?: e}", "패치 실패: ${e.message ?: e}"))
                throw e
            }
        }
    }
    button {
        id = "install_patched_helper"
        title = tr("Install patched helper", "패치된 도우미 설치")
        description = tr("Installs the helper patched above.", "위에서 다시 패치한 도우미를 설치해요.")
        icon = Icon.DOWNLOAD
        iconTintColor = tint
        action { install(embedded(context), EmbeddedSetup.HELPER_DIR) }
    }
    button {
        id = "export_shared_key"
        title = tr("Back up signing key", "서명 키 백업")
        description = tr("Back up before removing StarLight or the plugin.", "StarLight나 플러그인을 지우기 전에 백업하세요.")
        icon = Icon.KEY
        iconTintColor = tint
        action {
            val password = askKeyPassword(context, true) ?: return@action
            try {
                require(password.size >= MIN_PASSWORD) { passwordTooShort() }
                val uri = chooseDocument(context, export = true, key = true) ?: return@action
                embedded(context).exportKey(uri, password)
                setDescription(tr(
                    "Signing key backed up. Keep the file and its password somewhere safe.",
                    "서명 키를 백업했습니다. 파일과 비밀번호를 안전한 곳에 보관하세요.",
                ))
            } finally {
                password.fill('\u0000')
            }
        }
    }
    button {
        id = "import_shared_key"
        title = tr("Restore signing key", "서명 키 복원")
        description = tr(
            "Brings in a backed up key (PKCS#12), before the first patch.",
            "처음 패치하기 전에, 백업해 둔 키(PKCS#12)를 가져와요.",
        )
        icon = Icon.KEY
        iconTintColor = tint
        action {
            val password = askKeyPassword(context, false) ?: return@action
            try {
                require(password.size >= MIN_PASSWORD) { passwordTooShort() }
                val uri = chooseDocument(context, key = true) ?: return@action
                val fingerprint = embedded(context).importKey(uri, password)
                setDescription(tr("Signing key restored: $fingerprint", "서명 키를 복원했습니다: $fingerprint"))
            } finally {
                password.fill('\u0000')
            }
        }
    }
}

private const val MIN_PASSWORD = 8

private fun passwordTooShort(): String =
    tr("Enter a password of $MIN_PASSWORD or more characters.", "${MIN_PASSWORD}자 이상의 비밀번호를 입력하세요.")

private suspend fun ConfigActionScope.install(setup: EmbeddedSetup, target: String) {
    setDescription(tr("Preparing the installation files.", "설치 파일을 준비하는 중입니다."))
    try {
        setDescription(setup.install(target))
    } catch (e: Exception) {
        setDescription(tr("Installation failed: ${e.message ?: e}", "설치 실패: ${e.message ?: e}"))
        throw e
    }
}
