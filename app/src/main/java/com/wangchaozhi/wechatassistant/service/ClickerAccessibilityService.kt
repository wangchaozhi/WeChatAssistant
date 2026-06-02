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
        scope.launch {
            // 「边录边放」：把悬浮层刚录到的手势立即投放给真实 App，让界面前进。
            ServiceBus.recordInject.collect { raw ->
                runCatching { performGesture(raw.toGestureAction()) }
                ServiceBus.recordInjectDone.emit(Unit)
            }
        }
    }

    /** 由原始触摸推断手势类型，构造一个可被 [performGesture] 执行的临时 Action。 */
    private fun ServiceBus.RawTouch.toGestureAction(): Action {
        val dx = endX - startX
        val dy = endY - startY
        val type = when {
            kotlin.math.hypot(dx, dy) > 20f -> ActionType.SWIPE
            durationMs > 500 -> ActionType.LONG_PRESS
            else -> ActionType.TAP
        }
        return Action(
            scriptId = 0,
            index = 0,
            type = type,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs.coerceAtLeast(1L),
        )
    }

    private fun pasteIntoFocused(): Boolean {
        // 1) 首选：无障碍输入法接口直接 commitText（Android 14+）。走的是和真实键盘相同的
        //    InputConnection 通道，由输入框自己接收，不依赖无障碍节点——微信等剥掉节点信息的
        //    输入框也能写进去。要写的文本取自我们自己内存里的 AI 答案（后台读不到剪贴板）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val text = pasteText()
            if (!text.isNullOrEmpty() && commitViaIme(text)) return true
        }
        // 2) 兜底：节点动作。ACTION_PASTE 用系统剪贴板，SET_TEXT 直接写文本。
        val node = findFocusedEditable()
        if (node == null) {
            App.from(this).appendLog("PASTE: 未找到输入焦点/可编辑节点")
            return false
        }
        App.from(this).appendLog(
            "PASTE target=${node.className} editable=${node.isEditable} focused=${node.isFocused}"
        )
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return pasteBySetText(node)
    }

    /** 要粘贴的文本：优先内存里的 AI 答案，其次尝试剪贴板（后台多半读不到）。 */
    private fun pasteText(): String? =
        ServiceBus.lastAiAnswer.value?.takeIf { it.isNotEmpty() } ?: clipboardText()

    /** 通过无障碍输入法接口把文本 commit 到当前获焦的输入框。Android 14+。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun commitViaIme(text: String): Boolean {
        val ic = runCatching { inputMethod?.currentInputConnection }.getOrNull() ?: run {
            App.from(this).appendLog("PASTE IME: InputConnection 不可用（无获焦输入框）")
            return false
        }
        // AccessibilityInputConnection.commitText 返回 void，仅三参（含 TextAttribute）；不抛异常即成功。
        return try {
            ic.commitText(text, 1, null)
            App.from(this).appendLog("PASTE via IME commitText len=${text.length}")
            true
        } catch (t: Throwable) {
            App.from(this).appendLog("PASTE IME commitText 异常: ${t.message}")
            false
        }
    }

    private fun clipboardText(): String? {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        return clip?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    /** 跨所有窗口找输入框：优先「真正可编辑」的输入焦点，其次任意可见可编辑，最后退化为焦点占位节点。 */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            for (w in windows) w.root?.let { add(it) }
        }
        App.from(this).appendLog("PASTE windows=${windows.size} roots=${roots.size}")
        // 1) 真正可编辑的输入焦点
        for (r in roots) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        // 2) 任意可见可编辑节点
        for (r in roots) findEditable(r)?.let { return it }
        // 3) 兜底：输入焦点占位节点（给 ACTION_PASTE 一次机会，应对个别不标 editable 的输入框）
        for (r in roots) r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return null
    }

    /** ACTION_PASTE 不被支持时的兜底：读剪贴板，把文本追加到光标处（无选区则末尾）。 */
    private fun pasteBySetText(node: AccessibilityNodeInfo): Boolean {
        val text = pasteText()
        if (text.isNullOrEmpty()) {
            App.from(this).appendLog("PASTE: 无可粘贴文本，SET_TEXT 兜底失败")
            return false
        }
        val existing = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val (newText, cursor) = if (start in 0..existing.length && end in 0..existing.length) {
            val s = minOf(start, end); val e = maxOf(start, end)
            (existing.substring(0, s) + text + existing.substring(e)) to (s + text.length)
        } else {
            (existing + text) to (existing.length + text.length)
        }
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            App.from(this).appendLog("PASTE: SET_TEXT 也失败")
            return false
        }
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
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
                clearDebugBitmaps()
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

    private fun clearDebugBitmaps() {
        runCatching {
            filesDir.listFiles { file -> file.isFile && file.name.startsWith("dbg_") && file.name.endsWith(".png") }
                ?.forEach { it.delete() }
        }
    }

    /**
     * 从 START 节点出发，沿边深度优先遍历执行图：
     * - SNAPSHOT 节点把当前页面截图存入以「名称」(aiPrompt) 为键的具名寄存器；
     * - IF_PAGE_CHANGED 节点检测「指定名称」快照的图像变化，变了走出口 0、没变走出口 1；
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
        val regionBmps = HashMap<String, android.graphics.Bitmap>()  // 快照名称 -> 区域截图（OpenCV 比较）
        // 快照名称 -> 范围矩形（取该名快照节点上设定的范围）。条件单快照比较时复用同一范围取实时页面。
        val snapRegions = HashMap<String, android.graphics.Rect?>()
        var debugImageSeq = 0
        data.actions.forEach { a ->
            if (a.type == ActionType.SNAPSHOT) {
                snapRegions[a.aiPrompt?.ifBlank { null } ?: "默认"] = regionOf(a)
            }
        }
        // 整体循环次数：从 START 起把整张图重跑 loopCount 遍（<1 视为 1）。
        // 每遍独立重置栈/进度/步数；图内回指边形成的内部循环不受影响。
        val loops = script.loopCount.coerceAtLeast(1)
        try {
        run@ for (loop in 0 until loops) {
        if (!scope.isActive) break@run
        val stack = ArrayDeque<Long>()
        stack.addLast(startId)
        // LOOP 节点的计数器：nodeId -> 已进入循环体次数。每一遍整图执行独立重置。
        val loopCounters = HashMap<Long, Int>()
        var visited = 0
        var steps = 0
        while (scope.isActive && stack.isNotEmpty()) {
            val current = stack.removeLast()
            val node = nodes[current] ?: continue
            ServiceBus.playerState.value =
                ServiceBus.PlayerState.Playing(script, visited++, nodes.size)
            // 「执行前等待」对所有节点生效：快照/条件前可借此等页面加载稳定再截图/比较。
            if (node.delayBeforeMs > 0) {
                delay((node.delayBeforeMs / script.speed).toLong().coerceAtLeast(0))
            }
            val port = when (node.type) {
                ActionType.START -> 0
                ActionType.SNAPSHOT -> {
                    val key = node.aiPrompt?.ifBlank { null } ?: "默认"
                    val region = regionOf(node)
                    // 截图就绪时存截图做 OpenCV 比较；region=null 表示整屏。
                    val bmp = if (ServiceBus.captureReady.value) regionCrop(region) else null
                    if (bmp != null) {
                        regionBmps.remove(key)?.recycle()
                        regionBmps[key] = bmp
                        // 调试：把区域图存盘，便于 adb 拉出来肉眼对比。
                        saveDebugBitmap("dbg_snap_$key.png", bmp)
                        App.from(this@ClickerAccessibilityService)
                            .appendLog("SNAPSHOT[$key]=img ${bmp.width}x${bmp.height} region=$region")
                    } else {
                        regionBmps.remove(key)?.recycle()
                        App.from(this@ClickerAccessibilityService)
                            .appendLog("SNAPSHOT[$key] 失败：截图服务未就绪或截图超时 region=$region")
                    }
                    0
                }
                ActionType.IF_PAGE_CHANGED -> {
                    val changed = detectPageChanged(
                        node = node,
                        regionBmps = regionBmps,
                        snapRegions = snapRegions,
                        nextDebugSeq = { ++debugImageSeq },
                    )
                    if (changed) 0 else 1
                }
                ActionType.IF_IMAGE_EXISTS -> {
                    val path = node.templatePath?.ifBlank { null }
                    val found = path != null && App.from(this@ClickerAccessibilityService)
                        .templateMatch.locate(path, node.matchThreshold).isSuccess
                    App.from(this@ClickerAccessibilityService)
                        .appendLog("IF_IMAGE_EXISTS thr=${node.matchThreshold} found=$found")
                    if (found) 0 else 1
                }
                ActionType.IF_TEXT_EXISTS -> {
                    val target = node.aiPrompt?.ifBlank { null }
                    val found = target != null && screenHasText(target)
                    App.from(this@ClickerAccessibilityService)
                        .appendLog("IF_TEXT_EXISTS [$target] found=$found")
                    if (found) 0 else 1
                }
                ActionType.LOOP -> {
                    val n = node.retryCount.coerceAtLeast(1)
                    val c = loopCounters[node.id] ?: 0
                    if (c < n) {
                        loopCounters[node.id] = c + 1
                        0   // 继续：进入循环体（第 ${c + 1}/$n 次）
                    } else {
                        loopCounters[node.id] = 0   // 复位，便于该节点被再次进入时重新计数
                        1   // 到次数：往下走
                    }
                }
                ActionType.STOP -> {
                    App.from(this@ClickerAccessibilityService).appendLog("STOP 节点：终止整图执行")
                    break@run
                }
                else -> {
                    execute(node, ai, tap, scriptId)
                    0
                }
            }
            // 深度优先：第一条连线最先处理 => 反序压栈。
            val targets = out[current]?.get(port).orEmpty()
            for (i in targets.indices.reversed()) stack.addLast(targets[i])
            if (++steps > MAX_GRAPH_STEPS) {
                App.from(this@ClickerAccessibilityService).appendLog("runGraph: 步数超上限，停止")
                break@run
            }
        }
        }
        } finally {
            // 统一收尾：无论正常结束、STOP 跳出还是步数超限，都关掉可能仍开着的截图流。
            // detectPageChanged 不再自行 StopStream，连续 IF 节点复用同一路流、不重复预热。
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StopStream)
        }
    }

    private suspend fun detectPageChanged(
        node: Action,
        regionBmps: Map<String, android.graphics.Bitmap>,
        snapRegions: Map<String, android.graphics.Rect?>,
        nextDebugSeq: () -> Int,
    ): Boolean {
        val keyA = node.aiPrompt?.ifBlank { null } ?: "默认"
        val keyB = node.templatePath?.ifBlank { null }   // 复用字段存「快照B」名称
        val threshold = node.matchThreshold.coerceIn(0.1f, 1f)
        val timeoutMs = node.durationMs.coerceAtLeast(0L)
        var attempt = 0

        // 单次瞬时比较：双快照 / 时长<=0 走这里，截取实时页面一次即出结果。
        suspend fun once(): Boolean {
            attempt += 1
            return when {
                // 双快照图片：OpenCV 相关度，低于阈值即变。
                keyB != null && regionBmps[keyA] != null && regionBmps[keyB] != null -> {
                    val match = RegionDiff.compare(regionBmps[keyA]!!, regionBmps[keyB]!!)
                    val c = match != null && match.score < threshold
                    App.from(this@ClickerAccessibilityService)
                        .appendLog(
                            "IF OpenCV 快照[$keyA]↔[$keyB] " +
                                "attempt=$attempt sim=${"%.3f".format(match?.score ?: -1.0)} " +
                                "shift=(${match?.offsetX ?: 0},${match?.offsetY ?: 0}) " +
                                "thr=$threshold changed=$c"
                        )
                    c
                }
                keyB != null -> {
                    App.from(this@ClickerAccessibilityService)
                        .appendLog("IF 快照[$keyA]↔[$keyB] 缺少图片基准，changed=false")
                    false
                }
                regionBmps[keyA] != null -> {
                    val now = regionCrop(snapRegions[keyA])
                    val nowDebugName = if (now != null) "dbg_now_${keyA}_${nextDebugSeq()}.png" else null
                    if (now != null && nowDebugName != null) saveDebugBitmap(nowDebugName, now)
                    val match = if (now != null) RegionDiff.compare(regionBmps[keyA]!!, now) else null
                    now?.recycle()
                    val c = match != null && match.score < threshold
                    App.from(this@ClickerAccessibilityService)
                        .appendLog(
                            "IF OpenCV vs 快照[$keyA] " +
                                "attempt=$attempt sim=${"%.3f".format(match?.score ?: -1.0)} " +
                                "shift=(${match?.offsetX ?: 0},${match?.offsetY ?: 0}) " +
                                "thr=$threshold changed=$c now=$nowDebugName"
                        )
                    c
                }
                else -> {
                    App.from(this@ClickerAccessibilityService)
                        .appendLog("IF vs 快照[$keyA] 缺少图片基准，changed=false")
                    false
                }
            }
        }

        if (timeoutMs <= 0L || keyB != null) return once()

        // 单快照 + 时长>0：事件驱动地跟随截图流，每来一帧就和基准比一次，一旦变化立即返回。
        // 不再固定 100ms 轮询 sleep——检出延迟压到「一帧 + 比较」级别。
        // streamFrame 是 conflated StateFlow，慢消费者只取最新帧，天然限流、不会积压。
        val baseline = regionBmps[keyA]
        if (baseline == null) {
            App.from(this@ClickerAccessibilityService)
                .appendLog("IF vs 快照[$keyA] 缺少图片基准，changed=false")
            return false
        }
        val region = snapRegions[keyA]
        // 幂等开流：已在流式则 startFrameStream 直接返回，连续 IF 复用同一路流。
        // 此处不 StopStream——由 runGraph 统一收尾，省掉每个节点的重复预热。
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StartStream)
        return withTimeoutOrNull(timeoutMs) {
            var lastId = -1L
            ServiceBus.streamFrame.first { frame ->
                if (frame == null || frame.id == lastId) return@first false
                lastId = frame.id
                attempt += 1
                val now = cropBitmap(frame.bitmap, region)
                val match = if (now != null) RegionDiff.compare(baseline, now) else null
                now?.recycle()
                val changed = match != null && match.score < threshold
                App.from(this@ClickerAccessibilityService).appendLog(
                    "IF OpenCV vs 快照[$keyA](stream) attempt=$attempt " +
                        "sim=${"%.3f".format(match?.score ?: -1.0)} " +
                        "shift=(${match?.offsetX ?: 0},${match?.offsetY ?: 0}) " +
                        "thr=$threshold changed=$changed"
                )
                changed
            }
            true
        } ?: false
    }

    private fun saveDebugBitmap(name: String, bmp: android.graphics.Bitmap) {
        runCatching {
            java.io.File(filesDir, name).outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
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
        return cropBitmap(bmp, region)
    }

    private suspend fun cropBitmap(
        bmp: android.graphics.Bitmap,
        region: android.graphics.Rect?,
    ): android.graphics.Bitmap? {
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

    /** 当前活动窗口的可见控件树里，是否有节点的 text 或 contentDescription 包含 [target]（忽略大小写）。 */
    private suspend fun screenHasText(target: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val root = rootInActiveWindow ?: return@withContext false
            val needle = target.trim()
            if (needle.isEmpty()) return@withContext false
            fun walk(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser) {
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    if (text.contains(needle, ignoreCase = true) ||
                        desc.contains(needle, ignoreCase = true)
                    ) return true
                }
                for (i in 0 until node.childCount) if (walk(node.getChild(i))) return true
                return false
            }
            walk(root)
        }

    private suspend fun execute(
        action: Action,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
    ) {
        when (action.type) {
            ActionType.TAP, ActionType.LONG_PRESS, ActionType.SWIPE -> performGesture(action)
            ActionType.WAIT -> {
                // 随机抖动：在固定时长之上额外等 0~randomExtraMs 毫秒，模拟真人节奏。
                val extra = if (action.randomExtraMs > 0)
                    kotlin.random.Random.nextLong(action.randomExtraMs + 1) else 0L
                delay(action.durationMs + extra)
            }
            ActionType.SCREENSHOT_AI -> {
                val prompt = action.aiPrompt
                    ?: App.from(this@ClickerAccessibilityService).settingsRepo.defaultPrompt
                ai.run(prompt, scriptId, action.aiProvider, action.aiModel).onFailure { /* swallow */ }
            }
            ActionType.AI_TAP -> {
                val target = action.aiPrompt ?: return
                val result = tap.locate(target, scriptId, action.aiProvider, action.aiModel)
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
            ActionType.IF_PAGE_CHANGED,
            ActionType.IF_IMAGE_EXISTS,
            ActionType.IF_TEXT_EXISTS,
            ActionType.LOOP,
            ActionType.STOP -> Unit
        }
    }

    private fun enterIntoFocused(): Boolean {
        // 1) 首选：无障碍输入法接口。执行输入框声明的回车动作（评论框多半是「发送」），
        //    微信等剥掉节点信息的输入框也有效。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && imeEnter()) return true
        // 2) 兜底：节点动作。
        val focus = findFocusedEditable() ?: return false
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

    /**
     * 通过无障碍输入法接口触发回车：优先执行输入框声明的 IME 动作（发送/搜索/前往/完成），
     * 没有明确动作时退化为发送回车键事件。Android 14+。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun imeEnter(): Boolean {
        val im = runCatching { inputMethod }.getOrNull() ?: return false
        if (!im.currentInputStarted) {
            App.from(this).appendLog("ENTER IME: 输入未开始")
            return false
        }
        val ic = im.currentInputConnection ?: run {
            App.from(this).appendLog("ENTER IME: 无 InputConnection")
            return false
        }
        val ei = im.currentInputEditorInfo
        val imeOptions = ei?.imeOptions ?: 0
        val action = imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
        // 模仿真实键盘：多行框、或带 NO_ENTER_ACTION 标志时，回车=插入换行；否则才执行 IME 动作。
        val multiline = ((ei?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction =
            (imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val hasAction = action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
            action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
        return try {
            if (hasAction && !multiline && !noEnterAction) {
                ic.performEditorAction(action)
                App.from(this).appendLog("ENTER via IME performEditorAction action=$action")
            } else {
                ic.commitText("\n", 1, null)
                App.from(this).appendLog("ENTER via IME 换行 (multiline=$multiline action=$action)")
            }
            true
        } catch (t: Throwable) {
            App.from(this).appendLog("ENTER IME 异常: ${t.message}")
            false
        }
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
