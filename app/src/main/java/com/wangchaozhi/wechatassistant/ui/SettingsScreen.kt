package com.wangchaozhi.wechatassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf(viewModel.apiKey) }
    var prompt by remember { mutableStateOf(viewModel.defaultPrompt) }

    Scaffold(
        topBar = {
            TopAppBar(
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
                QwenCard(
                    apiKey = apiKey,
                    onApiKey = { apiKey = it; viewModel.apiKey = it },
                    prompt = prompt,
                    onPrompt = { prompt = it; viewModel.defaultPrompt = it },
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
fun ShizukuCard(viewModel: MainViewModel) {
    val state by viewModel.shizukuState.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshShizuku() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Shizuku 高级录制", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val statusText = when {
                !state.available -> "未启动（请先在 Shizuku App 中通过无线调试启动）"
                !state.granted -> "已就绪，但未授权本应用"
                else -> "已连接 · 已授权"
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "录制功能仅使用 Shizuku 高级录制，可记录图标点击、游戏画布、自定义控件、长按与滑动。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.available && !state.granted) {
                    Button(onClick = viewModel::requestShizukuPermission) { Text("授权本应用") }
                }
                OutlinedButton(onClick = viewModel::refreshShizuku) { Text("刷新状态") }
                TextButton(onClick = { showHelp = true }) { Text("无线调试启动") }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
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
