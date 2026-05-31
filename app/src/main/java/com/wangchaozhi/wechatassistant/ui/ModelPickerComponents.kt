package com.wangchaozhi.wechatassistant.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 给 verticalScroll 的容器画一个简易竖向滚动条（内容溢出时才显示）。 */
private fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    width: Dp = 4.dp,
): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max > 0) {
        val viewport = size.height
        val total = viewport + max
        val thumbH = (viewport / total) * viewport
        val thumbY = (state.value.toFloat() / max) * (viewport - thumbH)
        val w = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - w, thumbY),
            size = Size(w, thumbH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
        )
    }
}

/**
 * 可手填 + 弹窗选择的模型选择器。
 * 首次出现自动拉取一次官方模型列表并缓存；▼ 弹出居中的选择对话框（带搜索框、大列表），
 * 看得更清楚，搜索过滤时也不会跳动。刷新按钮重新拉取，拉取失败退回内置 [fallback]。
 */
@Composable
fun RefreshableModelField(
    model: String,
    onModel: (String) -> Unit,
    fetch: suspend () -> Result<List<String>>,
    fallback: List<String>,
    label: String = "模型名（可手填）",
    modifier: Modifier = Modifier,
    refreshKey: Any? = Unit,
) {
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            val r = fetch()
            loading = false
            r.onSuccess { fetched = it }.onFailure { error = it.message ?: "拉取失败" }
        }
    }

    // 首次出现自动拉取一次并缓存；refreshKey 变化（如切换供应商）时清空缓存重新拉取。
    LaunchedEffect(refreshKey) {
        fetched = null
        query = ""
        load()
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = model,
            onValueChange = onModel,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { load() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新模型列表")
                        }
                    }
                    IconButton(onClick = { query = ""; pickerOpen = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择模型")
                    }
                }
            },
        )
        val err = error
        if (err != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                "拉取失败：$err（已退回内置列表，可手填模型名）",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pickerOpen) {
        val all = fetched ?: fallback
        val list = if (query.isBlank()) all
        else all.filter { it.contains(query.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择模型") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val listScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScrollbar(listScroll, MaterialTheme.colorScheme.outline)
                            .verticalScroll(listScroll)
                            .padding(end = 8.dp),
                    ) {
                        if (list.isEmpty()) {
                            Text(
                                if (all.isEmpty()) "（无可用模型）" else "无匹配「$query」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            )
                        } else {
                            list.forEach { m ->
                                val selected = m == model
                                Text(
                                    m,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onModel(m); pickerOpen = false }
                                        .padding(horizontal = 4.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { load() }, enabled = !loading) {
                    Text(if (loading) "刷新中…" else "刷新")
                }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("取消") } },
        )
    }
}
