package com.wangchaozhi.wechatassistant.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.service.CaptureForegroundService
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.WifiAdbNotification

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
                )
            }
            item {
                QwenCard(
                    apiKey = apiKey,
                    onApiKey = { apiKey = it; viewModel.apiKey = it },
                    prompt = prompt,
                    onPrompt = { prompt = it; viewModel.defaultPrompt = it },
                    model = model,
                    onModel = { model = it; viewModel.qwenModel = it },
                    fetchModels = { viewModel.fetchModels(AiProvider.DASHSCOPE) },
                )
            }
            item {
                ModelScopeCard(
                    apiKey = msKey,
                    onApiKey = { msKey = it; viewModel.modelScopeApiKey = it },
                    model = msModel,
                    onModel = { msModel = it; viewModel.modelScopeModel = it },
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
            item { WifiAdbCard(viewModel) }
        }
    }
}

@Composable
private fun QwenCard(
    apiKey: String,
    onApiKey: (String) -> Unit,
    prompt: String,
    onPrompt: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
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
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("DashScope API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            RefreshableModelField(
                model = model,
                onModel = onModel,
                fetch = fetchModels,
                fallback = AiProvider.DASHSCOPE_MODELS,
                label = "多模态模型",
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
private fun DefaultProviderCard(provider: String, onProvider: (String) -> Unit) {
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
        }
    }
}

@Composable
private fun ModelScopeCard(
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
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
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("ModelScope API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            RefreshableModelField(
                model = model,
                onModel = onModel,
                fetch = fetchModels,
                fallback = AiProvider.MODELSCOPE_MODELS,
                label = "默认模型",
            )
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
                    enabled = !state.busy,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("通知配对")
                }
                OutlinedButton(onClick = viewModel::refreshWifiAdb, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showHelp = true }) { Text("怎么填") }
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
