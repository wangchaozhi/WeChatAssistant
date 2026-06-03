package com.wangchaozhi.wechatassistant.feature.match

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
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
    private val context: Context,
) {

    data class MatchResult(val point: PointF, val score: Double)

    /**
     * 在当前屏幕里找模板图。
     * [region] 非空时只在该区域(屏幕坐标，带少量余量)里匹配——模板本就是从这块截的，直接比这一小块，
     * 比全屏 matchTemplate 快得多；region 为空(老脚本/相册选图没存位置)才退回全屏搜。
     * [debugName] 非空时把截屏连同命中框/分数存到 filesDir/<debugName>.png，便于 adb 核对。
     */
    suspend fun locate(
        templatePath: String,
        threshold: Float,
        region: Rect? = null,
        debugName: String? = null,
    ): Result<MatchResult> {
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

        return withContext(Dispatchers.Default) { match(screen, template, threshold, region, debugName) }
    }

    private fun match(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
        region: Rect?,
        debugName: String?,
    ): Result<MatchResult> {
        if (template.width > screen.width || template.height > screen.height) {
            return Result.failure(
                IllegalStateException("模板比屏幕还大，无法匹配（模板需与截图同分辨率）。")
            )
        }
        // 搜索范围：有 region 就只裁该区域(缩放到位图像素，带少量余量容忍轻微位移)，否则整屏。
        var ox = 0   // 搜索区域相对整屏的偏移(位图像素)
        var oy = 0
        var search = screen
        if (region != null && region.right > region.left && region.bottom > region.top) {
            val dm = context.resources.displayMetrics
            val sx = screen.width.toFloat() / dm.widthPixels.coerceAtLeast(1)
            val sy = screen.height.toFloat() / dm.heightPixels.coerceAtLeast(1)
            val padX = (template.width * 0.15f).coerceAtLeast(8f)
            val padY = (template.height * 0.15f).coerceAtLeast(8f)
            val x0 = (region.left * sx - padX).toInt().coerceIn(0, screen.width - 1)
            val y0 = (region.top * sy - padY).toInt().coerceIn(0, screen.height - 1)
            val x1 = (region.right * sx + padX).toInt().coerceIn(x0 + 1, screen.width)
            val y1 = (region.bottom * sy + padY).toInt().coerceIn(y0 + 1, screen.height)
            if (x1 - x0 >= template.width && y1 - y0 >= template.height) {
                ox = x0; oy = y0
                search = Bitmap.createBitmap(screen, x0, y0, x1 - x0, y1 - y0)
            }
        }
        val src = Mat()
        val tpl = Mat()
        val result = Mat()
        try {
            Utils.bitmapToMat(search, src)
            Utils.bitmapToMat(template, tpl)
            // bitmapToMat 产出 RGBA，统一转 RGB，避免 alpha 干扰匹配。
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2RGB)

            Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            val score = mm.maxVal
            val found = score >= threshold
            val locX = mm.maxLoc.x.toInt() + ox
            val locY = mm.maxLoc.y.toInt() + oy
            if (debugName != null) {
                saveMatchDebug(
                    screen,
                    Rect(locX, locY, locX + tpl.cols(), locY + tpl.rows()),
                    score, threshold.toDouble(), found, debugName,
                )
            }
            if (!found) {
                return Result.failure(
                    IllegalStateException("未找到目标（最高置信度 ${"%.2f".format(score)} < 阈值 ${"%.2f".format(threshold)}）。")
                )
            }
            val cx = (locX + tpl.cols() / 2.0).toFloat()
            val cy = (locY + tpl.rows() / 2.0).toFloat()
            return Result.success(MatchResult(PointF(cx, cy), score))
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            src.release(); tpl.release(); result.release()
            if (search !== screen) search.recycle()
        }
    }

    /** 把屏幕连同「最佳匹配框 + 分数」画出来存盘，命中画绿框、未命中画红框。 */
    private fun saveMatchDebug(
        screen: Bitmap,
        box: Rect,
        score: Double,
        threshold: Double,
        found: Boolean,
        debugName: String,
    ) {
        runCatching {
            val out = screen.copy(Bitmap.Config.ARGB_8888, true) ?: return
            val canvas = Canvas(out)
            val boxColor = if (found) Color.rgb(0, 220, 0) else Color.rgb(255, 64, 64)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = boxColor
                style = Paint.Style.STROKE
                strokeWidth = (out.width / 200f).coerceAtLeast(3f)
            }
            canvas.drawRect(box, stroke)
            val textSize = (out.width / 28f).coerceAtLeast(18f)
            val label = "${if (found) "found" else "miss"} score=${"%.3f".format(score)} thr=${"%.2f".format(threshold)}"
            val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = boxColor
                this.textSize = textSize
            }
            canvas.drawRect(0f, 0f, out.width.toFloat(), textSize * 1.6f, bg)
            canvas.drawText(label, textSize * 0.3f, textSize * 1.2f, text)
            val file = File(context.filesDir, "$debugName.png")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
            out.recycle()
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
