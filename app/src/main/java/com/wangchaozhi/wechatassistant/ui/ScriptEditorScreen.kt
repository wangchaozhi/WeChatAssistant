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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.wangchaozhi.wechatassistant.ui.theme.GlassFilledButton
import com.wangchaozhi.wechatassistant.ui.theme.GlassOutlinedButton
import com.wangchaozhi.wechatassistant.ui.theme.GlassTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    scriptId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var script by remember { mutableStateOf<Script?>(null) }
    val actions = remember { mutableStateListOf<Action>() }
    var loaded by remember { mutableStateOf(false) }
    var showAddAiDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(scriptId) {
        val data = viewModel.loadScript(scriptId)
        if (data != null) {
            script = data.script
            actions.clear()
            actions.addAll(data.actions)
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(script?.name ?: "编辑脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        val s = script
        if (!loaded || s == null) {
            Column(
                Modifier.fillMaxSize().padding(inner),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (loaded) "脚本不存在或已被删除。" else "加载中…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScriptMetaCard(script = s, onChange = { updated -> script = updated })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("动作列表（${actions.size}）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    AddActionMenu(
                        onAddSimple = { type ->
                            actions += newDefaultAction(scriptId, actions.size, type)
                            editingIndex = actions.size - 1
                        },
                        onAddAi = { showAddAiDialog = true },
                    )
                }
            }
            itemsIndexed(actions, key = { i, _ -> i }) { i, a ->
                ActionRow(
                    index = i + 1,
                    action = a,
                    canMoveUp = i > 0,
                    canMoveDown = i < actions.size - 1,
                    onMoveUp = {
                        if (i > 0) actions.move(i, i - 1)
                    },
                    onMoveDown = {
                        if (i < actions.size - 1) actions.move(i, i + 1)
                    },
                    onEdit = { editingIndex = i },
                    onDelete = { actions.removeAt(i) },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    GlassFilledButton(
                        onClick = {
                            val snapshot = actions.toList()
                            viewModel.saveScript(s, snapshot) { onBack() }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        }

        if (showAddAiDialog) {
            AddAiStepDialog(
                defaultPrompt = viewModel.defaultPrompt,
                onDismiss = { showAddAiDialog = false },
                onConfirm = { type, prompt, delayMs ->
                    actions += Action(
                        scriptId = scriptId,
                        index = actions.size,
                        type = type,
                        startX = 0f, startY = 0f,
                        durationMs = if (type == ActionType.AI_TAP) 80L else 0L,
                        delayBeforeMs = delayMs,
                        aiPrompt = prompt,
                    )
                    showAddAiDialog = false
                },
            )
        }

        val ei = editingIndex
        if (ei != null && ei in actions.indices) {
            EditActionDialog(
                action = actions[ei],
                onDismiss = { editingIndex = null },
                onConfirm = { updated ->
                    actions[ei] = updated
                    editingIndex = null
                },
            )
        }
    }
}

private fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to) return
    val item = removeAt(from)
    add(to, item)
}

@Composable
private fun ScriptMetaCard(script: Script, onChange: (Script) -> Unit) {
    var loopText by remember(script.id) { mutableStateOf(script.loopCount.toString()) }
    var speedText by remember(script.id) { mutableStateOf(script.speed.toString()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = script.name,
                onValueChange = { onChange(script.copy(name = it)) },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = loopText,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() || it == '-' }
                        loopText = cleaned
                        cleaned.toIntOrNull()?.let { onChange(script.copy(loopCount = it)) }
                    },
                    label = { Text("循环次数（-1=∞）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = speedText,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() || it == '.' }
                        speedText = cleaned
                        cleaned.toFloatOrNull()?.let {
                            onChange(script.copy(speed = it.coerceIn(0.1f, 10f)))
                        }
                    },
                    label = { Text("速度倍率") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(
    index: Int,
    action: Action,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("#$index  ${typeLabel(action.type)}", style = MaterialTheme.typography.bodyLarge)
                Text(describe(action), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
            }
            GlassTextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun AddActionMenu(
    onAddSimple: (ActionType) -> Unit,
    onAddAi: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassTextButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("节点")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("点击") },
                onClick = { onAddSimple(ActionType.TAP); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("滑动") },
                onClick = { onAddSimple(ActionType.SWIPE); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("长按") },
                onClick = { onAddSimple(ActionType.LONG_PRESS); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("等待") },
                onClick = { onAddSimple(ActionType.WAIT); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("粘贴") },
                onClick = { onAddSimple(ActionType.PASTE); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("回车") },
                onClick = { onAddSimple(ActionType.ENTER); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("AI 步骤…") },
                onClick = { onAddAi(); expanded = false },
            )
        }
    }
}

private fun newDefaultAction(scriptId: Long, index: Int, type: ActionType): Action = when (type) {
    ActionType.TAP -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 80L,
    )
    ActionType.LONG_PRESS -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 800L,
    )
    ActionType.SWIPE -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, endX = 0f, endY = 0f, durationMs = 300L,
    )
    ActionType.WAIT -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 1000L,
    )
    ActionType.SCREENSHOT_AI, ActionType.AI_TAP -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f,
    )
    ActionType.PASTE, ActionType.ENTER -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
}

