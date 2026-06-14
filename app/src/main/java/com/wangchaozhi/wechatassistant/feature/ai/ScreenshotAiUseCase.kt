package com.wangchaozhi.wechatassistant.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.BuildConfig
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.feature.match.RegionDiff
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.copyToClipboard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class ScreenshotAiUseCase(
    private val context: Context,
    private val vision: VisionAiRepository,
    private val history: AiAnswerRepository,
) {

    suspend fun run(
        prompt: String,
        scriptId: Long? = null,
        provider: String? = null,
        model: String? = null,
        region: Rect? = null,
        fastRegionCapture: Boolean = true,
        streamOutput: Boolean = false,
    ): Result<String> {
        val app = App.from(context)
        val totalStart = SystemClock.uptimeMillis()
        if (!ServiceBus.captureReady.value) {
            debugLog(app, "AI screenshot failed: capture service not ready")
            return Result.failure(IllegalStateException("截图服务未启动，请先在主界面开启屏幕共享。"))
        }
        debugLog(
            app,
            "AI screenshot start scriptId=${scriptId ?: "-"} provider=${provider ?: "default"} " +
                "model=${model ?: "default"} region=$region fastRegion=$fastRegionCapture " +
                    "stream=$streamOutput promptLen=${prompt.length}"
        )
        // 有框选区域时优先直接截区域图：截图服务会从 ImageReader 的原始帧里裁区域，省掉整屏 Bitmap
        // 分配、整屏流帧判稳和后续二次裁剪，更接近悬浮窗「直接 AI」路径。失败时再回退流帧兜底。
        if (region != null && fastRegionCapture) {
            val directStart = SystemClock.uptimeMillis()
            val directBitmap = captureRegionDirect(region)
            val directMs = SystemClock.uptimeMillis() - directStart
            if (directBitmap != null) {
                debugLog(app, "AI screenshot capture source=region wait=${directMs}ms size=${directBitmap.width}x${directBitmap.height}")
                val result = runWithBitmap(directBitmap, prompt, scriptId, provider, model, streamOutput = streamOutput)
                debugLogResult(app, "AI screenshot total source=region", result, totalStart)
                return result
            }
            debugLog(app, "AI screenshot region capture unavailable wait=${directMs}ms, fallback=stream")
        } else if (region != null) {
            debugLog(app, "AI screenshot region capture skipped fastRegion=false, fallback=stream")
        }
        // 无区域或区域直截失败时复用截图流：流帧本就是「无悬浮窗」的连续画面，热流下取下一帧仅约
        // 一个帧间隔。流由本节点幂等开启、不主动关闭，连续 AI 节点可复用同一路热流。
        val streamStart = SystemClock.uptimeMillis()
        val streamBitmap = grabFreshStreamFrame(region)
        val streamMs = SystemClock.uptimeMillis() - streamStart
        if (streamBitmap != null) {
            debugLog(app, "AI screenshot capture source=stream wait=${streamMs}ms size=${streamBitmap.width}x${streamBitmap.height}")
            val result = runWithBitmap(streamBitmap, prompt, scriptId, provider, model, region, streamOutput)
            debugLogResult(app, "AI screenshot total source=stream", result, totalStart)
            return result
        }
        debugLog(app, "AI screenshot stream unavailable wait=${streamMs}ms, fallback=JustCapture")
        val captureStart = SystemClock.uptimeMillis()
        val bitmap = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture(region))
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: run {
            debugLog(app, "AI screenshot capture timeout total=${SystemClock.uptimeMillis() - totalStart}ms")
            return Result.failure(IllegalStateException("截图超时。"))
        }
        debugLog(app, "AI screenshot capture source=JustCapture wait=${SystemClock.uptimeMillis() - captureStart}ms size=${bitmap.width}x${bitmap.height}")
        val result = runWithBitmap(bitmap, prompt, scriptId, provider, model, streamOutput = streamOutput)
        debugLogResult(app, "AI screenshot total source=JustCapture", result, totalStart)
        return result
    }

    private suspend fun captureRegionDirect(region: Rect): Bitmap? = withTimeoutOrNull(2_000) {
        ServiceBus.lastBitmap.value = null
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture(region))
        ServiceBus.lastBitmap.first { it != null }!!
    }

    /**
     * 幂等开启截图流并取一帧「比进入时更新且画面已稳」的画面。
     * - 新鲜度：只接受 id 比进入时更新的帧（id 单调递增，避免复用上一轮残留帧）。
     * - 帧龄守卫：相邻帧相似度 >= [STREAM_STABLE_SIM] 即算稳。指定区域时只比对 AI 区域，避免
     *   状态栏/聊天列表等无关位置轻微变化拖慢 AI 截图；未指定区域时仍比对整屏。
     * 冷启动需等约 180ms 预热首帧、热流约 25ms/帧。持续在动到超时则退用已收到的最新帧（避免再付
     * 一次主动截图的预热成本）；一帧都没收到才返回 null，由调用方回退主动截图。
     */
    private suspend fun grabFreshStreamFrame(region: Rect?): Bitmap? {
        val entryId = ServiceBus.streamFrame.value?.id ?: -1L
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StartStream)
        var latest: Bitmap? = null
        val settled = withTimeoutOrNull(STREAM_FRAME_WAIT_MS) {
            var lastId = entryId
            var prev: Bitmap? = null
            var stableStreak = 0
            val recycleStabilityFrames = region != null
            try {
                ServiceBus.streamFrame.first { frame ->
                    if (frame == null || frame.id == lastId) return@first false
                    lastId = frame.id
                    latest = frame.bitmap
                    val current = frameForStability(frame.bitmap, region) ?: return@first false
                    val p = prev
                    val stable = p != null &&
                        (RegionDiff.similarity(p, current) ?: -1.0) >= STREAM_STABLE_SIM
                    if (recycleStabilityFrames) p?.recycle()
                    prev = current
                    stableStreak = if (stable) stableStreak + 1 else 0
                    stableStreak >= STREAM_STABLE_FRAMES
                }!!.bitmap
            } finally {
                if (recycleStabilityFrames) prev?.recycle()
            }
        }
        return settled ?: latest
    }

    suspend fun runWithBitmap(
        bitmap: Bitmap,
        prompt: String,
        scriptId: Long? = null,
        provider: String? = null,
        model: String? = null,
        region: Rect? = null,
        streamOutput: Boolean = false,
    ): Result<String> {
        val totalStart = SystemClock.uptimeMillis()
        val app = App.from(context)
        val settings = app.settingsRepo
        val maxSide = settings.aiImageMaxSide
        val quality = qualityFor(maxSide)
        val effective = prompt.ifBlank { settings.defaultPrompt }
        val (usedProvider, usedModel) = vision.resolve(provider, model)
        val cropStart = SystemClock.uptimeMillis()
        val askBitmap = region?.let { cropBitmapByScreenRect(bitmap, it) } ?: bitmap
        val cropMs = SystemClock.uptimeMillis() - cropStart
        debugLog(
            app,
            "AI ask prepare provider=${usedProvider.name} model=$usedModel " +
                "source=${bitmap.width}x${bitmap.height} ask=${askBitmap.width}x${askBitmap.height} " +
                "region=$region maxSide=$maxSide quality=$quality crop=${cropMs}ms promptLen=${effective.length}"
        )
        val askStart = SystemClock.uptimeMillis()
        var firstPartialMs: Long? = null
        val onPartial: ((String) -> Unit)? = if (streamOutput) {
            { partial ->
                if (partial.isNotEmpty()) {
                    if (firstPartialMs == null) {
                        firstPartialMs = SystemClock.uptimeMillis() - askStart
                        debugLog(app, "AI stream firstPartial=${firstPartialMs}ms chars=${partial.length}")
                    }
                    ServiceBus.lastAiAnswer.value = partial
                    ServiceBus.lastAiResult.value = ServiceBus.AiResult.Partial(partial)
                }
            }
        } else {
            null
        }
        val result = vision.ask(
            askBitmap,
            effective,
            provider,
            model,
            maxSide = maxSide,
            quality = quality,
            onPartial = onPartial,
        )
        val askMs = SystemClock.uptimeMillis() - askStart
        var savingInBackground = false
        result.onSuccess { answer ->
            context.copyToClipboard(answer)
            ServiceBus.lastAiAnswer.value = answer
            if (settings.saveAiHistory) {
                savingInBackground = true
                app.appScope.launch {
                    runCatching {
                        history.save(askBitmap, effective, answer, scriptId, usedProvider.name, usedModel)
                    }.onFailure {
                        app.appendLog("AI history save failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
                    if (askBitmap !== bitmap) {
                        askBitmap.recycle()
                    }
                }
            }
        }
        if (askBitmap !== bitmap && !savingInBackground) askBitmap.recycle()
        debugLogResult(
            app,
            "AI ask finish provider=${usedProvider.name} model=$usedModel ask=${askMs}ms crop=${cropMs}ms",
            result,
            totalStart,
        )
        return result
    }

    private fun debugLog(app: App, message: String) {
        if (BuildConfig.DEBUG) app.appendLog(message)
    }

    private fun debugLogResult(app: App, prefix: String, result: Result<String>, startMs: Long) {
        if (!BuildConfig.DEBUG) return
        val totalMs = SystemClock.uptimeMillis() - startMs
        result.fold(
            onSuccess = { app.appendLog("$prefix success total=${totalMs}ms answerLen=${it.length}") },
            onFailure = { app.appendLog("$prefix failure total=${totalMs}ms err=${it.javaClass.simpleName}: ${it.message}") },
        )
    }

    private fun cropBitmapByScreenRect(bmp: Bitmap, rect: Rect): Bitmap? {
        val dm = context.resources.displayMetrics
        val sx = bmp.width.toFloat() / dm.widthPixels.coerceAtLeast(1)
        val sy = bmp.height.toFloat() / dm.heightPixels.coerceAtLeast(1)
        val l = (rect.left * sx).roundToInt().coerceIn(0, bmp.width - 1)
        val t = (rect.top * sy).roundToInt().coerceIn(0, bmp.height - 1)
        val r = (rect.right * sx).roundToInt().coerceIn(l + 1, bmp.width)
        val b = (rect.bottom * sy).roundToInt().coerceIn(t + 1, bmp.height)
        val w = r - l
        val h = b - t
        if (w < 8 || h < 8) return null
        return runCatching { Bitmap.createBitmap(bmp, l, t, w, h) }.getOrNull()
    }

    private fun frameForStability(bmp: Bitmap, region: Rect?): Bitmap? =
        region?.let { cropBitmapByScreenRect(bmp, it) } ?: bmp

    private fun qualityFor(maxSide: Int): Int = when {
        maxSide <= 1024 -> 70
        maxSide <= 1280 -> 80
        else -> 85
    }

    companion object {
        // 等一帧「已稳」流式画面的预算：够覆盖冷启动 ~180ms 预热 + 少量判稳；
        // 画面持续在动到此上限则退用最新帧，避免无谓久等。
        private const val STREAM_FRAME_WAIT_MS = 800L
        // 帧龄守卫：相邻两帧 128 灰度归一化相关度阈值；>= 即视为画面基本静止。
        // 比 awaitRegionSettled 的 0.995 略松，避免状态栏时钟等细微动态导致整屏永不判稳。
        private const val STREAM_STABLE_SIM = 0.99
        // AI 截图优先低延迟：一次相邻帧判稳即可；条件节点仍走独立的更严格稳定逻辑。
        private const val STREAM_STABLE_FRAMES = 1
    }
}
