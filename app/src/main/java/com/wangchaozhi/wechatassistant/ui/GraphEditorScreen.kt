package com.wangchaozhi.wechatassistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import kotlin.math.roundToInt

// 节点固定尺寸（图空间 dp，scale=1 时）。端口锚点由此算术求得，免去逐卡测量。
private const val NODE_W = 160f
private const val NODE_H = 64f
private const val PORT_HIT = 48f   // 连线落点命中输入端口的 dp 半径（放宽便于吸附）

// reversed=false：从某节点输出口(anchorId,port)拖向目标输入口。
// reversed=true ：从某节点输入口(anchorId)拖向源节点输出口。
private data class Wiring(val anchorId: Long, val port: Int, val end: Offset, val reversed: Boolean = false)

/** 节点的输出端口列表（IF 有两个，其它一个）。 */
private fun outPorts(n: Action): List<Int> =
    if (n.type == ActionType.IF_PAGE_CHANGED) listOf(0, 1) else listOf(0)

private fun inAnchor(n: Action) = Offset(n.posX + NODE_W / 2, n.posY)
private fun outAnchor(n: Action, port: Int): Offset = when {
    n.type == ActionType.IF_PAGE_CHANGED && port == 0 -> Offset(n.posX + NODE_W * 0.3f, n.posY + NODE_H)
    n.type == ActionType.IF_PAGE_CHANGED && port == 1 -> Offset(n.posX + NODE_W * 0.7f, n.posY + NODE_H)
    else -> Offset(n.posX + NODE_W / 2, n.posY + NODE_H)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GraphEditorScreen(
    scriptId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var script by remember { mutableStateOf<Script?>(null) }
    val nodes = remember { mutableStateListOf<Action>() }
    val edges = remember { mutableStateListOf<Edge>() }
    var loaded by remember { mutableStateOf(false) }
    var tempId by remember { mutableStateOf(-1L) }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }   // px

    var editingId by remember { mutableStateOf<Long?>(null) }
    var contextId by remember { mutableStateOf<Long?>(null) }   // 长按弹出复制/删除的节点
    var wiring by remember { mutableStateOf<Wiring?>(null) }
    var showMeta by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val undoStack = remember { mutableStateListOf<Pair<List<Action>, List<Edge>>>() }
    val redoStack = remember { mutableStateListOf<Pair<List<Action>, List<Edge>>>() }

    val density = LocalDensity.current.density
    val context = androidx.compose.ui.platform.LocalContext.current

    // 选图：pendingRecaptureId=给 IMAGE_MATCH 设模板；pendingRegionId=给 SNAPSHOT 框选屏幕范围。
    var pendingRecaptureId by remember { mutableStateOf<Long?>(null) }
    var pendingRegionId by remember { mutableStateOf<Long?>(null) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val bmp = uri?.let { decodeBitmap(context, it) }
        if (bmp != null) cropSource = bmp else { pendingRecaptureId = null; pendingRegionId = null }
    }

    LaunchedEffect(scriptId) {
        val data = viewModel.loadGraphScript(scriptId)
        if (data != null) {
            script = data.script
            nodes.clear(); nodes.addAll(data.actions)
            edges.clear(); edges.addAll(data.edges)
            // 兜底：旧/异常脚本若无 START，补一个。
            if (nodes.none { it.type == ActionType.START }) {
                nodes.add(0, Action(id = tempId--, scriptId = scriptId, index = 0,
                    type = ActionType.START, startX = 0f, startY = 0f, posX = 120f, posY = 120f))
            }
        }
        loaded = true
    }

    fun screenToGraph(p: Offset) = Offset((p.x - offset.x) / scale / density, (p.y - offset.y) / scale / density)

    // 撤回/重做：每次改动前把当前 (节点, 边) 快照压入 undo 栈，并清空 redo 栈（标准行为）。
    // Action/Edge 为不可变 data class，浅拷贝列表即可。
    fun pushUndo() {
        undoStack.add(nodes.toList() to edges.toList())
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }
    fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        redoStack.add(nodes.toList() to edges.toList())
        nodes.clear(); nodes.addAll(last.first)
        edges.clear(); edges.addAll(last.second)
    }
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(nodes.toList() to edges.toList())
        nodes.clear(); nodes.addAll(next.first)
        edges.clear(); edges.addAll(next.second)
    }

    fun addNode(type: ActionType, viewportCenterPx: Offset) {
        pushUndo()
        val g = screenToGraph(viewportCenterPx)
        nodes += newDefaultAction(scriptId, nodes.size, type).copy(
            id = tempId--, posX = g.x - NODE_W / 2, posY = g.y - NODE_H / 2,
        )
    }

    fun connect(fromId: Long, fromPort: Int, toId: Long) {
        if (fromId == toId) return
        // 一个出口可连多条线（运行时按顺序依次执行）；仅去重完全相同的边。
        if (edges.any { it.fromActionId == fromId && it.fromPort == fromPort && it.toActionId == toId }) return
        pushUndo()
        edges += Edge(scriptId = scriptId, fromActionId = fromId, toActionId = toId, fromPort = fromPort)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(script?.name ?: "节点图") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Text("撤回") }
                    TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Text("重做") }
                    IconButton(onClick = { showMeta = true }) { Icon(Icons.Default.Settings, "信息") }
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "加节点") }
                        AddNodeMenu(
                            expanded = addMenu,
                            onDismiss = { addMenu = false },
                            onPick = { type ->
                                addMenu = false
                                // 放到当前画布可视中心（用近似屏幕中心）。
                                addNode(type, Offset(540f, 900f))
                            },
                        )
                    }
                    TextButton(onClick = {
                        val s = script ?: return@TextButton
                        viewModel.saveGraph(s, nodes.toList(), edges.toList()) { onBack() }
                    }) { Text("保存") }
                },
            )
        },
    ) { inner ->
        val s = script
        if (!loaded || s == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(if (loaded) "脚本不存在或已被删除。" else "加载中…")
            }
            return@Scaffold
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.3f, 3f)
                        offset = (offset - centroid) * (newScale / scale) + centroid + pan
                        scale = newScale
                    }
                }
        ) {
            // 内容层：平移+缩放整体应用在这一层，节点与连线都画在「图空间 dp」里。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        clip = false
                    }
            ) {
                // 连线
                Canvas(Modifier.fillMaxSize()) {
                    val byId = nodes.associateBy { it.id }
                    edges.forEach { e ->
                        val from = byId[e.fromActionId] ?: return@forEach
                        val to = byId[e.toActionId] ?: return@forEach
                        val a = outAnchor(from, e.fromPort) * density
                        val b = inAnchor(to) * density
                        val color = when {
                            from.type == ActionType.IF_PAGE_CHANGED && e.fromPort == 0 -> Color(0xFF2E7D32)
                            from.type == ActionType.IF_PAGE_CHANGED && e.fromPort == 1 -> Color(0xFFC62828)
                            else -> Color(0xFF607D8B)
                        }
                        drawCurve(a, b, color)
                    }
                    wiring?.let { w ->
                        val anchorNode = nodes.firstOrNull { it.id == w.anchorId } ?: return@let
                        if (w.reversed) {
                            drawCurve(w.end * density, inAnchor(anchorNode) * density, Color(0xFF1565C0))
                        } else {
                            drawCurve(outAnchor(anchorNode, w.port) * density, w.end * density, Color(0xFF1565C0))
                        }
                    }
                }

                // 删边的小 × （放在每条边中点）
                val byId = nodes.associateBy { it.id }
                edges.toList().forEach { e ->
                    val from = byId[e.fromActionId] ?: return@forEach
                    val to = byId[e.toActionId] ?: return@forEach
                    val mid = (outAnchor(from, e.fromPort) + inAnchor(to)) / 2f
                    Box(
                        Modifier
                            .offset { IntOffset((mid.x * density).roundToInt() - 18, (mid.y * density).roundToInt() - 18) }
                            .size(20.dp)
                            .background(Color(0xCCFFFFFF), CircleShape)
                            .combinedClickable(onClick = { pushUndo(); edges.remove(e) }, onLongClick = {}),
                        contentAlignment = Alignment.Center,
                    ) { Text("×", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall) }
                }

                // 节点卡片（只负责点击/长按/拖动；端口单独画，避免被卡片边界裁剪、抢手势）
                nodes.toList().forEach { node ->
                    NodeCard(
                        node = node,
                        density = density,
                        onDragStart = { pushUndo() },
                        onDrag = { dx, dy ->
                            val i = nodes.indexOfFirst { it.id == node.id }
                            if (i >= 0) nodes[i] = nodes[i].copy(
                                posX = nodes[i].posX + dx / density,
                                posY = nodes[i].posY + dy / density,
                            )
                        },
                        onTap = { editingId = node.id },
                        onLongPress = { if (node.type != ActionType.START) contextId = node.id },
                    )
                }

                // 端口（与节点平级，独立完整触摸区）。输入口/输出口都可拖，自动判方向建边。
                val onPortEnd: () -> Unit = {
                    val w = wiring
                    if (w != null) {
                        if (!w.reversed) {
                            // 从输出口拖出 → 落在某节点输入口
                            val target = nodes.firstOrNull { n ->
                                n.id != w.anchorId && n.type != ActionType.START &&
                                    (inAnchor(n) - w.end).getDistance() < PORT_HIT
                            }
                            if (target != null) connect(w.anchorId, w.port, target.id)
                        } else {
                            // 从输入口拖出 → 落在某节点输出口（含 IF 的两个口，取最近）
                            var best: Triple<Long, Int, Float>? = null
                            nodes.forEach { n ->
                                if (n.id == w.anchorId) return@forEach
                                outPorts(n).forEach { p ->
                                    val d = (outAnchor(n, p) - w.end).getDistance()
                                    if (d < PORT_HIT && (best == null || d < best!!.third)) {
                                        best = Triple(n.id, p, d)
                                    }
                                }
                            }
                            best?.let { connect(it.first, it.second, w.anchorId) }
                        }
                    }
                    wiring = null
                }
                val onDragEnd: (Float, Float) -> Unit = { dx, dy ->
                    wiring = wiring?.let { it.copy(end = it.end + Offset(dx / density, dy / density)) }
                }
                nodes.toList().forEach { node ->
                    if (node.type != ActionType.START) {
                        OutPortHandle(
                            anchor = inAnchor(node),
                            density = density,
                            color = Color.White,
                            label = null,
                            onStart = { wiring = Wiring(node.id, 0, inAnchor(node), reversed = true) },
                            onDrag = onDragEnd,
                            onEnd = onPortEnd,
                        )
                    }
                    val outs = if (node.type == ActionType.IF_PAGE_CHANGED)
                        listOf(0 to "是" to Color(0xFF66BB6A), 1 to "否" to Color(0xFFEF5350))
                    else listOf(0 to "" to Color.White)
                    outs.forEach { (pl, color) ->
                        val (port, label) = pl
                        OutPortHandle(
                            anchor = outAnchor(node, port),
                            density = density,
                            color = color,
                            label = label.ifEmpty { null },
                            onStart = { wiring = Wiring(node.id, port, outAnchor(node, port)) },
                            onDrag = onDragEnd,
                            onEnd = onPortEnd,
                        )
                    }
                }
            }
        }
    }

    // 节点编辑
    val ed = editingId
    if (ed != null) {
        val idx = nodes.indexOfFirst { it.id == ed }
        if (idx >= 0) {
            EditActionDialog(
                action = nodes[idx],
                onDismiss = { editingId = null },
                onConfirm = { updated -> nodes[idx] = updated; editingId = null },
                onRecaptureTemplate = {
                    pendingRecaptureId = ed
                    editingId = null
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onPickRegion = {
                    pendingRegionId = ed
                    editingId = null
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
            )
        }
    }

    // 长按节点：列表选择 复制 / 删除
    val ctx = contextId
    if (ctx != null) {
        val node = nodes.firstOrNull { it.id == ctx }
        AlertDialog(
            onDismissRequest = { contextId = null },
            title = { Text(node?.let { typeLabel(it.type) } ?: "节点") },
            text = {
                Column {
                    NodeActionRow("复制（不含连线）") {
                        if (node != null) {
                            pushUndo()
                            nodes += node.copy(
                                id = tempId--, index = nodes.size,
                                posX = node.posX + 40f, posY = node.posY + 40f,
                            )
                        }
                        contextId = null
                    }
                    NodeActionRow("删除节点及其连线", danger = true) {
                        pushUndo()
                        nodes.removeAll { it.id == ctx }
                        edges.removeAll { it.fromActionId == ctx || it.toActionId == ctx }
                        contextId = null
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { contextId = null }) { Text("取消") } },
        )
    }

    // 脚本信息（名称/速度）
    if (showMeta) {
        val s = script
        if (s != null) ScriptMetaDialog(s, onDismiss = { showMeta = false }, onConfirm = { script = it; showMeta = false })
    }

    // 裁剪/框选弹窗
    val src = cropSource
    if (src != null) {
        val regionId = pendingRegionId
        if (regionId != null) {
            RegionBandDialog(
                source = src,
                onDismiss = { cropSource = null; pendingRegionId = null },
                onConfirm = { rect ->
                    val i = nodes.indexOfFirst { it.id == regionId }
                    if (i >= 0) {
                        pushUndo()
                        nodes[i] = nodes[i].copy(
                            startX = rect.left, startY = rect.top, endX = rect.right, endY = rect.bottom,
                        )
                    }
                    cropSource = null; pendingRegionId = null
                },
            )
        } else {
            TemplateCropDialog(
                source = src,
                onDismiss = { cropSource = null; pendingRecaptureId = null },
                onConfirm = { bitmap ->
                    val path = com.wangchaozhi.wechatassistant.feature.match
                        .TemplateMatchUseCase.saveTemplate(context, bitmap)
                    val id = pendingRecaptureId
                    val i = if (id != null) nodes.indexOfFirst { it.id == id } else -1
                    if (i >= 0 && path != null) nodes[i] = nodes[i].copy(templatePath = path)
                    cropSource = null; pendingRecaptureId = null
                },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurve(a: Offset, b: Offset, color: Color) {
    val path = Path().apply {
        moveTo(a.x, a.y)
        val midY = (a.y + b.y) / 2
        cubicTo(a.x, midY, b.x, midY, b.x, b.y)
    }
    drawPath(path, color, style = Stroke(width = 4f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(
    node: Action,
    density: Float,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val color = when (node.type) {
        ActionType.START -> Color(0xFF1B5E20)
        ActionType.SNAPSHOT -> Color(0xFF4527A0)
        ActionType.IF_PAGE_CHANGED -> Color(0xFFE65100)
        else -> Color(0xFF37474F)
    }
    Box(
        Modifier
            .offset { IntOffset((node.posX * density).roundToInt(), (node.posY * density).roundToInt()) }
            .size(NODE_W.dp, NODE_H.dp)
            .background(color, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                ) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(typeLabel(node.type), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            if (node.type == ActionType.SNAPSHOT) {
                val region = node.endX > node.startX && node.endY > node.startY
                Text(
                    "「${node.aiPrompt?.ifBlank { null } ?: "默认"}」" + if (region) " ▣范围" else "",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (node.type == ActionType.IF_PAGE_CHANGED) {
                val a = node.aiPrompt?.ifBlank { null } ?: "默认"
                val b = node.templatePath?.ifBlank { null }
                Text(
                    if (b != null) "「$a」↔「$b」" else "「$a」↔ 实时",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 可拖拽的端口：触摸热区 44dp，独立布局（不被节点边界裁剪）。anchor 为图空间 dp。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OutPortHandle(
    anchor: Offset,
    density: Float,
    color: Color,
    label: String?,
    onStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onEnd: () -> Unit,
) {
    val half = (22f * density).roundToInt()
    Box(
        Modifier
            .offset { IntOffset((anchor.x * density).roundToInt() - half, (anchor.y * density).roundToInt() - half) }
            .size(44.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() },
                ) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (label != null) Text(label, color = Color.Black, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeActionRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (danger) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun AddNodeMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (ActionType) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            ActionType.TAP to "点击",
            ActionType.SWIPE to "滑动",
            ActionType.LONG_PRESS to "长按",
            ActionType.WAIT to "等待",
            ActionType.PASTE to "粘贴",
            ActionType.ENTER to "回车",
            ActionType.SCREENSHOT_AI to "AI 截图问答",
            ActionType.AI_TAP to "AI 找图点击",
            ActionType.IMAGE_MATCH to "找图点击（模板）",
            ActionType.SNAPSHOT to "快照（记基准）",
            ActionType.IF_PAGE_CHANGED to "条件：页面是否变化",
        ).forEach { (type, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onPick(type) })
        }
    }
}

@Composable
private fun ScriptMetaDialog(script: Script, onDismiss: () -> Unit, onConfirm: (Script) -> Unit) {
    var name by remember { mutableStateOf(script.name) }
    var speedText by remember { mutableStateOf(script.speed.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("脚本信息") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = speedText,
                    onValueChange = { raw -> speedText = raw.filter { it.isDigit() || it == '.' } },
                    label = { Text("速度倍率") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                Text("循环已由连线回指实现，循环次数设置在节点图中忽略。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(script.copy(name = name, speed = (speedText.toFloatOrNull() ?: script.speed).coerceIn(0.1f, 10f)))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
