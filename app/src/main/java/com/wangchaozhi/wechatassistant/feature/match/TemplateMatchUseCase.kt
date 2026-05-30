package com.wangchaozhi.wechatassistant.feature.match

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import com.wangchaozhi.wechatassistant.service.ServiceBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 本地模板匹配（OpenCV matchTemplate）。给定一张事先截好的模板小图，在当前屏幕里找到它，
 * 返回匹配中心点的像素坐标。不联网，速度快。
 *
 * 前提：模板图与屏幕来自同一 MediaProjection 截图，分辨率一致——matchTemplate 对缩放敏感，
 * 这个前提保证了匹配的可靠性。
 */
class TemplateMatchUseCase(
    @Suppress("unused") private val context: Context,
) {

    data class MatchResult(val point: PointF, val score: Double)

    suspend fun locate(templatePath: String, threshold: Float): Result<MatchResult> {
        if (!ensureOpenCv()) {
            return Result.failure(IllegalStateException("OpenCV 初始化失败。"))
        }
        if (!ServiceBus.captureReady.value) {
            return Result.failure(IllegalStateException("截图服务未启动。"))
        }
        val template = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(templatePath) }.getOrNull()
        } ?: return Result.failure(IllegalStateException("模板图片丢失：$templatePath"))

        val screen = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))

        return withContext(Dispatchers.Default) { match(screen, template, threshold) }
    }

    private fun match(screen: Bitmap, template: Bitmap, threshold: Float): Result<MatchResult> {
        if (template.width > screen.width || template.height > screen.height) {
            return Result.failure(
                IllegalStateException("模板比屏幕还大，无法匹配（模板需与截图同分辨率）。")
            )
        }
        val src = Mat()
        val tpl = Mat()
        val result = Mat()
        try {
            Utils.bitmapToMat(screen, src)
            Utils.bitmapToMat(template, tpl)
            // bitmapToMat 产出 RGBA，统一转 RGB，避免 alpha 干扰匹配。
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2RGB)

            Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            val score = mm.maxVal
            if (score < threshold) {
                return Result.failure(
                    IllegalStateException("未找到目标（最高置信度 ${"%.2f".format(score)} < 阈值 ${"%.2f".format(threshold)}）。")
                )
            }
            val cx = (mm.maxLoc.x + tpl.cols() / 2.0).toFloat()
            val cy = (mm.maxLoc.y + tpl.rows() / 2.0).toFloat()
            return Result.success(MatchResult(PointF(cx, cy), score))
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            src.release(); tpl.release(); result.release()
        }
    }

    companion object {
        @Volatile private var initialized = false

        /** 用 Maven 自带的 native 库做本地初始化，无需外部 OpenCV Manager。 */
        @Synchronized
        fun ensureOpenCv(): Boolean {
            if (initialized) return true
            initialized = OpenCVLoader.initLocal()
            return initialized
        }

        /** 把裁剪后的模板图保存到内部存储，返回绝对路径（PNG 无损，保证匹配精度）。 */
        fun saveTemplate(context: Context, bitmap: Bitmap): String? = try {
            val dir = File(context.filesDir, "templates").apply { if (!exists()) mkdirs() }
            val file = File(dir, "tpl_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}
