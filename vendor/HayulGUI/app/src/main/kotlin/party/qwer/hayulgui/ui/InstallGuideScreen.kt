package party.qwer.hayulgui.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import party.qwer.hayulgui.AppColors
import party.qwer.hayulgui.HayulState
import party.qwer.hayulgui.core.ApkInstaller
import party.qwer.hayulgui.core.Artifacts

/** 설치 — 산출물 목록. 우측: 설치 버튼(+삭제). 서명불일치는 설치 비활성. */
@Composable
fun InstallGuideScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var metas by remember { mutableStateOf(HayulState.artifacts(context)) }
    var plans by remember { mutableStateOf<Map<String, ApkInstaller.Plan?>>(emptyMap()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Artifacts.Meta?>(null) }

    fun reload() {
        scope.launch {
            metas = withContext(Dispatchers.IO) { HayulState.artifacts(context) }
            plans = withContext(Dispatchers.IO) {
                metas.associate { m -> m.id to runCatching { ApkInstaller.plan(context, m) }.getOrNull() }
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (metas.isEmpty()) {
            SurfaceCard {
                Text("산출물이 없습니다. 앱목록 → Downloads 에서 APK 를 패치하세요.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            }
        }

        metas.forEach { m ->
            val plan = plans[m.id]
            val busy = busyId == m.id
            SurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.packageName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("v${m.versionCode} · ${fmtDate(m.createdAt)} · " +
                            (if (m.files.count { it.endsWith(".apk") } > 1)
                                "${m.files.count { it.endsWith(".apk") }} apk · " else "") +
                            "uid ${m.sharedUserId}",
                            style = MaterialTheme.typography.labelSmall, color = AppColors.TextSub,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (plan?.blocked == true) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text("서명불일치", maxLines = 1)
                        }
                    } else if (busy) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.PrimaryAccent)
                    } else {
                        Button(
                            enabled = plan != null,
                            onClick = {
                                val p = plan ?: return@Button
                                scope.launch {
                                    busyId = m.id
                                    withContext(Dispatchers.IO) {
                                        ApkInstaller.install(context, p.files) { ok, msg ->
                                            scope.launch {
                                                busyId = null
                                                Toast.makeText(context,
                                                    if (ok) "설치 확인됨" else (msg ?: "설치 실패"),
                                                    Toast.LENGTH_LONG).show()
                                                if (ok) reload()
                                            }
                                        }
                                    }
                                    // 커밋 반환 후 브로드캐스트 대기 — 타임아웃 안전장치
                                    if (busyId == m.id) {
                                        Toast.makeText(context, "설치 창 대기 중…", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Default.InstallMobile, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (plan?.installed == true) "재설치" else "설치")
                        }
                    }
                    IconButton(onClick = { deleteTarget = m }) {
                        Icon(Icons.Default.DeleteOutline, "삭제", tint = AppColors.TextSub)
                    }
                }
            }
        }
    }

    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("산출물 삭제") },
            text = { Text("${m.packageName} 의 산출물 폴더를 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    Artifacts.delete(m)
                    scope.launch { reload() }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}
