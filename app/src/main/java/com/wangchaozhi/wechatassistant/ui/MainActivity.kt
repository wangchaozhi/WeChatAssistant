package com.wangchaozhi.wechatassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.service.CaptureForegroundService
import com.wangchaozhi.wechatassistant.service.OverlayService
import com.wangchaozhi.wechatassistant.ui.theme.WcaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(applicationContext as App)
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored */ }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result is read back from Settings.canDrawOverlays */ }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user toggles in system Settings */ }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureForegroundService.start(this, result.resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.screen.value != Screen.Home) viewModel.back() else finish()
        }
        setContent {
            WcaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val screen by viewModel.screen.collectAsState()
                    when (val s = screen) {
                        Screen.Home -> MainScreen(
                            viewModel = viewModel,
                            onOpenEditor = { id -> viewModel.navigate(Screen.Editor(id)) },
                            onOpenHistory = { viewModel.navigate(Screen.History) },
                            onRequestNotificationPermission = ::requestNotificationPermission,
                            onRequestOverlayPermission = ::requestOverlayPermission,
                            onRequestAccessibility = ::openAccessibilitySettings,
                            onStartCapture = ::requestMediaProjection,
                            onStopCapture = { CaptureForegroundService.stop(this) },
                            onStartOverlay = {
                                if (Settings.canDrawOverlays(this)) OverlayService.start(this)
                                else requestOverlayPermission()
                            },
                            onStopOverlay = { OverlayService.stop(this) },
                        )
                        is Screen.Editor -> ScriptEditorScreen(
                            scriptId = s.scriptId,
                            viewModel = viewModel,
                            onBack = viewModel::back,
                        )
                        Screen.History -> HistoryScreen(
                            viewModel = viewModel,
                            onBack = viewModel::back,
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
        }
    }

    private fun openAccessibilitySettings() {
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onOpenEditor: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    val ctx = LocalContext.current
    val scripts by viewModel.scripts.collectAsState()
    val accessibilityReady by viewModel.accessibilityReady.collectAsState()
    val captureReady by viewModel.captureReady.collectAsState()
    val overlayReady by viewModel.overlayReady.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val lastAnswer by viewModel.lastAiAnswer.collectAsState()

    val overlayGranted = remember(overlayReady) { Settings.canDrawOverlays(ctx) }
    val notifGranted = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    var apiKey by remember { mutableStateOf(viewModel.apiKey) }
    var prompt by remember { mutableStateOf(viewModel.defaultPrompt) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连点助手") },
                actions = {
                    TextButton(onClick = onOpenHistory) { Text("AI 历史") }
                },
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PermissionsCard(
                    notifGranted = notifGranted,
                    overlayGranted = overlayGranted,
                    accessibilityReady = accessibilityReady,
                    captureReady = captureReady,
                    overlayReady = overlayReady,
                    onRequestNotif = onRequestNotificationPermission,
                    onRequestOverlay = onRequestOverlayPermission,
                    onRequestAccessibility = onRequestAccessibility,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                    onStartOverlay = onStartOverlay,
                    onStopOverlay = onStopOverlay,
                )
            }
            item {
                SettingsCard(
                    apiKey = apiKey,
                    onApiKey = { apiKey = it; viewModel.apiKey = it },
                    prompt = prompt,
                    onPrompt = { prompt = it; viewModel.defaultPrompt = it },
                )
            }
            item {
                PlayerStatusCard(playerState, lastAnswer, onStop = viewModel::stop)
            }
            item {
                Text("脚本列表", style = MaterialTheme.typography.titleMedium)
            }
            items(scripts, key = { it.id }) { s ->
                ScriptItem(
                    script = s,
                    onPlay = { viewModel.play(s.id) },
                    onEdit = { onOpenEditor(s.id) },
                    onDelete = { viewModel.delete(s.id) },
                )
            }
            if (scripts.isEmpty()) {
                item {
                    Text(
                        "还没有脚本。请先在悬浮面板点击「录制」，在屏幕上点选要重复的位置，完成后会自动保存。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    notifGranted: Boolean,
    overlayGranted: Boolean,
    accessibilityReady: Boolean,
    captureReady: Boolean,
    overlayReady: Boolean,
    onRequestNotif: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("权限 & 服务", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            PermRow(
                title = "通知权限",
                subtitle = if (notifGranted) "已授予" else "用于前台服务的常驻通知",
                action = if (notifGranted) null else "去授权" to onRequestNotif,
            )
            PermRow(
                title = "悬浮窗",
                subtitle = if (overlayGranted) "已授予" else "用于显示控制面板与录制覆盖层",
                action = if (overlayGranted) null else "去授权" to onRequestOverlay,
            )
            PermRow(
                title = "无障碍（手势执行）",
                subtitle = if (accessibilityReady) "已开启" else "用于回放点击/滑动手势",
                action = if (accessibilityReady) null else "去开启" to onRequestAccessibility,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PermRow(
                title = "屏幕共享（截图）",
                subtitle = if (captureReady) "运行中" else "用于截屏并发送给千问",
                action = (if (captureReady) "停止" else "启动") to
                    (if (captureReady) onStopCapture else onStartCapture),
            )
            PermRow(
                title = "悬浮控制面板",
                subtitle = if (overlayReady) "运行中" else "提供「录制 / AI / 停止」快捷按钮",
                action = (if (overlayReady) "停止" else "启动") to
                    (if (overlayReady) onStopOverlay else onStartOverlay),
            )
        }
    }
}

@Composable
private fun PermRow(
    title: String,
    subtitle: String,
    action: Pair<String, () -> Unit>?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) {
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun SettingsCard(
    apiKey: String,
    onApiKey: (String) -> Unit,
    prompt: String,
    onPrompt: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("千问设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("DashScope API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = onPrompt,
                label = { Text("默认 Prompt") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlayerStatusCard(
    state: com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState,
    lastAnswer: String?,
    onStop: () -> Unit,
) {
    val playing = state is
        com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState.Playing
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("运行状态", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (playing) {
                val p = state as
                    com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState.Playing
                Text("正在播放：${p.script.name}  ${p.stepIndex + 1}/${p.totalSteps}")
                Spacer(Modifier.height(6.dp))
                Button(onClick = onStop) { Text("停止") }
            } else {
                Text("空闲")
            }
            if (!lastAnswer.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("最近 AI 回答（已写入粘贴板）", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.padding(top = 4.dp)) { Text(lastAnswer) }
            }
        }
    }
}

@Composable
private fun ScriptItem(
    script: Script,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "循环 ${script.loopCount} 次  速度 ${script.speed}x",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onPlay) { Text("播放") }
            Spacer(Modifier.height(0.dp))
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}
