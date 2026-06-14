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
import com.wangchaozhi.wechatassistant.BuildConfig
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionDefaults
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptWithGraph
import com.wangchaozhi.wechatassistant.feature.ai.AiTapUseCase
import com.wangchaozhi.wechatassistant.feature.ai.ScreenshotAiUseCase
import com.wangchaozhi.wechatassistant.feature.match.RegionDiff
import com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
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
        // CALL_SCRIPT 嵌套调用的最大深度，防止脚本互相调用形成过深/环状链。
        const val MAX_CALL_DEPTH = 16
        // IF_IMAGE_EXISTS 等稳定(awaitRegionSettled)判定「页面已稳定」的门槛：连续多帧相关度高于此值算静止。
        const val PAGE_SETTLE_SIM = 0.995
        // 需要连续静止的帧数，过滤掉刷新过程中偶发的一帧暂停，避免在加载中途就下结论。
        const val PAGE_SETTLE_FRAMES = 6
        // 还要连续静止持续够这么久(ms)才算「真正稳定」。光靠帧数容易被刷新中途的短暂静止窗口骗到
        // (如发完朋友圈列表还在加载、某块区域恰好停了几帧)，加一道时间门槛把这种中途静止滤掉。
        const val PAGE_SETTLE_MS = 600L
        // IMAGE_MATCH：跟着截图流逐帧匹配，连续这么多帧都命中、且命中点没动，才认为稳定、可点击。
        const val IMAGE_STABLE_FRAMES = 5
        // 两帧命中点位移在此像素内视为「没动」。模板缩放/噪声会有 1~2px 抖动，留点余量；超出即立即判否。
        const val IMAGE_STABLE_TOLERANCE_PX = 3f
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
            // 触发器(通知/定时)的播放请求：StateFlow 会把冷启动前设好的「待播放」值重放给刚连接的服务。
            // 消费后置回 null，避免重复播放，也便于同一脚本下次再触发能再次生效。
            ServiceBus.pendingPlay.collect { id ->
                if (id != null) {
                    ServiceBus.pendingPlay.value = null
                    startPlay(id)
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
                runCatching { performGesture(raw.toGestureAction(), showMarker = false) }
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

    private fun pasteIntoFocused(preferredText: String? = null): Boolean {
        // 1) 首选：无障碍输入法接口直接 commitText（Android 14+）。走的是和真实键盘相同的
        //    InputConnection 通道，由输入框自己接收，不依赖无障碍节点——微信等剥掉节点信息的
        //    输入框也能写进去。要写的文本取自我们自己内存里的 AI 答案（后台读不到剪贴板）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val text = pasteText(preferredText)
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
        if (!preferredText.isNullOrEmpty()) return pasteBySetText(node, preferredText)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return pasteBySetText(node, preferredText)
    }

    /** 要粘贴的文本：优先节点自带文本，其次内存里的 AI 答案，最后尝试剪贴板（后台多半读不到）。 */
    private fun pasteText(preferredText: String? = null): String? =
        preferredText?.takeIf { it.isNotEmpty() }
            ?: ServiceBus.lastAiAnswer.value?.takeIf { it.isNotEmpty() }
            ?: clipboardText()

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
    private fun pasteBySetText(node: AccessibilityNodeInfo, preferredText: String? = null): Boolean {
        val text = pasteText(preferredText)
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
        val active = playJob
        if (active?.isActive == true) {
            App.from(this).appendLog("PLAY 忽略重复启动 scriptId=$scriptId")
            return
        }
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
     * - IF_IMAGE_EXISTS 节点检测模板图是否在屏幕(或指定范围)出现，找到走出口 0、没找到走出口 1；
     * - 其它节点执行后走出口 0。
     * 一个出口可连多条边：按连线顺序依次深度优先执行（先把第一条分支整支跑完，再下一条）。
     * 循环由回指的边表达；用显式栈而非递归避免爆栈。MAX_GRAPH_STEPS 防无闸死循环。
     */
    private suspend fun runGraph(
        data: ScriptWithGraph,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
        // 当前调用栈上的脚本 id 集合，用于阻断 CALL_SCRIPT 的递归自调用。根脚本预置在内。
        callStack: MutableSet<Long> = mutableSetOf(scriptId),
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
        var debugImageSeq = 0
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
            // 「执行前等待」对普通执行节点生效。
            // IMAGE_MATCH 把这段时间当作「找到目标并等其停稳」的总预算，
            // 交给 locateStable 边截图边消化，不在这里盲等（详见 execute 里的 IMAGE_MATCH 分支）。
            // IF_IMAGE_EXISTS 只使用自身的「等画面稳定再判」，避免同一个判断节点出现两套等待语义。
            if (node.delayBeforeMs > 0 &&
                node.type != ActionType.IMAGE_MATCH &&
                node.type != ActionType.IF_IMAGE_EXISTS
            ) {
                delay((node.delayBeforeMs / script.speed).toLong().coerceAtLeast(0))
            }
            val port = when (node.type) {
                ActionType.START -> 0
                ActionType.IF_IMAGE_EXISTS -> {
                    // BigClicker 风格的图片识别：在搜索区域内滑动模板做 matchTemplate。
                    // region 为空时退回整屏搜索；有 region 时只在该区域附近找，速度更快也更稳。
                    val paths = TemplateMatchUseCase.splitTemplatePaths(node.templatePath)
                        .firstOrNull()
                        ?.let { listOf(it) }
                        .orEmpty()
                    val region = regionOf(node)
                    // 设了「等稳定时长」(durationMs>0)：先等搜索区域真正稳定再判，
                    // 避免刷新/列表挪动(如刚发完朋友圈)时拿中间帧误判 found 抖动。0=保持单次瞬时判定。
                    if (node.durationMs > 0L) awaitRegionSettled(region, node.durationMs)
                    val seq = ++debugImageSeq
                    val dbg = if (BuildConfig.DEBUG) "dbg_imgexists_$seq" else null
                    val result = if (paths.isNotEmpty()) {
                        val matcher = App.from(this@ClickerAccessibilityService).templateMatch
                        matcher.locateAny(
                            paths,
                            node.matchThreshold,
                            region,
                            dbg,
                            imageDownFallbackPx(node),
                            imageUpFallbackPx(node),
                        )
                    } else {
                        Result.failure(IllegalStateException("未设置模板图"))
                    }
                    val match = result.getOrNull()
                    val found = match != null
                    flashImageResult(match?.box, region, if (found) "已找到" else "未找到")
                    App.from(this@ClickerAccessibilityService).appendLog(
                        "IF_IMAGE_EXISTS(matchTemplate) precision=${TemplateMatchUseCase.thresholdToPrecision(node.matchThreshold)} " +
                            "thr=${"%.2f".format(node.matchThreshold)} " +
                            "score=${match?.score?.let { "%.3f".format(it) } ?: "-"} " +
                            "tpl=${match?.index?.plus(1) ?: "-"} " +
                            "found=$found center=${match?.point} region=$region dbg=${dbg?.let { "$it.png" } ?: "-"} " +
                            "err=${result.exceptionOrNull()?.message.orEmpty()}"
                    )
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
                ActionType.CALL_SCRIPT -> {
                    val app = App.from(this@ClickerAccessibilityService)
                    val targetId = node.callScriptId
                    when {
                        targetId == null ->
                            app.appendLog("CALL_SCRIPT skipped: 未设置目标脚本 id=${node.id}")
                        targetId in callStack ->
                            app.appendLog("CALL_SCRIPT skipped: 检测到递归调用 target=$targetId")
                        callStack.size >= MAX_CALL_DEPTH ->
                            app.appendLog("CALL_SCRIPT skipped: 调用深度超上限($MAX_CALL_DEPTH) target=$targetId")
                        else -> {
                            val sub = app.scriptRepo.loadGraph(targetId)
                            if (sub == null || sub.actions.isEmpty()) {
                                app.appendLog("CALL_SCRIPT skipped: 目标脚本为空/不存在 target=$targetId")
                            } else {
                                app.appendLog("CALL_SCRIPT enter target=$targetId name=${sub.script.name}")
                                callStack.add(targetId)
                                try {
                                    runGraph(sub, ai, tap, targetId, callStack)
                                } finally {
                                    callStack.remove(targetId)
                                }
                                app.appendLog("CALL_SCRIPT return target=$targetId")
                            }
                        }
                    }
                    0
                }
                else -> {
                    execute(node, ai, tap, scriptId, script.speed)
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
            // IF_IMAGE_EXISTS 的 awaitRegionSettled 不自行 StopStream，连续节点复用同一路流、不重复预热。
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StopStream)
        }
    }


    /**
     * 跟随截图流，等指定区域(region 为空=整屏)连续静止够帧数(>= [PAGE_SETTLE_FRAMES])且持续够久
     * (>= [PAGE_SETTLE_MS]，并自动 coerce 到 timeout 的一半内) —— 即「真正稳定」后返回 true；
     * [timeoutMs] 内没等到稳定则返回 false。供条件/找图判定前先让刷新/动画/列表挪动落定，避免拿中间帧下结论。
     */
    private suspend fun awaitRegionSettled(region: android.graphics.Rect?, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        // 幂等开流；由 runGraph 的 finally 统一 StopStream。
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StartStream)
        val settleMs = PAGE_SETTLE_MS.coerceAtMost((timeoutMs / 2).coerceAtLeast(0L))
        val startMs = android.os.SystemClock.uptimeMillis()
        var lastScore = -1.0
        val ok = withTimeoutOrNull(timeoutMs) {
            var lastId = -1L
            var prev: android.graphics.Bitmap? = null
            var stableStreak = 0
            var stableSinceMs = 0L
            try {
                ServiceBus.streamFrame.first { frame ->
                    if (frame == null || frame.id == lastId) return@first false
                    lastId = frame.id
                    val now = cropBitmap(frame.bitmap, region) ?: return@first false
                    val prevBmp = prev
                    val stableScore = if (prevBmp != null)
                        RegionDiff.compare(prevBmp, now)?.score ?: -1.0 else -1.0
                    prevBmp?.recycle()
                    prev = now
                    lastScore = stableScore
                    val settled = prevBmp != null && stableScore >= PAGE_SETTLE_SIM
                    if (!settled) {
                        stableStreak = 0
                        stableSinceMs = 0L
                        return@first false
                    }
                    if (stableStreak == 0) stableSinceMs = android.os.SystemClock.uptimeMillis()
                    stableStreak += 1
                    val stableForMs = android.os.SystemClock.uptimeMillis() - stableSinceMs
                    stableStreak >= PAGE_SETTLE_FRAMES && stableForMs >= settleMs
                }
                true
            } finally {
                prev?.recycle()
            }
        } ?: false
        val waitedMs = android.os.SystemClock.uptimeMillis() - startMs
        App.from(this@ClickerAccessibilityService).appendLog(
            "awaitRegionSettled settled=$ok waited=${waitedMs}ms timeout=${timeoutMs}ms " +
                "needSettleMs=$settleMs lastSim=${"%.3f".format(lastScore)} region=$region"
        )
        return ok
    }

    private fun flashImageResult(
        matchBox: android.graphics.Rect?,
        region: android.graphics.Rect?,
        label: String,
    ) {
        if (!App.from(this).settingsRepo.showPlaybackMarker) return
        val rect = matchBox ?: region ?: fullScreenRect()
        ServiceBus.overlayCmd.tryEmit(
            ServiceBus.OverlayCmd.FlashPositionMarker(
                ServiceBus.PositionMarker.Region(rect, label)
            )
        )
    }

    /**
     * 全程持续检测，等目标稳定再点：跟随截图流，对每一帧都跑一次模板匹配，
     * 直到连续 [IMAGE_STABLE_FRAMES] 帧都命中、且命中点位移都在 [IMAGE_STABLE_TOLERANCE_PX] 内
     * （目标已渲染完、不再移动）才返回该命中。用匹配本身来判稳，命中点天然就是要点的位置。
     *  - 没匹配到（还没出现 / 动画过渡中模板对不上）→ 立即判否，计数清零。
     *  - 命中点一旦跳动超出容差 → 立即判否，从这一帧重新计数。
     *  - [budgetMs] 是「等出现 + 等停稳」的总预算；用尽仍没攒够连续稳定帧 → 返回 null，调用方跳过不点。
     */
    private suspend fun locateStable(
        paths: List<String>,
        action: Action,
        region: android.graphics.Rect?,
        budgetMs: Long,
    ): TemplateMatchUseCase.MultiMatchResult? {
        if (budgetMs <= 0L) return null
        val matcher = App.from(this@ClickerAccessibilityService).templateMatch
        val fallbackPx = imageDownFallbackPx(action)
        val upFallbackPx = imageUpFallbackPx(action)
        // 幂等开流（已在流式则空操作）；由 runGraph 的 finally 统一 StopStream。
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StartStream)
        return withTimeoutOrNull(budgetMs) {
            var lastId = -1L
            var prev: TemplateMatchUseCase.MultiMatchResult? = null
            var stableStreak = 0
            var result: TemplateMatchUseCase.MultiMatchResult? = null
            while (result == null) {
                val frame = ServiceBus.streamFrame.first { it != null && it.id != lastId }!!
                lastId = frame.id
                val cur = matcher.locateAnyIn(
                    frame.bitmap,
                    paths,
                    action.matchThreshold,
                    region,
                    downFallbackBasePx = fallbackPx,
                    upFallbackBasePx = upFallbackPx,
                ).getOrNull()
                if (cur == null) {
                    // 没匹配到（还没出现 / 过渡中对不上）：立即判否，重新累计。
                    prev = null
                    stableStreak = 0
                } else {
                    val p = prev
                    // 命中点没动→连续计数+1；一变就立即重置为 1（这一帧算新的起点）。
                    stableStreak = if (p != null &&
                        kotlin.math.hypot(cur.point.x - p.point.x, cur.point.y - p.point.y) <=
                        IMAGE_STABLE_TOLERANCE_PX
                    ) stableStreak + 1 else 1
                    prev = cur
                    if (stableStreak >= IMAGE_STABLE_FRAMES) result = cur
                }
            }
            result
        }
    }

    private fun flashGesture(a: Action) {
        if (!App.from(this).settingsRepo.showPlaybackMarker) return
        val marker = when (a.type) {
            ActionType.TAP -> ServiceBus.PositionMarker.Point(a.startX, a.startY, "点击")
            ActionType.LONG_PRESS -> ServiceBus.PositionMarker.Point(a.startX, a.startY, "长按")
            ActionType.SWIPE -> ServiceBus.PositionMarker.Swipe(a.startX, a.startY, a.endX, a.endY, "滑动")
            else -> null
        } ?: return
        ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.FlashPositionMarker(marker))
    }

    private fun imageDownFallbackPx(action: Action): Int =
        if (action.retryCount == 10) {
            ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX
        } else {
            action.retryCount.coerceAtLeast(0)
        }

    private fun imageUpFallbackPx(action: Action): Int = action.upFallbackPx.coerceAtLeast(0)

    private fun fullScreenRect(): android.graphics.Rect {
        val dm = resources.displayMetrics
        return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
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

    /** 从节点的 startX/startY/endX/endY 取范围矩形；无效则整页（null）。 */
    private fun regionOf(node: Action): android.graphics.Rect? =
        if (node.endX > node.startX && node.endY > node.startY)
            android.graphics.Rect(node.startX.toInt(), node.startY.toInt(), node.endX.toInt(), node.endY.toInt())
        else null

    private suspend fun execute(
        action: Action,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
        speed: Float = 1f,
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
                ai.run(
                    prompt,
                    scriptId,
                    action.aiProvider,
                    action.aiModel,
                    regionOf(action),
                    fastRegionCapture = action.aiFastRegionCapture,
                    streamOutput = action.aiStreamOutput,
                ).onFailure { /* swallow */ }
            }
            ActionType.IMAGE_MATCH -> {
                val paths = TemplateMatchUseCase.splitTemplatePaths(action.templatePath)
                if (paths.isEmpty()) {
                    App.from(this@ClickerAccessibilityService).appendLog(
                        "IMAGE_MATCH skipped: 未设置模板 id=${action.id} idx=${action.index}"
                    )
                    return
                }
                val region = regionOf(action)
                // 等目标稳定再点：在「执行前等待」这段预算内反复截图匹配，
                // 直到目标出现且连续两帧几乎不动（画面静止）才点击，避免点到动画/过渡中间帧。
                val budgetMs = (action.delayBeforeMs / speed).toLong().coerceAtLeast(0)
                val match = if (budgetMs > 0L) {
                    locateStable(paths, action, region, budgetMs)
                } else {
                    val dbg = if (BuildConfig.DEBUG) "dbg_imgclick_${System.currentTimeMillis()}" else null
                    App.from(this@ClickerAccessibilityService).templateMatch
                        .locateAny(
                            paths,
                            action.matchThreshold,
                            region,
                            dbg,
                            imageDownFallbackPx(action),
                            imageUpFallbackPx(action),
                        )
                        .getOrNull()
                }
                if (match == null) {
                    App.from(this@ClickerAccessibilityService).appendLog(
                        "IMAGE_MATCH not found id=${action.id} idx=${action.index} " +
                            "precision=${TemplateMatchUseCase.thresholdToPrecision(action.matchThreshold)} " +
                            "thr=${"%.2f".format(action.matchThreshold)} count=${paths.size} region=$region"
                    )
                    return
                }
                App.from(this@ClickerAccessibilityService).appendLog(
                    "IMAGE_MATCH click id=${action.id} idx=${action.index} " +
                        "score=${"%.3f".format(match.score)} tpl=${match.index + 1}/${paths.size} " +
                        "center=${match.point} region=$region"
                )
                flashImageResult(match.box, region, "点击目标")
                performGesture(
                    action.copy(
                        type = ActionType.TAP,
                        startX = match.point.x,
                        startY = match.point.y,
                        endX = match.point.x,
                        endY = match.point.y,
                        durationMs = action.durationMs.coerceAtLeast(ActionDefaults.QUICK_TAP_MS),
                    )
                )
            }
            ActionType.PASTE -> {
                withContext(Dispatchers.Main.immediate) { pasteIntoFocused(action.pasteText) }
            }
            ActionType.ENTER -> {
                withContext(Dispatchers.Main.immediate) { enterIntoFocused() }
            }
            // 控制流节点，由 runScript / runGraph 直接处理；正常不会走到这里。
            ActionType.WAIT_PAGE_CHANGE,
            ActionType.START,
            ActionType.IF_IMAGE_EXISTS,
            ActionType.LOOP,
            ActionType.STOP,
            ActionType.CALL_SCRIPT -> Unit
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

    private suspend fun performGesture(
        a: Action,
        showMarker: Boolean = true,
    ) = withContext(Dispatchers.Main.immediate) {
        val maxX = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val maxY = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val startX = a.startX.coerceIn(0f, maxX - 1f)
        val startY = a.startY.coerceIn(0f, maxY - 1f)
        val endX = a.endX.coerceIn(0f, maxX - 1f)
        val endY = a.endY.coerceIn(0f, maxY - 1f)
        if (showMarker) {
            flashGesture(a.copy(startX = startX, startY = startY, endX = endX, endY = endY))
        }
        App.from(this@ClickerAccessibilityService).appendLog(
            "GESTURE ${a.type} id=${a.id} idx=${a.index} " +
                "start=(${"%.1f".format(startX)},${"%.1f".format(startY)}) " +
                "end=(${"%.1f".format(endX)},${"%.1f".format(endY)}) dur=${a.durationMs}"
        )
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
