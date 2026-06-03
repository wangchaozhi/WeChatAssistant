package com.wangchaozhi.wechatassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.service.ServiceBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// 节点固定尺寸（图空间 dp，scale=1 时）。端口锚点由此算术求得，免去逐卡测量。
private const val NODE_W = 160f
private const val NODE_H = 64f
private const val PORT_HIT = 48f   // 连线落点命中输入端口的 dp 半径（放宽便于吸附）

// reversed=false：从某节点输出口(anchorId,port)拖向目标输入口。
// reversed=true ：从某节点输入口(anchorId)拖向源节点输出口。
private data class Wiring(val anchorId: Long, val port: Int, val end: Offset, val reversed: Boolean = false)

/** 双出口节点：条件节点（变/有）和循环节点（环/完）。 */
private fun isTwoPort(t: ActionType): Boolean = t == ActionType.IF_PAGE_CHANGED ||
    t == ActionType.IF_IMAGE_EXISTS || t == ActionType.IF_TEXT_EXISTS || t == ActionType.LOOP

/** 节点的输出端口列表（双出口节点两个，STOP 无出口，其它一个）。 */
private fun outPorts(n: Action): List<Int> = when {
    n.type == ActionType.STOP -> emptyList()
    isTwoPort(n.type) -> listOf(0, 1)
    else -> listOf(0)
}

