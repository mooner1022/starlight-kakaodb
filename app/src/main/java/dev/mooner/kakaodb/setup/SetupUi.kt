package dev.mooner.kakaodb.setup

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.mooner.kakaodb.utils.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/** Registers directly: the settings Activity is already started when a plugin button is pressed. */
internal suspend fun chooseDocument(context: Context, export: Boolean = false, key: Boolean = false): Uri? {
    var current = context
    while (current is ContextWrapper && current !is ComponentActivity) current = current.baseContext
    val activity = current as? ComponentActivity ?: error(tr("This screen can't choose files.", "파일 선택을 지원하지 않는 설정 화면입니다."))
    return suspendCancellableCoroutine { cont ->
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(
            "kakaodb-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher?.unregister()
            if (cont.isActive) cont.resume(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
        }
        cont.invokeOnCancellation { activity.runOnUiThread { launcher?.unregister() } }
        val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (export && key) "application/octet-stream" else "*/*"
            if (export) putExtra(Intent.EXTRA_TITLE, "kakaodb-signing-key.p12")
        }
        try { launcher.launch(intent) } catch (e: Exception) { launcher.unregister(); throw e }
    }
}

internal suspend fun askKeyPassword(context: Context, export: Boolean): CharArray? = suspendCancellableCoroutine { cont ->
    val input = EditText(context).apply {
        hint = tr("Backup password (8 or more characters)", "백업 비밀번호 (8자 이상)")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(if (export) tr("Back up signing key", "서명 키 백업") else tr("Restore signing key", "서명 키 복원"))
        .setView(input)
        .setNegativeButton(tr("Cancel", "취소")) { _, _ -> if (cont.isActive) cont.resume(null) }
        .setPositiveButton(tr("Continue", "계속")) { _, _ ->
            val password = input.text.toString().toCharArray()
            if (cont.isActive) cont.resume(password) else password.fill('\u0000')
        }
        .setOnCancelListener { if (cont.isActive) cont.resume(null) }
        .create()
    cont.invokeOnCancellation { dialog.dismiss() }
    dialog.show()
}
