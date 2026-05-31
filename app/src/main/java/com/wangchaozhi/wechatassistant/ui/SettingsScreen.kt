package com.wangchaozhi.wechatassistant.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider

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
            item { ShizukuCard(viewModel) }
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
fun ShizukuCard(viewModel: MainViewModel) {
    val state by viewModel.shizukuState.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshShizuku() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val statusText = when {
                !state.available -> "未启动（请先在 Shizuku App 中通过无线调试启动）"
                !state.granted -> "已就绪，但未授权本应用"
                else -> "已连接 · 已授权"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(if (state.granted) Icons.Filled.Security else Icons.Filled.Link)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Shizuku 高级录制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "录制功能仅使用 Shizuku 高级录制，可记录图标点击、游戏画布、自定义控件、长按与滑动。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.available && !state.granted) {
                    Button(onClick = viewModel::requestShizukuPermission, shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("授权")
                    }
                }
                OutlinedButton(onClick = viewModel::refreshShizuku, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新")
                }
                TextButton(onClick = { showHelp = true }) { Text("无线调试启动") }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { showHelp = false },
            title = { Text("用无线调试启动 Shizuku（无需电脑）") },
            text = {
                Column {
                    Text(
                        "前置：Android 11+；先在应用商店或 GitHub 装好「Shizuku」App。\n\n" +
                        "1. 系统设置 → 关于手机 → 连点 7 次「版本号」打开开发者模式\n" +
                        "2. 开发者选项 → 打开「无线调试」并保持页面停留\n" +
                        "3. 进入 Shizuku App → 选「通过无线调试启动」\n" +
                        "4. 按 Shizuku 提示操作（部分机型需用「使用配对码配对设备」的端口和配对码）\n" +
                        "5. 启动成功后回到本应用，点上方「刷新状态」→「授权本应用」\n\n" +
                        "重启手机后 Shizuku 会失效，按上述步骤再启动一次即可。",
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
