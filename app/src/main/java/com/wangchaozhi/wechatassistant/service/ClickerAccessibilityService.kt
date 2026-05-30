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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ClickerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

    private companion object {
        // 图遍历硬上限，防止无条件节点把关的环导致死循环。
        const val MAX_GRAPH_STEPS = 100_000
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
        val baselines = HashMap<String, Int>()   // 快照名称 -> 指纹
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
                    val fp = pageFingerprint(region)
                    baselines[key] = fp
                    App.from(this@ClickerAccessibilityService).appendLog("SNAPSHOT[$key]=$fp region=$region")
                    0
                }
                ActionType.IF_PAGE_CHANGED -> {
                    val keyA = node.aiPrompt?.ifBlank { null } ?: "默认"
                    val keyB = node.templatePath?.ifBlank { null }   // 复用字段存「快照B」名称
                    val changed = if (keyB != null) {
                        // 比较两个具名快照：都拍过且不同 => 变了。
                        val a = baselines[keyA]
                        val b = baselines[keyB]
                        val c = a != null && b != null && a != b
                        App.from(this@ClickerAccessibilityService)
                            .appendLog("IF 快照[$keyA]=$a vs 快照[$keyB]=$b changed=$c")
                        c
                    } else {
                        // 单快照：当前实时页面 vs 快照A。复用快照A 的范围，保证比的是同一区域。
                        val base = baselines[keyA]
                        val now = pageFingerprint(snapRegions[keyA])
                        val c = base != null && now != base
                        App.from(this@ClickerAccessibilityService)
                            .appendLog("IF vs 快照[$keyA] changed=$c (now=$now base=$base)")
                        c
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

    /** 从快照节点的 startX/startY/endX/endY 取范围矩形；无效则整页（null）。 */
    private fun regionOf(node: Action): android.graphics.Rect? =
        if (node.endX > node.startX && node.endY > node.startY)
            android.graphics.Rect(node.startX.toInt(), node.startY.toInt(), node.endX.toInt(), node.endY.toInt())
        else null

    /**
     * 当前活动窗口可见节点的指纹：拼接 text/className/bounds 后取 hash。
     * [region] 非空时只统计与该屏幕矩形相交的节点（用于范围快照）。
     */
    private suspend fun pageFingerprint(region: android.graphics.Rect? = null): Int =
        withContext(Dispatchers.Main.immediate) {
            val root = rootInActiveWindow ?: return@withContext 0
            val sb = StringBuilder()
            val rect = android.graphics.Rect()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser) {
                    node.getBoundsInScreen(rect)
                    if (region == null || android.graphics.Rect.intersects(region, rect)) {
                        sb.append(node.className).append('|')
                            .append(node.text ?: "").append('|')
                            .append(rect.left).append(',').append(rect.top).append(',')
                            .append(rect.right).append(',').append(rect.bottom).append(';')
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
