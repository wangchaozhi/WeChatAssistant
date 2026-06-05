package com.wangchaozhi.wechatassistant.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.feature.ai.AiReasoningEffort
import com.wangchaozhi.wechatassistant.service.CaptureForegroundService
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.copyToClipboard
import com.wangchaozhi.wechatassistant.util.WifiAdbNotification
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf(viewModel.apiKey) }
    var prompt by remember { mutableStateOf(viewModel.defaultPrompt) }
    var model by remember { mutableStateOf(viewModel.qwenModel) }
    var thumbSide by remember { mutableStateOf(viewModel.thumbnailMaxSide) }
    var aiSide by remember { mutableStateOf(viewModel.aiImageMaxSide) }
    var msKey by remember { mutableStateOf(viewModel.modelScopeApiKey) }
    var msModel by remember { mutableStateOf(viewModel.modelScopeModel) }
    var defaultProvider by remember { mutableStateOf(viewModel.defaultAiProvider) }
    var reasoningEffort by remember { mutableStateOf(viewModel.aiReasoningEffort) }
    var qwenCachedModels by remember { mutableStateOf(viewModel.cachedModels(AiProvider.DASHSCOPE)) }
    var modelScopeCachedModels by remember { mutableStateOf(viewModel.cachedModels(AiProvider.MODELSCOPE)) }
    var recordEngine by remember { mutableStateOf(viewModel.recordEngine) }
    var showPlaybackMarker by remember { mutableStateOf(viewModel.showPlaybackMarker) }

    LaunchedEffect(Unit) {
        if (!viewModel.settingsModelsFetchedThisRun) {
            viewModel.fetchModels(AiProvider.DASHSCOPE).onSuccess { qwenCachedModels = it }
            viewModel.fetchModels(AiProvider.MODELSCOPE).onSuccess { modelScopeCachedModels = it }
            viewModel.settingsModelsFetchedThisRun = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DefaultProviderCard(
                    provider = defaultProvider,
                    onProvider = { defaultProvider = it; viewModel.defaultAiProvider = it },
                    reasoningEffort = reasoningEffort,
                    onReasoningEffort = { reasoningEffort = it; viewModel.aiReasoningEffort = it },
                    prompt = prompt,
                    onPrompt = { prompt = it; viewModel.defaultPrompt = it },
                )
            }
            item {
                QwenCard(
                    apiKey = apiKey,
                    onApiKey = { apiKey = it; viewModel.apiKey = it },
                    model = model,
                    onModel = { model = it; viewModel.qwenModel = it },
                    cachedModels = qwenCachedModels,
                    fetchModels = { viewModel.fetchModels(AiProvider.DASHSCOPE) },
                )
            }
            item {
                ModelScopeCard(
                    apiKey = msKey,
                    onApiKey = { msKey = it; viewModel.modelScopeApiKey = it },
                    model = msModel,
                    onModel = { msModel = it; viewModel.modelScopeModel = it },
                    cachedModels = modelScopeCachedModels,
                    fetchModels = { viewModel.fetchModels(AiProvider.MODELSCOPE) },
                )
            }
            item {
                AiImageCard(
                    side = aiSide,
                    onSide = { aiSide = it; viewModel.aiImageMaxSide = it },
                )
            }
            item {
                ThumbnailCard(
                    side = thumbSide,
                    onSide = { thumbSide = it; viewModel.thumbnailMaxSide = it },
                )
            }
            item {
                RecordEngineCard(
                    engine = recordEngine,
                    onEngine = { recordEngine = it; viewModel.recordEngine = it },
                )
            }
            item {
                PlaybackMarkerCard(
                    enabled = showPlaybackMarker,
                    onEnabled = { showPlaybackMarker = it; viewModel.showPlaybackMarker = it },
                )
            }
            // Wi-Fi ADB 配对卡片只在选了「Wi-Fi ADB 录制」时显示——悬浮层录制用不到它。
            if (recordEngine == SettingsRepository.RECORD_ENGINE_WIFI_ADB) {
                item { WifiAdbCard(viewModel) }
            }
            item { DebugLogCard() }
        }
    }
}

@Composable
private fun QwenCard(
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    cachedModels: List<String>,
    fetchModels: suspend () -> Result<List<String>>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Cloud)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("千问设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "用于截图识别与 AI 回答",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ApiKeyField(
                value = apiKey,
                onValueChange = onApiKey,
                label = "DashScope API Key",
            )
            RefreshableModelField(
                model = model,
                onModel = onModel,
                fetch = fetchModels,
                fallback = cachedModels.ifEmpty { AiProvider.DASHSCOPE_MODELS },
                label = "多模态模型",
            )
        }
    }
}

