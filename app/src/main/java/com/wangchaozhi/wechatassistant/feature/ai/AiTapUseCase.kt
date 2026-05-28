package com.wangchaozhi.wechatassistant.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository
import com.wangchaozhi.wechatassistant.service.ServiceBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class AiTapUseCase(
    private val context: Context,
    private val qwen: QwenRepository,
    private val history: AiAnswerRepository,
) {

    suspend fun locate(targetDescription: String, scriptId: Long? = null): Result<PointF> {
        if (!ServiceBus.captureReady.value) {
            return Result.failure(IllegalStateException("截图服务未启动。"))
        }
        val bitmap = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))

        val model = App.from(context).settingsRepo.qwenModel
        val prompt = buildPrompt(targetDescription, bitmap.width, bitmap.height)
        val raw = qwen.ask(bitmap, prompt, model, maxSide = Int.MAX_VALUE)
            .getOrElse { return Result.failure(it) }

        runCatching { history.save(bitmap, prompt, raw, scriptId) }

        val point = parseCoords(raw, bitmap.width, bitmap.height)
            ?: return Result.failure(IllegalStateException("未能从回答中解析坐标：${raw.take(120)}"))
        return Result.success(point)
    }

    private fun buildPrompt(target: String, w: Int, h: Int): String = """
        屏幕分辨率是 ${w}x${h} 像素。请你找到屏幕上的以下元素：$target
        只返回一个 JSON，不要任何其它说明，格式：{"x": <像素 x>, "y": <像素 y>}
        坐标是该元素中心点的绝对像素值，左上角为 (0,0)。
    """.trimIndent()

    private fun parseCoords(raw: String, w: Int, h: Int): PointF? {
        val xMatch = X_REGEX.find(raw) ?: return null
        val yMatch = Y_REGEX.find(raw) ?: return null
        var x = xMatch.groupValues[1].toFloatOrNull() ?: return null
        var y = yMatch.groupValues[1].toFloatOrNull() ?: return null

        // 兼容归一化（0-1 或 0-1000）坐标
        if (x <= 1f && y <= 1f) { x *= w; y *= h }
        else if (x > w * 1.2f || y > h * 1.2f) {
            val nx = (x / 1000f) * w
            val ny = (y / 1000f) * h
            if (nx <= w && ny <= h) { x = nx; y = ny }
        }
        if (x < 0 || y < 0 || x > w || y > h) return null
        return PointF(x, y)
    }

    companion object {
        private val X_REGEX = Regex("""["']?x["']?\s*[:=]\s*([0-9]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
        private val Y_REGEX = Regex("""["']?y["']?\s*[:=]\s*([0-9]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
    }
}
