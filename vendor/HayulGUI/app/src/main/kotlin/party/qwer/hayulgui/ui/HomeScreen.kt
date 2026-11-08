package party.qwer.hayulgui.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import party.qwer.hayulgui.AppColors
import party.qwer.hayulgui.HayulState

/** Home — 키 상태/생성/백업, sharedUserId 설정, 사용법. */
@Composable
fun HomeScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyVersion by remember { mutableIntStateOf(0) }
    keyVersion.let { }
    var sharedId by remember { mutableStateOf(HayulState.sharedUserId) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 서명 키 ----
        SurfaceCard {
            SectionHeader(icon = Icons.Default.Key, title = "서명 키")
            Spacer(Modifier.height(10.dp))
            val hasKey = HayulState.loadKey(context) != null || HayulState.keyExists
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(ok = hasKey, label = if (hasKey) "생성됨" else "없음")
                Spacer(Modifier.width(10.dp))
                if (hasKey) {
                    Text(
                        HayulState.keyFingerprint(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = AppColors.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!hasKey) {
                Text(
                    "패치하기 전에 키를 만드세요. uninstall 시 키도 사라집니다.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { HayulState.ensureKey(context) }
                            keyVersion++
                        }
                    }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("키 생성") }
                    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) scope.launch {
                            runCatching {
                                val ks = withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)!!.use { HayulState.importKey(it.readBytes()) }
                                }
                                HayulState.putKey(context, ks)
                            }
                            keyVersion++
                        }
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("키 가져오기")
                    }
                }
            } else {
                Button(onClick = {
                    runCatching {
                        val ks = HayulState.keySet ?: return@runCatching
                        val bytes = HayulState.exportPkcs12(ks, "aaaaaa".toCharArray())
                        val f = java.io.File(context.cacheDir, "hayulgui-key.p12")
                        f.writeBytes(bytes)
                        val send = Intent(Intent.ACTION_SEND).setType("application/x-pkcs12")
                            .putExtra(Intent.EXTRA_STREAM,
                                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "서명 키 백업"))
                    }
                }) { Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("키 백업 (.p12)") }
            }
        }

        // ---- sharedUserId 설정 ----
        SurfaceCard {
            SectionHeader(icon = Icons.Default.GroupWork, title = "sharedUserId")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sharedId,
                onValueChange = { sharedId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("같은 값끼리 uid 를 공유합니다. 저장하면 모든 패치에 사용됩니다.") },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { HayulState.saveSharedId(context, sharedId.trim()) }) { Text("저장") }
                TextButton(onClick = { sharedId = HayulState.DEFAULT_SHARED_ID }) { Text("기본값") }
            }
        }

        // ---- How to ----
        SurfaceCard {
            SectionHeader(icon = Icons.Default.Info, title = "How to")
            Spacer(Modifier.height(8.dp))
            listOf(
                "1. 키 생성",
                "2. 앱목록 → 파일에 '패치' → 산출물 생성",
                "3. 설치 탭 → 산출물 설치 버튼 (설치 창 확인)",
                "4. 기존 앱과 서명이 다르면 먼저 uninstall",
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMain,
                    modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}
