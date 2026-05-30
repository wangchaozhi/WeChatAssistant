package com.wangchaozhi.wechatassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.service.CaptureForegroundService
import com.wangchaozhi.wechatassistant.service.OverlayService
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.ui.theme.WcaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        handleLaunchIntent(intent)
        setContent {
            WcaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val screen by viewModel.screen.collectAsState()
                    when (val s = screen) {
                        Screen.Home -> MainScreen(
                            viewModel = viewModel,
                            onOpenEditor = { id -> viewModel.navigate(Screen.Editor(id)) },
                            onOpenHistory = { viewModel.navigate(Screen.History) },
                            onOpenSettings = { viewModel.navigate(Screen.Settings) },
                            onCreateScript = {
                                viewModel.createEmptyScript { id ->
                                    viewModel.navigate(Screen.Editor(id))
                                }
                            },
                            onRequestNotificationPermission = ::requestNotificationPermission,
                            onRequestOverlayPermission = ::requestOverlayPermission,
                            onRequestAccessibility = ::openAccessibilitySettings,
                            onStartCapture = ::requestMediaProjection,
                            onStopCapture = { CaptureForegroundService.stop(this) },
                            onStartOverlay = {
                                when {
                                    !Settings.canDrawOverlays(this) -> requestOverlayPermission()
                                    !ServiceBus.accessibilityReady.value ->
                                        Toast.makeText(this, "请先开启「无障碍」服务再启动悬浮面板", Toast.LENGTH_LONG).show()
                                    !ServiceBus.captureReady.value ->
                                        Toast.makeText(this, "请先启动「截图服务」再启动悬浮面板", Toast.LENGTH_LONG).show()
                                    else -> OverlayService.start(this)
                                }
                            },
                            onStopOverlay = { OverlayService.stop(this) },
                        )
                        is Screen.Editor -> GraphEditorScreen(
                            scriptId = s.scriptId,
                            viewModel = viewModel,
                            onBack = viewModel::back,
                        )
                        Screen.History -> HistoryScreen(
                            viewModel = viewModel,
                            onBack = viewModel::back,
                        )
                        Screen.Settings -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = viewModel::back,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_EDIT_SCRIPT_ID, -1L) ?: -1L
        if (id > 0) viewModel.navigate(Screen.Editor(id))
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

    companion object {
        const val EXTRA_EDIT_SCRIPT_ID = "extra_edit_script_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onOpenEditor: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreateScript: () -> Unit,
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

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var notifGranted by remember { mutableStateOf(checkNotificationGranted(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(ctx)
                notifGranted = checkNotificationGranted(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Column {
                        Text("连点助手", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Shizuku 录制 · 无障碍回放",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "AI 历史")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
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
                HeroStatusCard(
                    scripts = scripts,
                    accessibilityReady = accessibilityReady,
                    overlayReady = overlayReady,
                    captureReady = captureReady,
                    playerState = playerState,
                    onStartOverlay = onStartOverlay,
                    onStopOverlay = onStopOverlay,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                )
            }
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
                PlayerStatusCard(playerState, lastAnswer, onStop = viewModel::stop)
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("脚本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (scripts.isEmpty()) "还没有脚本，点 + 新建或在悬浮窗录制"
                            else "${scripts.size} 个可用脚本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCreateScript) {
                        Icon(Icons.Filled.Add, contentDescription = "新建脚本")
                    }
                }
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
                    EmptyScriptsCard(onCreateScript = onCreateScript)
                }
            }
        }
    }
}

@Composable
private fun HeroStatusCard(
    scripts: List<Script>,
    accessibilityReady: Boolean,
    overlayReady: Boolean,
    captureReady: Boolean,
    playerState: com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val playing = playerState is com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState.Playing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (playing) "正在执行脚本" else "准备录制与回放",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${scripts.size} 个脚本 · ${readyCount(accessibilityReady, overlayReady, captureReady)}/3 项服务就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val overlayEnabled = overlayReady || (accessibilityReady && captureReady)
                Button(
                    onClick = if (overlayReady) onStopOverlay else onStartOverlay,
                    enabled = overlayEnabled,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (overlayReady) Icons.Filled.Stop else Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (overlayReady) "关闭面板" else "启动面板")
                }
                OutlinedButton(
                    onClick = if (captureReady) onStopCapture else onStartCapture,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (captureReady) "停止截图" else "截图服务")
                }
            }
        }
    }
}

private fun readyCount(vararg values: Boolean): Int = values.count { it }

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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(title = "权限与服务", subtitle = "录制、回放、AI 截图的运行条件")

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
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok = action == null || action.first == "停止")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            OutlinedButton(
                onClick = action.second,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.defaultMinSize(minWidth = 78.dp),
            ) { Text(action.first) }
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (playing) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(title = "运行状态", subtitle = if (playing) "脚本正在回放" else "当前空闲")
            if (playing) {
                val p = state as
                    com.wangchaozhi.wechatassistant.service.ServiceBus.PlayerState.Playing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.script.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第 ${p.stepIndex + 1} / ${p.totalSteps} 步",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onStop, shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                }
            } else {
                Text("悬浮面板启动后，可在任意应用中录制 Shizuku 手势并回放。")
            }
            if (!lastAnswer.isNullOrBlank()) {
                HorizontalDivider()
                Text("最近 AI 回答", style = MaterialTheme.typography.labelMedium)
                Text(
                    lastAnswer,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    script.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "循环 ${script.loopCount} 次 · 速度 ${script.speed}x · ${formatScriptDate(script.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyScriptsCard(onCreateScript: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "启动悬浮控制面板后，在屏幕上点击「录制」即可保存新的手势脚本；也可以直接新建一个空脚本手动编辑。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCreateScript, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建空脚本")
            }
        }
    }
}

private fun formatScriptDate(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun checkNotificationGranted(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
