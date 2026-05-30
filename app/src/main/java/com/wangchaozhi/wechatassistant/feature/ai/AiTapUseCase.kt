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
        App.from(context).appendLog(
            "AITAP w=${bitmap.width} h=${bitmap.height} point=$point raw=${raw.replace("\n", " ").take(180)}"
        )
        if (point == null) {
            return Result.failure(IllegalStateException("未能从回答中解析坐标：${raw.take(120)}"))
        }
        return Result.success(point)
    }

    private fun buildPrompt(target: String, w: Int, h: Int): String = """
        屏幕分辨率是 ${w}x${h} 像素，左上角为 (0,0)。请找到屏幕上的元素：$target
        只返回一行 JSON：{"x": 整数, "y": 整数}
        x、y 是该元素中心点的绝对像素值，必须是单个整数，不要返回数组、范围或边界框，不要任何解释。
    """.trimIndent()

    private fun parseCoords(raw: String, w: Int, h: Int): PointF? {
        // 模型常把坐标返回成数组/边界框（如 "x":[a,b]）。把每个轴上的所有数字求平均，
        // 单点取其本身、边界框取中心，都是要点击的位置。
        val xs = valuesFor(raw, "x")
        val ys = valuesFor(raw, "y")
        if (xs.isEmpty() || ys.isEmpty()) return null
        var x = xs.average().toFloat()
        var y = ys.average().toFloat()

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

    /** 取某个轴(key)后面跟着的所有数字：兼容 `"x": 840`、`"x":[840,120]`、`"x": 840, 120` 等。 */
    private fun valuesFor(raw: String, key: String): List<Float> {
        val seg = Regex("""["']?$key["']?\s*[:=]\s*(\[[^\]]*]|[-0-9.,\s]+)""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1) ?: return emptyList()
        return NUM_REGEX.findAll(seg).mapNotNull { it.value.toFloatOrNull() }.toList()
    }

    companion object {
        private val NUM_REGEX = Regex("""-?[0-9]+\.?[0-9]*""")
    }
}