private fun typeLabel(t: ActionType): String = when (t) {
    ActionType.TAP -> "点击"
    ActionType.SWIPE -> "滑动"
    ActionType.LONG_PRESS -> "长按"
    ActionType.WAIT -> "等待"
    ActionType.SCREENSHOT_AI -> "AI 截图问答"
    ActionType.AI_TAP -> "AI 找图点击"
    ActionType.PASTE -> "粘贴"
    ActionType.ENTER -> "回车"
}

private fun describe(a: Action): String = when (a.type) {
    ActionType.TAP -> "(${a.startX.toInt()}, ${a.startY.toInt()})  延迟 ${a.delayBeforeMs}ms"
    ActionType.LONG_PRESS -> "(${a.startX.toInt()}, ${a.startY.toInt()}) 长按 ${a.durationMs}ms"
    ActionType.SWIPE ->
        "(${a.startX.toInt()},${a.startY.toInt()})→(${a.endX.toInt()},${a.endY.toInt()}) ${a.durationMs}ms"
    ActionType.WAIT -> "等待 ${a.durationMs}ms"
    ActionType.SCREENSHOT_AI -> "prompt: \"${a.aiPrompt?.take(40) ?: ""}\""
    ActionType.AI_TAP -> "目标: \"${a.aiPrompt?.take(40) ?: ""}\""
    ActionType.PASTE -> "粘贴到当前焦点输入框 · 延迟 ${a.delayBeforeMs}ms"
    ActionType.ENTER -> "回车 (IME action 或换行) · 延迟 ${a.delayBeforeMs}ms"
}

@Composable
private fun AddAiStepDialog(
    defaultPrompt: String,
    onDismiss: () -> Unit,
    onConfirm: (ActionType, String, Long) -> Unit,
) {
    var type by remember { mutableStateOf(ActionType.SCREENSHOT_AI) }
    var prompt by remember { mutableStateOf(defaultPrompt) }
    var delayText by remember { mutableStateOf("500") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插入 AI 步骤") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == ActionType.SCREENSHOT_AI,
                        onClick = { type = ActionType.SCREENSHOT_AI },
                        label = { Text("截图问答") },
                    )
                    FilterChip(
                        selected = type == ActionType.AI_TAP,
                        onClick = { type = ActionType.AI_TAP },
                        label = { Text("找图点击") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = {
                        Text(if (type == ActionType.AI_TAP) "要点击的目标（如：登录按钮）" else "Prompt")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                    label = { Text("执行前等待 (ms)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            GlassFilledButton(onClick = {
                onConfirm(type, prompt, delayText.toLongOrNull() ?: 0L)
            }) { Text("添加") }
        },
        dismissButton = { GlassTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditActionDialog(
    action: Action,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    var startX by remember { mutableStateOf(action.startX.toString()) }
    var startY by remember { mutableStateOf(action.startY.toString()) }
    var endX by remember { mutableStateOf(action.endX.toString()) }
    var endY by remember { mutableStateOf(action.endY.toString()) }
    var duration by remember { mutableStateOf(action.durationMs.toString()) }
    var delay by remember { mutableStateOf(action.delayBeforeMs.toString()) }
    var aiPrompt by remember { mutableStateOf(action.aiPrompt.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${typeLabel(action.type)}") },
        text = {
            Column {
                AssistChip(onClick = {}, label = { Text("#${action.index + 1}  ${typeLabel(action.type)}") })
                Spacer(Modifier.height(8.dp))
                when (action.type) {
                    ActionType.TAP, ActionType.LONG_PRESS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(startX, { startX = it }, "X", Modifier.weight(1f))
                            NumField(startY, { startY = it }, "Y", Modifier.weight(1f))
                        }
                    }
                    ActionType.SWIPE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(startX, { startX = it }, "起 X", Modifier.weight(1f))
                            NumField(startY, { startY = it }, "起 Y", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(endX, { endX = it }, "终 X", Modifier.weight(1f))
                            NumField(endY, { endY = it }, "终 Y", Modifier.weight(1f))
                        }
                    }
                    ActionType.WAIT, ActionType.SCREENSHOT_AI, ActionType.AI_TAP,
                    ActionType.PASTE, ActionType.ENTER -> { /* no coords */ }
                }
                Spacer(Modifier.height(6.dp))
                if (action.type != ActionType.SCREENSHOT_AI &&
                    action.type != ActionType.PASTE &&
                    action.type != ActionType.ENTER
                ) {
                    NumField(duration, { duration = it }, "持续 (ms)", Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                NumField(delay, { delay = it }, "执行前等待 (ms)", Modifier.fillMaxWidth())
                if (action.type == ActionType.SCREENSHOT_AI || action.type == ActionType.AI_TAP) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        label = {
                            Text(if (action.type == ActionType.AI_TAP) "目标描述" else "Prompt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            GlassFilledButton(onClick = {
                onConfirm(
                    action.copy(
                        startX = startX.toFloatOrNull() ?: action.startX,
                        startY = startY.toFloatOrNull() ?: action.startY,
                        endX = endX.toFloatOrNull() ?: action.endX,
                        endY = endY.toFloatOrNull() ?: action.endY,
                        durationMs = duration.toLongOrNull() ?: action.durationMs,
                        delayBeforeMs = delay.toLongOrNull() ?: action.delayBeforeMs,
                        aiPrompt = aiPrompt.ifBlank { null },
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { GlassTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == '.' || it == '-' }) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}
