package com.wangchaozhi.wechatassistant.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.wangchaozhi.wechatassistant.App
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
    ): Result<String> {
        if (!ServiceBus.captureReady.value) {
            return Result.failure(IllegalStateException("截图服务未启动，请先在主界面开启屏幕共享。"))
        }
        // 优先复用截图流的最新帧：流帧本就是「无悬浮窗」的连续画面，省掉 JustCapture 每次必付的
        // ~180ms 隐藏悬浮窗等待——热流下取下一帧仅约一个帧间隔(25ms)。流由本节点幂等开启、不主动
        // 关闭，连续 AI 节点复用同一路热流（仅首个节点付一次预热），由 runGraph 的 finally 统一关流。
        // 整屏流帧的 region 裁剪交给 runWithBitmap；流不可用/超时再回退到主动截图。
        val streamBitmap = grabFreshStreamFrame()
        if (streamBitmap != null) {
            return runWithBitmap(streamBitmap, prompt, scriptId, provider, model, region)
        }
        val bitmap = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture(region))
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))
        return runWithBitmap(bitmap, prompt, scriptId, provider, model)
    }

    /**
     * 幂等开启截图流并取一帧「比进入时更新且画面已稳」的画面。
     * - 新鲜度：只接受 id 比进入时更新的帧（id 单调递增，避免复用上一轮残留帧）。
     * - 帧龄守卫：连续 [STREAM_STABLE_FRAMES] 次相邻帧相似度 >= [STREAM_STABLE_SIM] 才算稳，
     *   挡掉「AI 节点紧跟改变画面的动作」时拿到的转场动画/未渲染完中间帧。整屏比对即可——
     *   RegionDiff 内部统一缩放到 128 灰度，开销有上界，且无需裁剪、不触碰流帧的生命周期。
     * 冷启动需等约 180ms 预热首帧、热流约 25ms/帧。持续在动到超时则退用已收到的最新帧（避免再付
     * 一次主动截图的预热成本）；一帧都没收到才返回 null，由调用方回退主动截图。
     */
    private suspend fun grabFreshStreamFrame(): Bitmap? {
        val entryId = ServiceBus.streamFrame.value?.id ?: -1L
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.StartStream)
        var latest: Bitmap? = null
        val settled = withTimeoutOrNull(STREAM_FRAME_WAIT_MS) {
            var lastId = entryId
            var prev: Bitmap? = null
            var stableStreak = 0
            ServiceBus.streamFrame.first { frame ->
                if (frame == null || frame.id == lastId) return@first false
                lastId = frame.id
                latest = frame.bitmap
                val p = prev
                val stable = p != null &&
                    (RegionDiff.similarity(p, frame.bitmap) ?: -1.0) >= STREAM_STABLE_SIM
                prev = frame.bitmap
                stableStreak = if (stable) stableStreak + 1 else 0
                stableStreak >= STREAM_STABLE_FRAMES
            }!!.bitmap
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
    ): Result<String> {
        val app = App.from(context)
        val settings = app.settingsRepo
        val maxSide = settings.aiImageMaxSide
        val quality = qualityFor(maxSide)
        val effective = prompt.ifBlank { settings.defaultPrompt }
        val (usedProvider, usedModel) = vision.resolve(provider, model)
        val askBitmap = region?.let { cropBitmapByScreenRect(bitmap, it) } ?: bitmap
        val result = vision.ask(askBitmap, effective, provider, model, maxSide = maxSide, quality = quality)
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
        return result
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

    private fun qualityFor(maxSide: Int): Int = when {
        maxSide <= 1024 -> 70
        maxSide <= 1280 -> 80
        else -> 85
    }

    companion object {
        // 等一帧「已稳」流式画面的预算：够覆盖冷启动 ~180ms 预热 + 几帧判稳；
        // 画面持续在动到此上限则退用最新帧，避免无谓久等。
        private const val STREAM_FRAME_WAIT_MS = 1_500L
        // 帧龄守卫：相邻两帧 128 灰度归一化相关度阈值；>= 即视为画面基本静止。
        // 比 awaitRegionSettled 的 0.995 略松，避免状态栏时钟等细微动态导致整屏永不判稳。
        private const val STREAM_STABLE_SIM = 0.99
        // 需要连续多少次「判稳」才采用：2 即三帧两次比对(~50ms 热流)，轻量但能挡掉转场中间帧。
        private const val STREAM_STABLE_FRAMES = 2
    }
}
