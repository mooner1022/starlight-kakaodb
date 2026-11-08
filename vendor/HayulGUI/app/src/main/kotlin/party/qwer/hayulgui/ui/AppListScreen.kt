package party.qwer.hayulgui.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
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
import party.qwer.hayulgui.HayulOps
import party.qwer.hayulgui.HayulState
import party.qwer.hayulgui.core.ApkSources
import party.qwer.hayulgui.core.PatchEngine

private fun Uri.nameFromUri(ctx: Context): String {
    var name: String? = lastPathSegment?.substringAfterLast('/')
    if (name == null || !name.contains('.')) runCatching {
        ctx.contentResolver.query(this, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
    }
    return name ?: "picked.apk"
}

/** 앱목록 — [앱 추출(ADB)] / [Downloads] 두 탭. */
@Composable
fun AppListScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabExtract by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<ApkSources.InstalledApp>>(emptyList()) }
    var files by remember { mutableStateOf<List<ApkSources.DownloadEntry>>(emptyList()) }
    var busyName by remember { mutableStateOf<String?>(null) }

    val staging = remember { java.io.File(context.filesDir, "input") }

    /** staged 파일 세트 패치 본체 (IO 컨텍스트에서 호출). */
    suspend fun patchStaged(staged: List<java.io.File>): PatchEngine.Result =
        withContext(Dispatchers.IO) {
            HayulState.inputFiles = staged
            HayulState.inputPackage = ApkSources.packageOf(staged.first()) ?: ""
            if (!HayulState.keyExists) HayulState.ensureKey(context)
            HayulOps.patchCurrentInput(context)
        }

    /** 라벨→busy 즉시 반영. 무거운 작업은 calling coroutine/withContext 안에서. */
    fun withBusy(label: String, block: suspend () -> Unit) {
        if (HayulState.busy) return
        busyName = label
        scope.launch {
            val outcome = runCatching { block() }
            busyName = null
            outcome.onSuccess {
                Toast.makeText(context, "패치 완료 — 설치 탭으로 이동", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "패치 실패: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val adder = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            busyName = "선택한 파일"   // picker 직후 staging 단계부터 spinner
            scope.launch {
                val outcome = runCatching {
                    val staged = withContext(Dispatchers.IO) {
                        val first = uris.first()
                        val name = first.nameFromUri(context)
                        var set = ApkSources.stageUri(context, first, name, staging)
                        if (uris.size > 1) {
                            uris.drop(1).forEachIndexed { idx, u ->
                                val f = java.io.File(staging, "${idx + 1}.apk")
                                context.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                                if (f.isFile && !set.contains(f)) set = set + f
                            }
                        }
                        set
                    }
                    patchStaged(staged)
                }
                busyName = null
                outcome.onSuccess {
                    Toast.makeText(context, "패치 완료 — 설치 탭으로 이동", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(context, "패치 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun reloadTab() {
        scope.launch {
            if (tabExtract) {
                apps = withContext(Dispatchers.IO) {
                    runCatching { ApkSources.installedApps(context) }.getOrDefault(emptyList())
                        .filter { !it.systemApp }   // OS/사전 설치 앱(ex com.google.*) 제외
                }
            } else {
                files = withContext(Dispatchers.IO) { runCatching { ApkSources.downloads(context) }.getOrDefault(emptyList()) }
            }
        }
    }
    LaunchedEffect(tabExtract) { reloadTab() }

    // settings(전체파일 접근)에서 돌아오면 즉시 갱신
    val le = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(le) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !tabExtract) reloadTab()
        }
        le.lifecycle.addObserver(obs)
        onDispose { le.lifecycle.removeObserver(obs) }
    }

    Column(modifier.fillMaxSize().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = tabExtract, onClick = { tabExtract = true },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("앱 추출 (ADB)") }
            SegmentedButton(selected = !tabExtract, onClick = { tabExtract = false },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Downloads") }
        }

        if (tabExtract) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps) { app ->
                    SurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                val cmd = app.adbPullCommand()
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("adb", cmd))
                                Toast.makeText(context, "adb pull 명령 복사됨", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("복사")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ApkSources.needsAllFiles(context)) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("파일 목록 보려면 ", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + context.packageName)))
                            }
                        }) { Text("전체파일 접근 허용") }
                    }
                }
                items(files) { f ->
                    SurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("%.1f MB · %s · %s".format(f.size / 1048576.0,
                                    if (f.kind == "set") "split set" else f.kind.uppercase(), fmtDate(f.mtime)),
                                    style = MaterialTheme.typography.labelSmall, color = AppColors.TextSub)
                            }
                            if (busyName == f.name) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = AppColors.PrimaryAccent)
                            } else {
                                Button(
                                    enabled = busyName == null,
                                    onClick = {
                                        withBusy(f.name) {
                                            val staged = withContext(Dispatchers.IO) {
                                                ApkSources.stageDownload(context, f, staging)
                                            }
                                            patchStaged(staged)
                                        }
                                    },
                                ) { Text("패치") }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (files.isEmpty()) "Downloads 비어있음 " else "목록 아래처럼 직접 선택 가능 ",
                            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                        TextButton(onClick = { adder.launch(arrayOf("application/vnd.android.package-archive", "application/apk",
                            "application/vnd.android.package-archive+xapk", "application/zip", "application/octet-stream", "*/*")) }) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("파일 선택")
                        }
                    }
                }
            }
        }
    }
}
