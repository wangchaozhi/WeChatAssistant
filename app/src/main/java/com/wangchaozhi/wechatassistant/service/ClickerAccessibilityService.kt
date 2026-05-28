package com.wangchaozhi.wechatassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptWithActions
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
import kotlin.coroutines.resume

class ClickerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

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
            val data = app.scriptRepo.load(scriptId) ?: return@launch
            try {
                runScript(data, app.screenshotAi, app.aiTap, scriptId)
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

    private suspend fun runScript(
        data: ScriptWithActions,
        ai: ScreenshotAiUseCase,
        tap: AiTapUseCase,
        scriptId: Long,
    ) {
        val script = data.script
        val actions = data.actions.sortedBy { it.index }
        if (actions.isEmpty()) return
        val loops = if (script.loopCount <= 0) Int.MAX_VALUE else script.loopCount
        repeat(loops) { _ ->
            if (!scope.isActive) return
            actions.forEachIndexed { i, action ->
                ServiceBus.playerState.value =
                    ServiceBus.PlayerState.Playing(script, i, actions.size)
                if (action.delayBeforeMs > 0) {
                    delay((action.delayBeforeMs / script.speed).toLong().coerceAtLeast(0))
                }
                execute(action, ai, tap, scriptId)
            }
        }
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
                val point = tap.locate(target, scriptId).getOrNull() ?: return
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
        }
    }

    private suspend fun performGesture(a: Action) {
        val path = Path().apply {
            moveTo(a.startX, a.startY)
            if (a.type == ActionType.SWIPE) lineTo(a.endX, a.endY)
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
