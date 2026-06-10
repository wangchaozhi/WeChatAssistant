package com.wangchaozhi.wechatassistant.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.copyToClipboard
import kotlinx.coroutines.flow.first
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
        val bitmap = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))
        return runWithBitmap(bitmap, prompt, scriptId, provider, model, region)
    }

    suspend fun runWithBitmap(
        bitmap: Bitmap,
        prompt: String,
        scriptId: Long? = null,
        provider: String? = null,
        model: String? = null,
        region: Rect? = null,
    ): Result<String> {
        val settings = App.from(context).settingsRepo
        val maxSide = settings.aiImageMaxSide
        val quality = qualityFor(maxSide)
        val effective = prompt.ifBlank { settings.defaultPrompt }
        val (usedProvider, usedModel) = vision.resolve(provider, model)
        val askBitmap = region?.let { cropBitmapByScreenRect(bitmap, it) } ?: bitmap
        val result = vision.ask(askBitmap, effective, provider, model, maxSide = maxSide, quality = quality)
        result.onSuccess { answer ->
            context.copyToClipboard(answer)
            ServiceBus.lastAiAnswer.value = answer
            runCatching {
                history.save(askBitmap, effective, answer, scriptId, usedProvider.name, usedModel)
            }
        }
        if (askBitmap !== bitmap) askBitmap.recycle()
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
        maxSide <= 1024 -> 75
        maxSide <= 1280 -> 80
        else -> 85
    }
}