@Composable
private fun DefaultProviderCard(
    provider: String,
    onProvider: (String) -> Unit,
    reasoningEffort: String,
    onReasoningEffort: (String) -> Unit,
    prompt: String,
    onPrompt: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = AiProvider.parse(provider) ?: AiProvider.DASHSCOPE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Cloud)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("默认 AI 供应商", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "AI 节点设为「跟随全局」时使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("供应商", style = MaterialTheme.typography.labelMedium)
                        Text(current.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AiProvider.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { onProvider(p.name); expanded = false },
                        )
                    }
                }
            }
            ReasoningEffortPicker(
                effort = AiReasoningEffort.parse(reasoningEffort),
                onEffort = { onReasoningEffort(it.name) },
            )
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
private fun ReasoningEffortPicker(
    effort: AiReasoningEffort,
    onEffort: (AiReasoningEffort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("推理模式", style = MaterialTheme.typography.labelMedium)
                Text(effort.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            AiReasoningEffort.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            Text(
                                reasoningEffortHint(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onEffort(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun reasoningEffortHint(effort: AiReasoningEffort): String =
    when (effort) {
        AiReasoningEffort.DEFAULT -> "不传推理参数，保留模型默认行为"
        AiReasoningEffort.OFF -> "传 enable_thinking=false，减少延迟和成本"
        AiReasoningEffort.LOW -> "低预算推理，适合轻量判断"
        AiReasoningEffort.MEDIUM -> "中等预算推理，平衡速度和效果"
        AiReasoningEffort.HIGH -> "高预算推理，适合复杂图文推断"
    }

@Composable
private fun ModelScopeCard(
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    cachedModels: List<String>,
    fetchModels: suspend () -> Result<List<String>>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Cloud)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("魔搭社区设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "AI 节点可选「魔搭社区」供应商时使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ApiKeyField(
                value = apiKey,
                onValueChange = onApiKey,
                label = "ModelScope API Key",
            )
            RefreshableModelField(
                model = model,
                onModel = onModel,
                fetch = fetchModels,
                fallback = cachedModels.ifEmpty { AiProvider.MODELSCOPE_MODELS },
                label = "默认模型",
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    var visible by remember { mutableStateOf(false) }
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            Row {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(value))
                        Toast.makeText(context, "API Key 已复制", Toast.LENGTH_SHORT).show()
                    },
                    enabled = value.isNotBlank(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制 API Key")
                }
            }
        },
    )
}

@Composable
private fun RecordEngineCard(engine: String, onEngine: (String) -> Unit) {
    val overlaySelected = engine != SettingsRepository.RECORD_ENGINE_WIFI_ADB
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.TouchApp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("录制方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (overlaySelected)
                            "悬浮层录制：全屏接管触摸、边录边放，需开启无障碍"
                        else
                            "Wi-Fi ADB 录制：getevent 读取触摸，需配对 ADB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (overlaySelected) {
                    Button(
                        onClick = { onEngine(SettingsRepository.RECORD_ENGINE_OVERLAY) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("悬浮层录制") }
                    OutlinedButton(
                        onClick = { onEngine(SettingsRepository.RECORD_ENGINE_WIFI_ADB) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Wi-Fi ADB") }
                } else {
                    OutlinedButton(
                        onClick = { onEngine(SettingsRepository.RECORD_ENGINE_OVERLAY) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("悬浮层录制") }
                    Button(
                        onClick = { onEngine(SettingsRepository.RECORD_ENGINE_WIFI_ADB) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Wi-Fi ADB") }
                }
            }
        }
    }
}

@Composable
private fun PlaybackMarkerCard(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(Icons.Filled.TouchApp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("回放点击标记", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "回放时在屏幕上闪现点击/滑动/找图位置，默认开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
    }
}

@Composable
private fun AiImageCard(side: Int, onSide: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Cloud)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("AI 截图质量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "发给千问的截图分辨率，越大 token 越多",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SidePicker(
                presets = AI_IMAGE_PRESETS,
                side = side,
                onSide = onSide,
                label = "上传分辨率",
            )
        }
    }
}

@Composable
private fun ThumbnailCard(side: Int, onSide: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Image)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("历史截图质量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "AI 历史中保存截图的分辨率",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SidePicker(
                presets = THUMB_PRESETS,
                side = side,
                onSide = onSide,
                label = "分辨率",
            )
        }
    }
}

@Composable
private fun SidePicker(
    presets: List<ThumbPreset>,
    side: Int,
    onSide: (Int) -> Unit,
    label: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = presets.firstOrNull { it.side == side }?.label
        ?: "自定义 (${side}px)"
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(current, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            presets.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.label)
                            Text(
                                p.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSide(p.side)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class ThumbPreset(val side: Int, val label: String, val hint: String)

private val THUMB_PRESETS = listOf(
    ThumbPreset(480, "极简 480px", "默认，最省空间，预览较糊"),
    ThumbPreset(720, "标清 720px", "稍清晰，体积仍小"),
    ThumbPreset(1440, "清晰 1440px", "平衡清晰度与体积"),
    ThumbPreset(2160, "高清 2160px", "原图级，磁盘占用较大"),
)

private val AI_IMAGE_PRESETS = listOf(
    ThumbPreset(1024, "省 token 1024px", "约 600 视觉 token / 次"),
    ThumbPreset(1280, "默认 1280px", "约 940 视觉 token / 次，平衡"),
    ThumbPreset(1568, "高识别 1568px", "约 1450 视觉 token / 次，识别更稳"),
)

@Composable
fun WifiAdbCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.wifiAdbState.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshWifiAdb() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.connected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val statusText = when {
                state.busy -> state.message.ifBlank { "处理中..." }
                state.connected -> "已连接 ${state.host}:${state.connectPort}"
                state.message.isNotBlank() -> state.message
                else -> "未连接（使用系统无线调试配对/连接）"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(if (state.connected) Icons.Filled.Security else Icons.Filled.Link)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Wi-Fi ADB 高级录制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "录制功能通过无线调试 ADB 执行 getevent，可记录图标点击、游戏画布、自定义控件、长按与滑动。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // 截屏前台服务会占用通知，先停掉再弹通知，避免被覆盖
                        if (ServiceBus.captureReady.value) {
                            CaptureForegroundService.stop(context)
                        }
                        WifiAdbNotification.show(context)
                        // 顺手打开开发者选项，方便用户进入「无线调试」拿配对码
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    enabled = !state.busy && !state.connected,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.connected) "已连接" else "通知配对")
                }
                OutlinedButton(
                    onClick = viewModel::reconnectWifiAdb,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.connected) "刷新" else "重连")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showHelp = true }) { Text("说明") }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { showHelp = false },
            title = { Text("连接 Wi-Fi ADB") },
            text = {
                Column {
                    Text(
                        "前置：Android 11+，并打开开发者选项里的「无线调试」。\n\n" +
                        "1. 系统设置 → 关于手机 → 连点 7 次「版本号」打开开发者模式\n" +
                        "2. 点「通知配对」：会发出一条通知并自动跳到开发者选项\n" +
                        "3. 开发者选项 → 打开「无线调试」→「使用配对码配对设备」\n" +
                        "4. 保持配对弹窗打开，下拉通知点「输入配对码」回复 6 位配对码，端口会自动查找并连接\n\n" +
                        "一般不需要手动填写端口；如果自动连接失败，再检查无线调试是否保持开启。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = { showHelp = false }) { Text("好") } },
        )
    }
}

