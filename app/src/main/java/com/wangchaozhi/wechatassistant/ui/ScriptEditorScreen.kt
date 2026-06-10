package com.wangchaozhi.wechatassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionDefaults
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
import com.wangchaozhi.wechatassistant.service.ServiceBus
import kotlin.math.min

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
    val context = LocalContext.current
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
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
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
                        durationMs = 0L,
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
                onRecaptureTemplate = {
                    android.widget.Toast.makeText(
                        context,
                        "请在节点图中使用悬浮面板现场框选模板",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
                onClearTemplate = {
                    // 清空模板，下次「选图/框选」即为重选（替换）。region 留给后续框选覆盖。
                    actions[ei] = actions[ei].copy(templatePath = null)
                },
                onRecaptureAiRegion = {
                    android.widget.Toast.makeText(
                        context,
                        "请在节点图中使用悬浮面板现场框选 AI 问答区域",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
                onClearAiRegion = {
                    actions[ei] = actions[ei].copy(startX = 0f, startY = 0f, endX = 0f, endY = 0f)
                },
                fetchModels = { viewModel.fetchModels(it) },
                cachedModels = { viewModel.cachedModels(it) },
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
            if (action.type == ActionType.IMAGE_MATCH) {
                TemplateThumb(action.templatePath, 44.dp)
                Spacer(Modifier.width(10.dp))
            }
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
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun TemplateThumb(
    path: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val firstPath = remember(path) { TemplateMatchUseCase.splitTemplatePaths(path).firstOrNull() }
    val image = remember(firstPath) {
        firstPath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "模板",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("?", style = MaterialTheme.typography.bodySmall)
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
        TextButton(onClick = { expanded = true }) {
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
                text = { Text("等待页面变化") },
                onClick = { onAddSimple(ActionType.WAIT_PAGE_CHANGE); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("AI 步骤…") },
                onClick = { onAddAi(); expanded = false },
            )
        }
    }
}

internal fun newDefaultAction(scriptId: Long, index: Int, type: ActionType): Action = when (type) {
    ActionType.TAP -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f,
        durationMs = ActionDefaults.QUICK_TAP_MS,
        delayBeforeMs = ActionDefaults.DEFAULT_CLICK_DELAY_MS,
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
    ActionType.SCREENSHOT_AI -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
    ActionType.IMAGE_MATCH -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f,
        durationMs = ActionDefaults.QUICK_TAP_MS,
        delayBeforeMs = ActionDefaults.DEFAULT_IMAGE_DELAY_MS,
        retryCount = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
        matchThreshold = 0.70f,
    )
    ActionType.PASTE, ActionType.ENTER -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
    ActionType.WAIT_PAGE_CHANGE -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 800L, retryCount = 10,
    )
    ActionType.START -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
    ActionType.IF_IMAGE_EXISTS -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f,
        durationMs = 0L,
        retryCount = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
        matchThreshold = 0.70f,
    )
    ActionType.LOOP -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L, retryCount = 3,
    )
    ActionType.STOP -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
    ActionType.CALL_SCRIPT -> Action(
        scriptId = scriptId, index = index, type = type,
        startX = 0f, startY = 0f, durationMs = 0L,
    )
}

internal fun typeLabel(t: ActionType): String = when (t) {
    ActionType.TAP -> "点击"
    ActionType.SWIPE -> "滑动"
    ActionType.LONG_PRESS -> "长按"
    ActionType.WAIT -> "等待"
    ActionType.SCREENSHOT_AI -> "AI 截图问答"
    ActionType.IMAGE_MATCH -> "选图点击"
    ActionType.PASTE -> "粘贴"
    ActionType.ENTER -> "回车"
    ActionType.WAIT_PAGE_CHANGE -> "等待页面变化"
    ActionType.START -> "开始"
    ActionType.IF_IMAGE_EXISTS -> "条件：图像是否存在"
    ActionType.LOOP -> "循环 N 次"
    ActionType.STOP -> "停止"
    ActionType.CALL_SCRIPT -> "调用脚本"
}