private fun inAnchor(n: Action) = Offset(n.posX + NODE_W / 2, n.posY)
private fun outAnchor(n: Action, port: Int): Offset = when {
    isTwoPort(n.type) && port == 0 -> Offset(n.posX + NODE_W * 0.3f, n.posY + NODE_H)
    isTwoPort(n.type) && port == 1 -> Offset(n.posX + NODE_W * 0.7f, n.posY + NODE_H)
    else -> Offset(n.posX + NODE_W / 2, n.posY + NODE_H)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GraphEditorScreen(
    scriptId: Long?,
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

    // 选图：pendingRecaptureId=给 IMAGE_MATCH 设模板；pendingLiveRegion=给 SNAPSHOT 现场框选屏幕范围。
    var pendingRecaptureId by remember { mutableStateOf<Long?>(null) }
    var pendingLiveRegionId by remember { mutableStateOf<Long?>(null) }
    var pendingLiveRegionRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingLiveTemplateId by remember { mutableStateOf<Long?>(null) }
    var pendingLiveTemplateRequestId by remember { mutableStateOf<Long?>(null) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val bmp = uri?.let { decodeBitmap(context, it) }
        if (bmp != null) cropSource = bmp else pendingRecaptureId = null
    }

    LaunchedEffect(scriptId) {
        if (scriptId == null) {
            val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
            script = Script(name = name)
            nodes.clear()
            nodes.add(0, Action(id = tempId--, scriptId = 0L, index = 0,
                type = ActionType.START, startX = 0f, startY = 0f, posX = 120f, posY = 120f))
            edges.clear()
            loaded = true
            return@LaunchedEffect
        }
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

    fun applyLiveSnapshotRegion(result: ServiceBus.SnapshotRegionPickResult) {
        if (result.requestId != pendingLiveRegionRequestId) return
        val id = pendingLiveRegionId ?: return
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) {
            pushUndo()
            val rect = result.rect
            nodes[i] = nodes[i].copy(
                startX = rect.left.toFloat(),
                startY = rect.top.toFloat(),
                endX = rect.right.toFloat(),
                endY = rect.bottom.toFloat(),
                templatePath = result.previewPath ?: nodes[i].templatePath,
            )
        }
        pendingLiveRegionId = null
        pendingLiveRegionRequestId = null
    }

    fun applyLiveTemplate(result: ServiceBus.TemplatePickResult) {
        if (result.requestId != pendingLiveTemplateRequestId) return
        val id = pendingLiveTemplateId ?: return
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) {
            pushUndo()
            val rect = result.rect
            nodes[i] = nodes[i].copy(
                startX = rect.left.toFloat(),
                startY = rect.top.toFloat(),
                endX = rect.right.toFloat(),
                endY = rect.bottom.toFloat(),
                templatePath = result.templatePath,
            )
        }
        pendingLiveTemplateId = null
        pendingLiveTemplateRequestId = null
    }

    LaunchedEffect(Unit) {
        ServiceBus.snapshotRegionPickResult.collect { applyLiveSnapshotRegion(it) }
    }

    LaunchedEffect(Unit) {
        ServiceBus.templatePickResult.collect { applyLiveTemplate(it) }
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
        nodes += newDefaultAction(scriptId ?: 0L, nodes.size, type).copy(
            id = tempId--, posX = g.x - NODE_W / 2, posY = g.y - NODE_H / 2,
        )
    }

    fun connect(fromId: Long, fromPort: Int, toId: Long) {
        if (fromId == toId) return
        // 一个出口可连多条线（运行时按顺序依次执行）；仅去重完全相同的边。
        if (edges.any { it.fromActionId == fromId && it.fromPort == fromPort && it.toActionId == toId }) return
        pushUndo()
        edges += Edge(scriptId = scriptId ?: 0L, fromActionId = fromId, toActionId = toId, fromPort = fromPort)
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
                        val selectAfterSave = scriptId == null
                        viewModel.saveGraph(s, nodes.toList(), edges.toList()) { savedId ->
                            if (selectAfterSave) viewModel.selectScript(savedId)
                            onBack()
                        }
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
                            isTwoPort(from.type) && e.fromPort == 0 -> Color(0xFF2E7D32)
                            isTwoPort(from.type) && e.fromPort == 1 -> Color(0xFFC62828)
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
                    val green = Color(0xFF66BB6A)
                    val red = Color(0xFFEF5350)
                    val outs = when (node.type) {
                        ActionType.IF_PAGE_CHANGED ->
                            listOf(0 to "是" to green, 1 to "否" to red)
                        ActionType.IF_IMAGE_EXISTS, ActionType.IF_TEXT_EXISTS ->
                            listOf(0 to "有" to green, 1 to "无" to red)
                        ActionType.LOOP ->
                            listOf(0 to "环" to green, 1 to "完" to red)
                        ActionType.STOP -> emptyList()
                        else -> listOf(0 to "" to Color.White)
                    }
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
                fetchModels = { viewModel.fetchModels(it) },
                cachedModels = { viewModel.cachedModels(it) },
                onRecaptureTemplate = {
                    if (!ServiceBus.overlayReady.value) {
                        android.widget.Toast.makeText(
                            context,
                            "请先启动悬浮面板，再现场框选图片模板",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@EditActionDialog
                    }
                    if (!ServiceBus.captureReady.value) {
                        android.widget.Toast.makeText(
                            context,
                            "请先启动截图服务，再现场框选图片模板",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@EditActionDialog
                    }
                    val requestId = System.currentTimeMillis()
                    pendingLiveTemplateId = ed
                    pendingLiveTemplateRequestId = requestId
                    editingId = null
                    ServiceBus.overlayCmd.tryEmit(
                        ServiceBus.OverlayCmd.RequestTemplatePick(requestId, script?.id?.takeIf { it > 0 })
                    )
                    android.widget.Toast.makeText(
                        context,
                        "切到目标页面后，点悬浮面板「框选」",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
                onPickRegion = {
                    if (!ServiceBus.overlayReady.value) {
                        android.widget.Toast.makeText(
                            context,
                            "请先启动悬浮面板，再现场框选快照范围",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@EditActionDialog
                    }
                    if (!ServiceBus.captureReady.value) {
                        android.widget.Toast.makeText(
                            context,
                            "请先启动截图服务，再现场框选快照范围",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@EditActionDialog
                    }
                    val requestId = System.currentTimeMillis()
                    pendingLiveRegionId = ed
                    pendingLiveRegionRequestId = requestId
                    editingId = null
                    ServiceBus.overlayCmd.tryEmit(
                        ServiceBus.OverlayCmd.RequestSnapshotRegionPick(requestId, script?.id?.takeIf { it > 0 })
                    )
                    android.widget.Toast.makeText(
                        context,
                        "切到目标页面后，点悬浮面板「框选」",
                        android.widget.Toast.LENGTH_LONG,
                    )
                        .show()
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

/** AI 节点卡片上显示的「供应商 · 模型」简标；未指定时显示「跟随全局」。 */
private fun aiModelLabel(node: Action): String {
    val provider = AiProvider.parse(node.aiProvider) ?: return "跟随全局"
    val model = node.aiModel?.ifBlank { null } ?: provider.models.firstOrNull().orEmpty()
    return "${provider.label} · ${model.substringAfterLast('/')}"
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
        ActionType.IF_PAGE_CHANGED,
        ActionType.IF_IMAGE_EXISTS,
        ActionType.IF_TEXT_EXISTS -> Color(0xFFE65100)
        ActionType.LOOP -> Color(0xFF00838F)
        ActionType.STOP -> Color(0xFFB71C1C)
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
            val alias = node.alias?.ifBlank { null }
            if (alias != null) {
                Text(alias, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(typeLabel(node.type), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
            } else {
                Text(typeLabel(node.type), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            if (node.type == ActionType.SNAPSHOT) {
                val region = node.endX > node.startX && node.endY > node.startY
                val snapPath = node.templatePath?.ifBlank { null }
                val snapBmp = remember(snapPath) {
                    snapPath?.let { p ->
                        runCatching { if (File(p).exists()) BitmapFactory.decodeFile(p) else null }.getOrNull()
                    }
                }
                Text(
                    "「${node.aiPrompt?.ifBlank { null } ?: "默认"}」" +
                        if (snapBmp != null) " ▣基准图" else if (region) " ▣范围" else "",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (snapBmp != null) {
                    Image(
                        bitmap = snapBmp.asImageBitmap(),
                        contentDescription = "快照基准图",
                        modifier = Modifier.height(22.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            if (node.type == ActionType.IF_PAGE_CHANGED) {
                val a = node.aiPrompt?.ifBlank { null } ?: "默认"
                val b = node.templatePath?.ifBlank { null }
                Text(
                    (if (b != null) "「$a」↔「$b」" else "「$a」↔ 实时") +
                        " · 阈值${"%.2f".format(node.matchThreshold)}",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (node.type == ActionType.SCREENSHOT_AI || node.type == ActionType.AI_TAP) {
                Text(
                    aiModelLabel(node),
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (node.type == ActionType.IF_IMAGE_EXISTS) {
                Text(
                    if (node.templatePath != null) "▣模板 · 阈值${"%.2f".format(node.matchThreshold)}" else "⚠ 未设模板",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (node.type == ActionType.IF_TEXT_EXISTS) {
                Text(
                    "「${node.aiPrompt?.ifBlank { null } ?: "?"}」",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (node.type == ActionType.LOOP) {
                Text(
                    "× ${node.retryCount}",
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

// 加节点菜单：按类别二级折叠，默认只显示分组标题，点开才列出该组节点，减少高度与误触。
private val NODE_GROUPS: List<Pair<String, List<Pair<ActionType, String>>>> = listOf(
    "基础操作" to listOf(
        ActionType.TAP to "点击",
        ActionType.SWIPE to "滑动",
        ActionType.LONG_PRESS to "长按",
        ActionType.WAIT to "等待",
        ActionType.PASTE to "粘贴",
        ActionType.ENTER to "回车",
    ),
    "找图 / AI" to listOf(
        ActionType.SCREENSHOT_AI to "AI 截图问答",
        ActionType.AI_TAP to "AI 找图点击",
        ActionType.IMAGE_MATCH to "选图点击",
    ),
    "快照 / 条件" to listOf(
        ActionType.SNAPSHOT to "快照（记基准）",
        ActionType.IF_PAGE_CHANGED to "检测快照变化",
        ActionType.IF_IMAGE_EXISTS to "图像是否存在",
        ActionType.IF_TEXT_EXISTS to "文字是否存在",
    ),
    "流程控制" to listOf(
        ActionType.LOOP to "循环 N 次",
        ActionType.STOP to "停止",
    ),
)

@Composable
private fun AddNodeMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (ActionType) -> Unit) {
    // 每次重新打开菜单都回到「全部折叠」。
    var openGroup by remember(expanded) { mutableStateOf<String?>(null) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        NODE_GROUPS.forEach { (title, items) ->
            val isOpen = openGroup == title
            DropdownMenuItem(
                text = {
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold)
                },
                trailingIcon = {
                    Icon(
                        if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                    )
                },
                onClick = { openGroup = if (isOpen) null else title },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(38.dp),
            )
            if (isOpen) {
                items.forEach { (type, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { onPick(type) },
                        contentPadding = PaddingValues(start = 28.dp, end = 12.dp),
                        modifier = Modifier.height(34.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptMetaDialog(script: Script, onDismiss: () -> Unit, onConfirm: (Script) -> Unit) {
    var name by remember { mutableStateOf(script.name) }
    var speedText by remember { mutableStateOf(script.speed.toString()) }
    var loopText by remember { mutableStateOf(script.loopCount.toString()) }
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
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = loopText,
                    onValueChange = { raw -> loopText = raw.filter { it.isDigit() } },
                    label = { Text("整体循环次数") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                Text("整体循环次数：从入口把整张图重跑指定遍数。图内连线回指形成的内部循环不受影响。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(script.copy(
                    name = name,
                    speed = (speedText.toFloatOrNull() ?: script.speed).coerceIn(0.1f, 10f),
                    loopCount = (loopText.toIntOrNull() ?: script.loopCount).coerceAtLeast(1),
                ))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