@Composable
private fun DebugLogCard() {
    val context = LocalContext.current
    val app = remember(context) { App.from(context) }
    var showLog by remember { mutableStateOf(false) }
    var showImages by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var logSize by remember { mutableStateOf(app.logFileSizeBytes()) }
    var debugImages by remember { mutableStateOf(app.debugBitmapFiles()) }

    fun refreshLog() {
        logText = app.readLog()
        logSize = app.logFileSizeBytes()
        debugImages = app.debugBitmapFiles()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(Icons.Filled.Refresh)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("调试日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "记录截图、AI、图像识别与脚本运行信息 · ${formatBytes(logSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        refreshLog()
                        showLog = true
                    },
                    shape = RoundedCornerShape(8.dp),
                ) { Text("查看日志") }
                OutlinedButton(
                    onClick = {
                        context.copyToClipboard(app.readLog(Int.MAX_VALUE), label = "WCA Debug Log")
                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                ) { Text("复制") }
                OutlinedButton(
                    onClick = {
                        debugImages = app.debugBitmapFiles()
                        showImages = true
                    },
                    shape = RoundedCornerShape(8.dp),
                ) { Text("查看图片") }
                TextButton(
                    onClick = {
                        app.clearLog()
                        refreshLog()
                        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("清空") }
            }
        }
    }

    if (showLog) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { showLog = false },
            title = { Text("调试日志") },
            text = {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = logText.ifBlank { "暂无日志" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(scroll),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showLog = false }) { Text("关闭") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        context.copyToClipboard(app.readLog(Int.MAX_VALUE), label = "WCA Debug Log")
                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    TextButton(onClick = {
                        app.clearLog()
                        refreshLog()
                        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                    }) { Text("清空") }
                }
            },
        )
    }

    if (showImages) {
        DebugImagesDialog(
            images = debugImages,
            onRefresh = { debugImages = app.debugBitmapFiles() },
            onClear = {
                val n = app.clearDebugBitmaps()
                debugImages = app.debugBitmapFiles()
                Toast.makeText(context, "已清理 $n 张调试图片", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showImages = false },
        )
    }
}

@Composable
private fun DebugImagesDialog(
    images: List<File>,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(8.dp),
        onDismissRequest = onDismiss,
        title = { Text("调试图片") },
        text = {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (images.isEmpty()) {
                    Text(
                        "暂无调试图片。运行脚本后会生成 dbg_snap_* 和 dbg_now_*。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    images.forEach { file ->
                        DebugImageItem(file)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(
                    onClick = onClear,
                    enabled = images.isNotEmpty(),
                ) { Text("一键清理") }
            }
        },
    )
}

@Composable
private fun DebugImageItem(file: File) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        BitmapFactory.decodeFile(file.absolutePath)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${file.name} · ${formatBytes(file.length())}",
            style = MaterialTheme.typography.labelMedium,
        )
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Text(
                "图片无法解码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.1fMB".format(bytes / 1024f / 1024f)
    }

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}
