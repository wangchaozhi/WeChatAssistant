package com.wangchaozhi.wechatassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptWithGraph
import com.wangchaozhi.wechatassistant.feature.ai.AiTapUseCase
import com.wangchaozhi.wechatassistant.feature.ai.ScreenshotAiUseCase
import com.wangchaozhi.wechatassistant.feature.match.RegionDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ClickerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

    private companion object {
        // 图遍历硬上限，防止无条件节点把关的环导致死循环。
        const val MAX_GRAPH_STEPS = 100_000
        // OpenCV 区域相关度阈值：相关度低于此值即判「变了」（1.0=完全一致）。
        const val SIMILARITY_THRESHOLD = 0.95
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceBus.accessibilityReady.value = true
        scope.launch {
            ServiceBus.playerCmd.collectLatest { cmd ->
                when (cmd) {
                    is ServiceBus.PlayerCmd.Play -> startPlay(cmd.scriptId)
                    ServiceBus.PlayerCmd.Stop -> stopPlay()
                }
            }
        }
        scope.launch {
            ServiceBus.pasteCmd.collect {
                val ok = runCatching {
                    withContext(Dispatchers.Main.immediate) { pasteIntoFocused() }
                }.getOrDefault(false)
                ServiceBus.pasteResult.emit(ok)
            }
        }
        scope.launch {
            ServiceBus.enterCmd.collect {
                val ok = runCatching {
                    withContext(Dispatchers.Main.immediate) { enterIntoFocused() }
                }.getOrDefault(false)
                ServiceBus.enterResult.emit(ok)
            }
        }
    }

    private fun pasteIntoFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        return focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        ServiceBus.accessibilityReady.value = false
        scope.cancel()
    }

    private fun startPlay(scriptId: Long) {
        playJob?.cancel()
        playJob = scope.launch {
            val app = App.from(this@ClickerAccessibilityService)
            val data = app.scriptRepo.loadGraph(scriptId) ?: return@launch
            try {
                runGraph(data, app.screenshotAi, app.aiTap, scriptId)
            } finally {
                ServiceBus.playerState.value = ServiceBus.PlayerState.Idle
            }
        }
    }

    private fun stopPlay() {
        playJob?.cancel()
        playJob = null
        ServiceBus.playerState.value = ServiceBus.PlayerState.Idle
    }

    /**
     * 从 START 节点出发，沿边深度优先遍历执行图：
     * - SNAPSHOT 节点把当前页面指纹存入以「名称」(aiPrompt) 为键的具名寄存器；
     * - IF_PAGE_CHANGED 节点把当前指纹与「指定名称」的快照瞬时比较，变了走出口 0、没变走出口 1；
     *   指定的快照若尚未拍过，视为「没变」走出口 1；
     * - 其它节点执行后走出口 0。
     * 一个出口可连多条边：按连线顺序依次深度优先执行（先把第一条分支整支跑完，再下一条）。
     * 循环由回指的边表达；用显式栈而非递归避免爆栈。MAX_GRAPH_STEPS 防无闸死循环。
     */
    private suspend fun runGraph(
        data: ScriptWithGraph,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
    ) {
        val script = data.script
        val nodes = data.actions.associateBy { it.id }
        if (nodes.isEmpty()) return
        // fromId -> (port -> [toId...])，保留连线顺序，按缺失节点过滤悬空边。
        val out = HashMap<Long, HashMap<Int, MutableList<Long>>>()
        data.edges.forEach { e ->
            if (nodes.containsKey(e.fromActionId) && nodes.containsKey(e.toActionId)) {
                out.getOrPut(e.fromActionId) { HashMap() }
                    .getOrPut(e.fromPort) { ArrayList() }
                    .add(e.toActionId)
            }
        }
        val startId = data.actions.firstOrNull { it.type == ActionType.START }?.id
            ?: data.actions.first().id
        val baselines = HashMap<String, Int>()                  // 快照名称 -> 文字指纹（无范围/无截图时）
        val regionBmps = HashMap<String, android.graphics.Bitmap>()  // 快照名称 -> 区域截图（OpenCV 比较）
        // 快照名称 -> 范围矩形（取该名快照节点上设定的范围）。条件单快照比较时复用同一范围取实时页面。
        val snapRegions = HashMap<String, android.graphics.Rect?>()
        data.actions.forEach { a ->
            if (a.type == ActionType.SNAPSHOT) {
                snapRegions[a.aiPrompt?.ifBlank { null } ?: "默认"] = regionOf(a)
            }
        }
        val stack = ArrayDeque<Long>()
        stack.addLast(startId)
        var visited = 0
        var steps = 0
        while (scope.isActive && stack.isNotEmpty()) {
            val current = stack.removeLast()
            val node = nodes[current] ?: continue
            ServiceBus.playerState.value =
                ServiceBus.PlayerState.Playing(script, visited++, nodes.size)
            val port = when (node.type) {
                ActionType.START -> 0
                ActionType.SNAPSHOT -> {
                    val key = node.aiPrompt?.ifBlank { null } ?: "默认"
                    val region = regionOf(node)
                    // 有范围且截图就绪 → 存区域截图（OpenCV 比较）；否则存文字指纹。
                    val bmp = if (region != null && ServiceBus.captureReady.value)
                        regionCrop(region) else null
                    if (bmp != null) {
                        regionBmps.remove(key)?.recycle()
                        regionBmps[key] = bmp
                        baselines.remove(key)
                        App.from(this@ClickerAccessibilityService)
                            .appendLog("SNAPSHOT[$key]=img ${bmp.width}x${bmp.height} region=$region")
                    } else {
                        val fp = pageFingerprint(region)
                        baselines[key] = fp
                        regionBmps.remove(key)?.recycle()
                        App.from(this@ClickerAccessibilityService).appendLog("SNAPSHOT[$key]=$fp(text) region=$region")
                    }
                    0
                }
                ActionType.IF_PAGE_CHANGED -> {
                    val keyA = node.aiPrompt?.ifBlank { null } ?: "默认"
                    val keyB = node.templatePath?.ifBlank { null }   // 复用字段存「快照B」名称
                    val changed = when {
                        // 双快照 + 区域图：OpenCV 相关度，低于阈值即变。
                        keyB != null && regionBmps[keyA] != null && regionBmps[keyB] != null -> {
                            val sim = RegionDiff.similarity(regionBmps[keyA]!!, regionBmps[keyB]!!)
                            val c = sim != null && sim < SIMILARITY_THRESHOLD
                            App.from(this@ClickerAccessibilityService)
                                .appendLog("IF OpenCV 快照[$keyA]↔[$keyB] sim=${"%.3f".format(sim ?: -1.0)} changed=$c")
                            c
                        }
                        // 双快照 + 文字指纹。
                        keyB != null -> {
                            val a = baselines[keyA]; val b = baselines[keyB]
                            val c = a != null && b != null && a != b
                            App.from(this@ClickerAccessibilityService)
                                .appendLog("IF 快照[$keyA]=$a vs 快照[$keyB]=$b changed=$c")
                            c
                        }
                        // 单快照 + 区域图：实时区域 vs 快照A。
                        regionBmps[keyA] != null -> {
                            val now = regionCrop(snapRegions[keyA])
                            val sim = if (now != null) RegionDiff.similarity(regionBmps[keyA]!!, now) else null
                            now?.recycle()
                            val c = sim != null && sim < SIMILARITY_THRESHOLD
                            App.from(this@ClickerAccessibilityService)
                                .appendLog("IF OpenCV vs 快照[$keyA] sim=${"%.3f".format(sim ?: -1.0)} changed=$c")
                            c
                        }
                        // 单快照 + 文字指纹。
                        else -> {
                            val base = baselines[keyA]
                            val now = pageFingerprint(snapRegions[keyA])
                            val c = base != null && now != base
                            App.from(this@ClickerAccessibilityService)
                                .appendLog("IF vs 快照[$keyA] changed=$c (now=$now base=$base)")
                            c
                        }
                    }
                    if (changed) 0 else 1
                }
                else -> {
                    if (node.delayBeforeMs > 0) {
                        delay((node.delayBeforeMs / script.speed).toLong().coerceAtLeast(0))
                    }
                    execute(node, ai, tap, scriptId)
                    0
                }
            }
            // 深度优先：第一条连线最先处理 => 反序压栈。
            val targets = out[current]?.get(port).orEmpty()
            for (i in targets.indices.reversed()) stack.addLast(targets[i])
            if (++steps > MAX_GRAPH_STEPS) {
                App.from(this@ClickerAccessibilityService).appendLog("runGraph: 步数超上限，停止")
                break
            }
        }
    }

    /** 截屏并裁出区域 Bitmap（按屏幕像素映射到截图分辨率）。截图不可用返回 null。 */
    private suspend fun regionCrop(region: android.graphics.Rect?): android.graphics.Bitmap? {
        val bmp = withTimeoutOrNull(3_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }
        } ?: return null
        return withContext(Dispatchers.Default) {
            val dm = resources.displayMetrics
            val sx = bmp.width.toFloat() / dm.widthPixels.coerceAtLeast(1)
            val sy = bmp.height.toFloat() / dm.heightPixels.coerceAtLeast(1)
            val l = ((region?.left ?: 0) * sx).toInt().coerceIn(0, bmp.width - 1)
            val t = ((region?.top ?: 0) * sy).toInt().coerceIn(0, bmp.height - 1)
            val r = ((region?.right ?: bmp.width) * sx).toInt().coerceIn(l + 1, bmp.width)
            val b = ((region?.bottom ?: bmp.height) * sy).toInt().coerceIn(t + 1, bmp.height)
            runCatching { android.graphics.Bitmap.createBitmap(bmp, l, t, r - l, b - t) }.getOrNull()
        }
    }

    /** 从快照节点的 startX/startY/endX/endY 取范围矩形；无效则整页（null）。 */
    private fun regionOf(node: Action): android.graphics.Rect? =
        if (node.endX > node.startX && node.endY > node.startY)
            android.graphics.Rect(node.startX.toInt(), node.startY.toInt(), node.endX.toInt(), node.endY.toInt())
        else null

    /**
     * 当前活动窗口的「文字内容指纹」：只统计有文字或 contentDescription 的可见节点
     * （自动跳过空的整屏容器），拼接 className + text + contentDescription 取 hash。
     * 不含边界坐标——忽略位移/滚动/动画/重渲染带来的纯视觉变化，只在意文字内容是否变。
     * [region] 非空时只统计「节点中心落在该矩形内」的节点：既纳入条带里的列表项，
     * 又排除中心在别处的整屏容器。
     */
    private suspend fun pageFingerprint(region: android.graphics.Rect? = null): Int =
        withContext(Dispatchers.Main.immediate) {
            val root = rootInActiveWindow ?: return@withContext 0
            val sb = StringBuilder()
            val rect = android.graphics.Rect()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser) {
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    if (text.isNotBlank() || desc.isNotBlank()) {
                        node.getBoundsInScreen(rect)
                        val inRegion = region == null ||
                            region.contains((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
                        if (inRegion) {
                            sb.append(node.className).append('|')
                                .append(text).append('|')
                                .append(desc).append(';')
                        }
                    }
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(root)
            sb.toString().hashCode()
        }

    private suspend fun execute(
        action: Action,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
    ) {
        when (action.type) {
            ActionType.TAP, ActionType.LONG_PRESS, ActionType.SWIPE -> performGesture(action)
            ActionType.WAIT -> delay(action.durationMs)
            ActionType.SCREENSHOT_AI -> {
                val prompt = action.aiPrompt
                    ?: App.from(this@ClickerAccessibilityService).settingsRepo.defaultPrompt
                ai.run(prompt, scriptId).onFailure { /* swallow */ }
            }
            ActionType.AI_TAP -> {
                val target = action.aiPrompt ?: return
                val result = tap.locate(target, scriptId)
                val point = result.getOrNull()
                App.from(this@ClickerAccessibilityService).appendLog(
                    "AITAP exec point=$point err=${result.exceptionOrNull()?.message}"
                )
                if (point == null) return
                performGesture(
                    action.copy(
                        type = ActionType.TAP,
                        startX = point.x,
                        startY = point.y,
                        endX = point.x,
                        endY = point.y,
                        durationMs = action.durationMs.coerceAtLeast(80L),
                    )
                )
            }
            ActionType.IMAGE_MATCH -> {
                val path = action.templatePath ?: return
                val match = App.from(this@ClickerAccessibilityService)
                    .templateMatch.locate(path, action.matchThreshold)
                    .getOrNull() ?: return
                performGesture(
                    action.copy(
                        type = ActionType.TAP,
                        startX = match.point.x,
                        startY = match.point.y,
                        endX = match.point.x,
                        endY = match.point.y,
                        durationMs = action.durationMs.coerceAtLeast(80L),
                    )
                )
            }
            ActionType.PASTE -> {
                withContext(Dispatchers.Main.immediate) { pasteIntoFocused() }
            }
            ActionType.ENTER -> {
                withContext(Dispatchers.Main.immediate) { enterIntoFocused() }
            }
            // 控制流节点，由 runScript / runGraph 直接处理；正常不会走到这里。
            ActionType.WAIT_PAGE_CHANGE,
            ActionType.START,
            ActionType.SNAPSHOT,
            ActionType.IF_PAGE_CHANGED -> Unit
        }
    }

    private fun enterIntoFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        // 多行输入框：回车 = 在光标处插入换行；单行：先尝试 IME 提交动作（发送/搜索/下一步）。
        if (focus.isMultiLine) {
            if (insertNewlineAtCursor(focus)) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeEnterId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            if (focus.performAction(imeEnterId)) return true
        }
        return insertNewlineAtCursor(focus)
    }

    /** 在当前光标/选区处插入换行，并把光标移到换行之后；无法读到选区时退化为末尾追加。 */
    private fun insertNewlineAtCursor(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val (newText, cursor) = if (start in 0..text.length && end in 0..text.length) {
            val s = minOf(start, end)
            val e = maxOf(start, end)
            (text.substring(0, s) + "\n" + text.substring(e)) to (s + 1)
        } else {
            (text + "\n") to (text.length + 1)
        }
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    private suspend fun performGesture(a: Action) = withContext(Dispatchers.Main.immediate) {
        val maxX = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val maxY = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val startX = a.startX.coerceIn(0f, maxX - 1f)
        val startY = a.startY.coerceIn(0f, maxY - 1f)
        val endX = a.endX.coerceIn(0f, maxX - 1f)
        val endY = a.endY.coerceIn(0f, maxY - 1f)
        val path = Path().apply {
            moveTo(startX, startY)
            if (a.type == ActionType.SWIPE) lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, a.durationMs.coerceAtLeast(1)))
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onCancelled(d: GestureDescription) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }, handler)
            if (!ok && cont.isActive) cont.resume(Unit)
        }
    }
}