private fun describe(a: Action): String = when (a.type) {
    ActionType.TAP -> "(${a.startX.toInt()}, ${a.startY.toInt()})  延迟 ${a.delayBeforeMs}ms"
    ActionType.LONG_PRESS -> "(${a.startX.toInt()}, ${a.startY.toInt()}) 长按 ${a.durationMs}ms"
    ActionType.SWIPE ->
        "(${a.startX.toInt()},${a.startY.toInt()})→(${a.endX.toInt()},${a.endY.toInt()}) ${a.durationMs}ms"
    ActionType.WAIT -> "等待 ${a.durationMs}ms" + if (a.randomExtraMs > 0) " (+0~${a.randomExtraMs}ms)" else ""
    ActionType.SCREENSHOT_AI -> {
        val region = if (a.endX > a.startX && a.endY > a.startY)
            " · 区域(${a.startX.toInt()},${a.startY.toInt()})-(${a.endX.toInt()},${a.endY.toInt()})"
        else " · 整屏"
        "prompt: \"${a.aiPrompt?.take(40) ?: ""}\"$region"
    }
    ActionType.IMAGE_MATCH ->
        "${if (templateCount(a.templatePath) > 0) "找图点击(${templateCount(a.templatePath)}张)" else "⚠ 未设模板"} · 精度 ${imagePrecision(a.matchThreshold)} · 延迟 ${a.delayBeforeMs}ms"
    ActionType.PASTE -> "粘贴到当前焦点输入框 · 延迟 ${a.delayBeforeMs}ms"
    ActionType.ENTER -> "回车 (IME action 或换行) · 延迟 ${a.delayBeforeMs}ms"
    ActionType.WAIT_PAGE_CHANGE ->
        "页面没变就重复前 ${a.repeatPrevSteps} 步 · 最多 ${a.retryCount} 次 · 间隔 ${a.durationMs}ms"
    ActionType.START -> "图入口"
    ActionType.IF_IMAGE_EXISTS ->
        "${if (templateCount(a.templatePath) > 0) "区域找图(模板已设置)" else "⚠ 未设模板"} 走「有」，否则走「无」 · 精度 ${imagePrecision(a.matchThreshold)}"
    ActionType.LOOP -> "循环 ${a.retryCount} 次 · 继续走「环」，到次数走「完」"
    ActionType.STOP -> "终止整张图的执行"
    ActionType.CALL_SCRIPT ->
        if (a.callScriptId != null) "调用脚本 #${a.callScriptId} 作为子流程" else "⚠ 未选择目标脚本"
}

private fun templateCount(path: String?): Int = TemplateMatchUseCase.splitTemplatePaths(path).size

private fun isImageRecognition(type: ActionType): Boolean =
    type == ActionType.IMAGE_MATCH || type == ActionType.IF_IMAGE_EXISTS

private fun imagePrecision(threshold: Float): Int = TemplateMatchUseCase.thresholdToPrecision(threshold)

private fun imagePrecisionText(threshold: Float): String = imagePrecision(threshold).toString()

private fun imagePrecisionInputToThreshold(raw: String, fallback: Float): Float {
    val value = raw.toFloatOrNull() ?: return fallback
    return if (value <= 1f) {
        value.coerceIn(0f, 1f)
    } else {
        TemplateMatchUseCase.precisionToThreshold(value.toInt())
    }
}

@Composable
private fun AddAiStepDialog(
    defaultPrompt: String,
    onDismiss: () -> Unit,
    onConfirm: (ActionType, String, Long) -> Unit,
) {
    var type by remember { mutableStateOf(ActionType.SCREENSHOT_AI) }
    var prompt by remember { mutableStateOf(defaultPrompt) }
    var delayText by remember { mutableStateOf(ActionDefaults.DEFAULT_CLICK_DELAY_MS.toString()) }
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
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
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
            Button(onClick = {
                onConfirm(type, prompt, delayText.toLongOrNull() ?: 0L)
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EditActionDialog(
    action: Action,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
    onRecaptureTemplate: () -> Unit = {},
    onClearTemplate: () -> Unit = {},
    onRecaptureAiRegion: () -> Unit = {},
    onClearAiRegion: () -> Unit = {},
    fetchModels: suspend (AiProvider) -> Result<List<String>> = { Result.success(it.models) },
    cachedModels: (AiProvider) -> List<String> = { emptyList() },
    // CALL_SCRIPT 目标脚本可选列表（已排除当前脚本自身）。
    callableScripts: List<Script> = emptyList(),
) {
    var startX by remember { mutableStateOf(action.startX.toString()) }
    var startY by remember { mutableStateOf(action.startY.toString()) }
    var endX by remember { mutableStateOf(action.endX.toString()) }
    var endY by remember { mutableStateOf(action.endY.toString()) }
    var duration by remember { mutableStateOf(action.durationMs.toString()) }
    var delay by remember { mutableStateOf(action.delayBeforeMs.toString()) }
    var randomExtra by remember { mutableStateOf(action.randomExtraMs.toString()) }
    var aiPrompt by remember { mutableStateOf(action.aiPrompt.orEmpty()) }
    var threshold by remember {
        mutableStateOf(
            if (isImageRecognition(action.type)) imagePrecisionText(action.matchThreshold)
            else action.matchThreshold.toString()
        )
    }
    var retry by remember {
        mutableStateOf(
            if (isImageRecognition(action.type) && action.retryCount == 10)
                ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX.toString()
            else
                action.retryCount.toString()
        )
    }
    var upRetry by remember { mutableStateOf(action.upFallbackPx.toString()) }
    var repeatSteps by remember { mutableStateOf(action.repeatPrevSteps.toString()) }
    var alias by remember { mutableStateOf(action.alias.orEmpty()) }
    // CALL_SCRIPT：选中的目标脚本 id。
    var callScriptId by remember { mutableStateOf(action.callScriptId) }
    // AI 节点：供应商（null=跟随全局）与具体模型。
    var aiProvider by remember { mutableStateOf(AiProvider.parse(action.aiProvider)) }
    var aiModel by remember { mutableStateOf(action.aiModel.orEmpty()) }
    var showPositionPreview by remember { mutableStateOf(false) }
    var showIfTemplateActions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun showActionPosition() {
        val sx = startX.toFloatOrNull() ?: action.startX
        val sy = startY.toFloatOrNull() ?: action.startY
        val ex = endX.toFloatOrNull() ?: action.endX
        val ey = endY.toFloatOrNull() ?: action.endY
        val marker = when (action.type) {
            ActionType.TAP -> ServiceBus.PositionMarker.Point(sx, sy, "点击")
            ActionType.LONG_PRESS -> ServiceBus.PositionMarker.Point(sx, sy, "长按")
            ActionType.SWIPE -> ServiceBus.PositionMarker.Swipe(
                sx,
                sy,
                ex,
                ey,
                "滑动",
            )
            else -> ServiceBus.PositionMarker.Region(
                android.graphics.Rect(
                    sx.toInt(),
                    sy.toInt(),
                    ex.toInt(),
                    ey.toInt(),
                ),
                typeLabel(action.type),
            )
        }
        if (ServiceBus.overlayReady.value) {
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.FlashPositionMarker(marker))
        } else {
            android.widget.Toast.makeText(
                context,
                "启动悬浮面板后可在真实屏幕上闪烁显示位置",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            if (marker is ServiceBus.PositionMarker.Region) showPositionPreview = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${typeLabel(action.type)}") },
        text = {
            Column {
                AssistChip(onClick = {}, label = { Text("#${action.index + 1}  ${typeLabel(action.type)}") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名（可选，仅用于显示）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when (action.type) {
                    ActionType.TAP, ActionType.LONG_PRESS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(startX, { startX = it }, "X", Modifier.weight(1f))
                            NumField(startY, { startY = it }, "Y", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { showActionPosition() }, modifier = Modifier.fillMaxWidth()) {
                            Text("显示位置")
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
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { showActionPosition() }, modifier = Modifier.fillMaxWidth()) {
                            Text("显示位置")
                        }
                    }
                    ActionType.WAIT, ActionType.SCREENSHOT_AI,
                    ActionType.IMAGE_MATCH, ActionType.PASTE, ActionType.ENTER,
                    ActionType.WAIT_PAGE_CHANGE,
                    ActionType.START, ActionType.IF_IMAGE_EXISTS,
                    ActionType.LOOP, ActionType.STOP, ActionType.CALL_SCRIPT -> { /* no coords */ }
                }
                if (action.type == ActionType.WAIT_PAGE_CHANGE) {
                    Spacer(Modifier.height(6.dp))
                    NumField(repeatSteps, { repeatSteps = it }, "每次重复执行的前几步 (如出去+进来=2)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(retry, { retry = it }, "页面未变最多重试次数", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(duration, { duration = it }, "轮询间隔 (ms)", Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(6.dp))
                    if (action.type != ActionType.SCREENSHOT_AI &&
                        action.type != ActionType.IMAGE_MATCH &&
                        action.type != ActionType.PASTE &&
                        action.type != ActionType.ENTER &&
                        action.type != ActionType.LOOP &&
                        action.type != ActionType.STOP &&
                        action.type != ActionType.CALL_SCRIPT
                    ) {
                        NumField(
                            duration,
                            { duration = it },
                            when (action.type) {
                                ActionType.IF_IMAGE_EXISTS -> "等画面稳定再判 (ms，0=单次判定)"
                                else -> "持续 (ms)"
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    if (action.type == ActionType.WAIT) {
                        Spacer(Modifier.height(6.dp))
                        NumField(randomExtra, { randomExtra = it }, "随机附加等待 (0~N ms，0=不抖动)", Modifier.fillMaxWidth())
                    }
                    if (action.type != ActionType.IF_IMAGE_EXISTS) {
                        Spacer(Modifier.height(6.dp))
                        NumField(delay, { delay = it }, "执行前等待 (ms)", Modifier.fillMaxWidth())
                    }
                }
                if (action.type == ActionType.SCREENSHOT_AI) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    AiProviderModelPicker(
                        provider = aiProvider,
                        model = aiModel,
                        fetchModels = fetchModels,
                        cachedModels = cachedModels,
                        onProvider = { p ->
                            // 切换供应商时，模型名跟着切到新供应商的默认模型；「跟随全局」则清空。
                            if (p != aiProvider) {
                                aiModel = if (p == null) "" else p.models.firstOrNull().orEmpty()
                            }
                            aiProvider = p
                        },
                        onModel = { aiModel = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    val sx = startX.toFloatOrNull() ?: action.startX
                    val sy = startY.toFloatOrNull() ?: action.startY
                    val ex = endX.toFloatOrNull() ?: action.endX
                    val ey = endY.toFloatOrNull() ?: action.endY
                    val hasRegion = ex > sx && ey > sy
                    Text(
                        if (hasRegion)
                            "问答区域：(${sx.toInt()},${sy.toInt()})-(${ex.toInt()},${ey.toInt()})"
                        else "问答区域：整屏",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onRecaptureAiRegion, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hasRegion) "重新框选问答区域" else "框选问答区域")
                    }
                    if (hasRegion) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { showActionPosition() }, modifier = Modifier.fillMaxWidth()) {
                            Text("显示区域")
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                startX = "0"
                                startY = "0"
                                endX = "0"
                                endY = "0"
                                onClearAiRegion()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("清空区域（整屏问答）")
                        }
                    }
                }
                if (action.type == ActionType.IMAGE_MATCH) {
                    val count = templateCount(action.templatePath)
                    Spacer(Modifier.height(6.dp))
                    NumField(threshold, { threshold = it }, "识别精度 (0~100，越大越严格)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(upRetry, { upRetry = it }, "上方容错 (px，0=关闭)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(retry, { retry = it }, "下方容错 (px，0=关闭)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TemplateThumb(action.templatePath, 64.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (count > 0) "点击目标模板 · 共 ${count} 张（命中任一张就点击）" else "⚠ 尚未设置模板图",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val hasRegion = action.endX > action.startX && action.endY > action.startY
                    if (hasRegion) {
                        Text(
                            "位置：(${action.startX.toInt()},${action.startY.toInt()})-(${action.endX.toInt()},${action.endY.toInt()})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { showActionPosition() }, modifier = Modifier.fillMaxWidth()) {
                            Text("显示位置")
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedButton(onClick = onRecaptureTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text(if (count > 0) "继续添加模板" else "选图 / 截取模板")
                    }
                    if (count > 0) {
                        TextButton(onClick = onClearTemplate, modifier = Modifier.fillMaxWidth()) {
                            Text("清空模板（重选）")
                        }
                    }
                }
                if (action.type == ActionType.IF_IMAGE_EXISTS) {
                    val hasTemplate = templateCount(action.templatePath) > 0
                    Spacer(Modifier.height(6.dp))
                    NumField(threshold, { threshold = it }, "识别精度 (0~100，越大越严格)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(upRetry, { upRetry = it }, "上方容错 (px，0=关闭)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    NumField(retry, { retry = it }, "下方容错 (px，0=关闭)", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TemplateThumb(
                            action.templatePath,
                            64.dp,
                            Modifier.clickable {
                                if (hasTemplate) {
                                    showIfTemplateActions = true
                                } else {
                                    onRecaptureTemplate()
                                }
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (hasTemplate) "搜索模板已设置" else "⚠ 尚未设置模板图",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val hasRegion = action.endX > action.startX && action.endY > action.startY
                    if (hasRegion) {
                        Text(
                            "搜索范围：(${action.startX.toInt()},${action.startY.toInt()})-(${action.endX.toInt()},${action.endY.toInt()})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { showActionPosition() }, modifier = Modifier.fillMaxWidth()) {
                            Text("显示位置")
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (action.type == ActionType.LOOP) {
                    Spacer(Modifier.height(6.dp))
                    NumField(retry, { retry = it }, "循环次数", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("把「环」出口连回循环体、循环体末尾再连回本节点：循环体会执行指定次数，然后走「完」出口往下。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (action.type == ActionType.STOP) {
                    Spacer(Modifier.height(6.dp))
                    Text("执行到此节点立即终止整张图（含整体循环）。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (action.type == ActionType.CALL_SCRIPT) {
                    Spacer(Modifier.height(6.dp))
                    CallScriptPicker(
                        scripts = callableScripts,
                        selectedId = callScriptId,
                        onSelect = { callScriptId = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("作为子流程把目标脚本整图跑一遍（含其整体循环），结束后回到本节点继续往下。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    action.copy(
                        startX = startX.toFloatOrNull() ?: action.startX,
                        startY = startY.toFloatOrNull() ?: action.startY,
                        endX = endX.toFloatOrNull() ?: action.endX,
                        endY = endY.toFloatOrNull() ?: action.endY,
                        durationMs = duration.toLongOrNull() ?: action.durationMs,
                        delayBeforeMs = if (action.type == ActionType.IF_IMAGE_EXISTS) {
                            0L
                        } else {
                            delay.toLongOrNull() ?: action.delayBeforeMs
                        },
                        randomExtraMs = randomExtra.toLongOrNull()?.coerceAtLeast(0L) ?: action.randomExtraMs,
                        aiPrompt = aiPrompt.ifBlank { null },
                        matchThreshold = if (isImageRecognition(action.type))
                            imagePrecisionInputToThreshold(threshold, action.matchThreshold)
                        else
                            threshold.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: action.matchThreshold,
                        retryCount = retry.toIntOrNull()?.coerceAtLeast(0) ?: action.retryCount,
                        upFallbackPx = upRetry.toIntOrNull()?.coerceAtLeast(0) ?: action.upFallbackPx,
                        repeatPrevSteps = repeatSteps.toIntOrNull()?.coerceAtLeast(1)
                            ?: action.repeatPrevSteps,
                        templatePath = action.templatePath,
                        alias = alias.ifBlank { null },
                        aiProvider = aiProvider?.name,
                        aiModel = if (aiProvider == null) null else aiModel.ifBlank { null },
                        callScriptId = if (action.type == ActionType.CALL_SCRIPT) callScriptId else action.callScriptId,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (showIfTemplateActions) {
        AlertDialog(
            onDismissRequest = { showIfTemplateActions = false },
            title = { Text("模板") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TemplateThumb(action.templatePath, 72.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("搜索模板已设置", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showIfTemplateActions = false
                    onRecaptureTemplate()
                }) { Text("替换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showIfTemplateActions = false
                    onClearTemplate()
                }) { Text("删除") }
            },
        )
    }
    if (showPositionPreview) {
        RegionPositionPreviewDialog(
            title = typeLabel(action.type),
            left = action.startX,
            top = action.startY,
            right = action.endX,
            bottom = action.endY,
            onDismiss = { showPositionPreview = false },
        )
    }
}

@Composable
private fun RegionPositionPreviewDialog(
    title: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    onDismiss: () -> Unit,
) {
    val dm = LocalContext.current.resources.displayMetrics
    val screenW = dm.widthPixels.toFloat().coerceAtLeast(1f)
    val screenH = dm.heightPixels.toFloat().coerceAtLeast(1f)
    val l = left.coerceIn(0f, screenW)
    val t = top.coerceIn(0f, screenH)
    val r = right.coerceIn(l, screenW)
    val b = bottom.coerceIn(t, screenH)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xF2000000)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("$title · 位置预览", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val cw = with(density) { maxWidth.toPx() }
                        val ch = with(density) { maxHeight.toPx() }
                        val scale = min(cw / screenW, ch / screenH)
                        val dispW = with(density) { (screenW * scale).toDp() }
                        val dispH = with(density) { (screenH * scale).toDp() }
                        Box(Modifier.size(dispW, dispH).background(Color(0xFF202124))) {
                            Canvas(Modifier.fillMaxSize()) {
                                val dl = l * scale
                                val dt = t * scale
                                val dr = r * scale
                                val db = b * scale
                                val dim = Color(0xAA000000)
                                drawRect(dim, Offset(0f, 0f), Size(size.width, dt))
                                drawRect(dim, Offset(0f, db), Size(size.width, size.height - db))
                                drawRect(dim, Offset(0f, dt), Size(dl, db - dt))
                                drawRect(dim, Offset(dr, dt), Size(size.width - dr, db - dt))
                                drawRect(
                                    color = Color(0xFF00E5FF),
                                    topLeft = Offset(dl, dt),
                                    size = Size(dr - dl, db - dt),
                                    style = Stroke(width = 4f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun CallScriptPicker(
    scripts: List<Script>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = scripts.firstOrNull { it.id == selectedId }?.name
        ?: selectedId?.let { "脚本 #$it（已删除？）" }
        ?: "未选择"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("目标脚本：$selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (scripts.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("没有其它脚本可调用") },
                    onClick = { expanded = false },
                )
            }
            scripts.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name) },
                    onClick = { onSelect(s.id); expanded = false },
                )
            }
        }
    }
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

/** AI 节点的「供应商 + 模型」选择器。provider 为 null 表示跟随全局设置。 */
@Composable
private fun AiProviderModelPicker(
    provider: AiProvider?,
    model: String,
    fetchModels: suspend (AiProvider) -> Result<List<String>>,
    cachedModels: (AiProvider) -> List<String>,
    onProvider: (AiProvider?) -> Unit,
    onModel: (String) -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("模型供应商", style = MaterialTheme.typography.labelMedium)
                Text(provider?.label ?: "跟随全局设置", style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
            DropdownMenuItem(
                text = { Text("跟随全局设置") },
                onClick = { onProvider(null); providerMenu = false },
            )
            AiProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onProvider(p); providerMenu = false },
                )
            }
        }
    }

    if (provider != null) {
        Spacer(Modifier.height(6.dp))
        RefreshableModelField(
            model = model,
            onModel = onModel,
            fetch = { fetchModels(provider) },
            fallback = cachedModels(provider).ifEmpty { provider.models },
            refreshKey = provider,
        )
    }
}
