package com.wangchaozhi.wechatassistant.feature.match

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.wangchaozhi.wechatassistant.BuildConfig
import com.wangchaozhi.wechatassistant.data.model.ActionDefaults
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
import kotlin.math.roundToInt

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
    data class MultiMatchResult(
        val index: Int,
        val templatePath: String,
        val point: PointF,
        val box: Rect,
        val score: Double,
    )

    private data class Candidate(
        val index: Int,
        val templatePath: String,
        val point: PointF,
        val box: Rect,
        val score: Double,
        val found: Boolean,
    )

    /**
     * 在当前屏幕里找模板图。
     * [region] 非空时只在该区域(屏幕坐标，带少量余量)里匹配——模板本就是从这块截的，直接比这一小块，
     * 比全屏 matchTemplate 快得多；region 为空(老脚本没存位置)才退回全屏搜。
     * [debugName] 非空时把截屏连同命中框/分数存到 filesDir/<debugName>.png，便于 adb 核对。
     */
    suspend fun locate(
        templatePath: String,
        threshold: Float,
        region: Rect? = null,
        debugName: String? = null,
        downFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
        upFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_UP_FALLBACK_PX,
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

        return withContext(Dispatchers.Default) {
            match(screen, template, threshold, region, debugName, downFallbackBasePx, upFallbackBasePx)
        }
    }

    /** 在当前屏幕里匹配多张模板，返回分数最高且达到阈值的模板。index 为模板列表中的 0 基序号。 */
    suspend fun locateAny(
        templatePaths: List<String>,
        threshold: Float,
        region: Rect? = null,
        debugName: String? = null,
        downFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
        upFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_UP_FALLBACK_PX,
    ): Result<MultiMatchResult> {
        if (!ensureOpenCv()) {
            return Result.failure(IllegalStateException("OpenCV 初始化失败。"))
        }
        if (!ServiceBus.captureReady.value) {
            return Result.failure(IllegalStateException("截图服务未启动。"))
        }
        val paths = templatePaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            return Result.failure(IllegalStateException("未设置模板图。"))
        }

        val screen = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))

        return locateAnyIn(screen, paths, threshold, region, debugName, downFallbackBasePx, upFallbackBasePx)
    }

    /** 在指定截图上匹配多张模板；调用方负责管理 [screen] 的生命周期。 */
    suspend fun locateAnyIn(
        screen: Bitmap,
        templatePaths: List<String>,
        threshold: Float,
        region: Rect? = null,
        debugName: String? = null,
        downFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
        upFallbackBasePx: Int = ActionDefaults.DEFAULT_IMAGE_UP_FALLBACK_PX,
    ): Result<MultiMatchResult> {
        if (!ensureOpenCv()) {
            return Result.failure(IllegalStateException("OpenCV 初始化失败。"))
        }
        val paths = templatePaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            return Result.failure(IllegalStateException("未设置模板图。"))
        }
        val templates = withContext(Dispatchers.IO) {
            paths.mapIndexedNotNull { index, path ->
                val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (bmp == null) null else Triple(index, path, bmp)
            }
        }
        if (templates.isEmpty()) {
            return Result.failure(IllegalStateException("模板图片都已丢失。"))
        }

        return withContext(Dispatchers.Default) {
            try {
                val best = templates
                    .mapNotNull { (index, path, template) ->
                        matchCandidate(
                            screen,
                            template,
                            threshold,
                            region,
                            index,
                            path,
                            downFallbackBasePx,
                            upFallbackBasePx,
                        ).getOrNull()
                    }
                    .maxByOrNull { it.score }

                if (debugName != null && best != null) {
                    saveMatchDebug(
                        screen,
                        best.box,
                        best.score,
                        threshold.toDouble(),
                        best.found,
                        debugName,
                    )
                }
                if (best == null) {
                    Result.failure(IllegalStateException("没有可匹配的模板。"))
                } else if (!best.found) {
                    Result.failure(
                        IllegalStateException(
                            "未找到目标（最高置信度 ${"%.2f".format(best.score)} < 阈值 ${"%.2f".format(threshold)}）。"
                        )
                    )
                } else {
                    Result.success(MultiMatchResult(best.index, best.templatePath, best.point, best.box, best.score))
                }
            } finally {
                templates.forEach { it.third.recycle() }
            }
        }
    }

    private fun match(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
        region: Rect?,
        debugName: String?,
        downFallbackBasePx: Int,
        upFallbackBasePx: Int,
    ): Result<MatchResult> {
        if (template.width > screen.width || template.height > screen.height) {
            return Result.failure(
                IllegalStateException("模板比屏幕还大，无法匹配（模板需与截图同分辨率）。")
            )
        }
        val candidate = matchCandidate(screen, template, threshold, region, 0, "", downFallbackBasePx, upFallbackBasePx).getOrElse {
            return Result.failure(it)
        }
        if (debugName != null) {
            saveMatchDebug(
                screen,
                candidate.box,
                candidate.score,
                threshold.toDouble(),
                candidate.found,
                debugName,
            )
        }
        if (!candidate.found) {
            return Result.failure(
                IllegalStateException("未找到目标（最高置信度 ${"%.2f".format(candidate.score)} < 阈值 ${"%.2f".format(threshold)}）。")
            )
        }
        return Result.success(MatchResult(candidate.point, candidate.score))
    }

    private fun matchCandidate(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
        region: Rect?,
        index: Int,
        templatePath: String,
        downFallbackBasePx: Int,
        upFallbackBasePx: Int,
    ): Result<Candidate> {
        if (template.width > screen.width || template.height > screen.height) {
            return Result.failure(
                IllegalStateException("模板比屏幕还大，无法匹配（模板需与截图同分辨率）。")
            )
        }
        // 搜索范围：有 region 先严格只裁该区域；没命中时，再向下补一段容错区域。
        // 朋友圈刷新后内容常向下回弹一截，两段式比一开始就大范围搜索更快，也避免正常命中漂移。
        var ox = 0   // 搜索区域相对整屏的偏移(位图像素)
        var oy = 0
        var search = screen
        var fallbackSearch: Bitmap? = null
        var fallbackOx = 0
        var fallbackOy = 0
        var upSearch: Bitmap? = null
        var upOx = 0
        var upOy = 0
        if (region != null && region.right > region.left && region.bottom > region.top) {
            val dm = context.resources.displayMetrics
            val sx = screen.width.toFloat() / dm.widthPixels.coerceAtLeast(1)
            val sy = screen.height.toFloat() / dm.heightPixels.coerceAtLeast(1)
            val x0 = (region.left * sx).toInt().coerceIn(0, screen.width - 1)
            val y0 = (region.top * sy).toInt().coerceIn(0, screen.height - 1)
            val x1 = (region.right * sx).toInt().coerceIn(x0 + 1, screen.width)
            val y1 = (region.bottom * sy).toInt().coerceIn(y0 + 1, screen.height)
            if (x1 - x0 >= template.width && y1 - y0 >= template.height) {
                ox = x0; oy = y0
                search = Bitmap.createBitmap(screen, x0, y0, x1 - x0, y1 - y0)
                val downFallbackPx = (downFallbackBasePx.coerceAtLeast(0) * screen.height / BASE_SCREEN_HEIGHT)
                    .roundToInt()
                val y2 = (y1 + downFallbackPx).coerceAtMost(screen.height)
                if (y2 - y0 > y1 - y0 && y2 - y0 >= template.height) {
                    fallbackOx = x0
                    fallbackOy = y0
                    fallbackSearch = Bitmap.createBitmap(screen, x0, y0, x1 - x0, y2 - y0)
                }
                // 上方容错：区域上沿向上扩一段（下沿仍为 y1），作为最后一档兜底。
                val upFallbackPx = (upFallbackBasePx.coerceAtLeast(0) * screen.height / BASE_SCREEN_HEIGHT)
                    .roundToInt()
                val yUp = (y0 - upFallbackPx).coerceAtLeast(0)
                if (y1 - yUp > y1 - y0 && y1 - yUp >= template.height) {
                    upOx = x0
                    upOy = yUp
                    upSearch = Bitmap.createBitmap(screen, x0, yUp, x1 - x0, y1 - yUp)
                }
            }
        }

        fun runMatch(searchBmp: Bitmap, searchOx: Int, searchOy: Int): Candidate {
            val src = Mat()
            val tpl = Mat()
            val result = Mat()
            try {
                Utils.bitmapToMat(searchBmp, src)
                Utils.bitmapToMat(template, tpl)
                // bitmapToMat 产出 RGBA，统一转 RGB，避免 alpha 干扰匹配。
                Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
                Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2RGB)

                Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            val score = mm.maxVal
            val found = score >= threshold
                val locX = mm.maxLoc.x.toInt() + searchOx
                val locY = mm.maxLoc.y.toInt() + searchOy
            val cx = (locX + tpl.cols() / 2.0).toFloat()
            val cy = (locY + tpl.rows() / 2.0).toFloat()
                return Candidate(
                    index = index,
                    templatePath = templatePath,
                    point = PointF(cx, cy),
                    box = Rect(locX, locY, locX + tpl.cols(), locY + tpl.rows()),
                    score = score,
                    found = found,
                )
            } finally {
                src.release(); tpl.release(); result.release()
            }
        }

        try {
            // 先紧后松、分方向兜底：严格区域 → 向下扩 → 向上扩，每档仅在仍未命中时才跑，取分最高者。
            var best = runMatch(search, ox, oy)
            if (!best.found) {
                fallbackSearch?.let { runMatch(it, fallbackOx, fallbackOy) }
                    ?.let { if (it.score > best.score) best = it }
            }
            if (!best.found) {
                upSearch?.let { runMatch(it, upOx, upOy) }
                    ?.let { if (it.score > best.score) best = it }
            }
            return Result.success(
                Candidate(
                    index = best.index,
                    templatePath = best.templatePath,
                    point = best.point,
                    box = best.box,
                    score = best.score,
                    found = best.found,
                )
            )
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            if (search !== screen) search.recycle()
            fallbackSearch?.recycle()
            upSearch?.recycle()
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
        if (!BuildConfig.DEBUG) return
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
        private const val BASE_SCREEN_HEIGHT = 3_200f

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

        fun splitTemplatePaths(raw: String?): List<String> =
            raw.orEmpty()
                .split('\n', '\r', '\t', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        fun appendTemplatePath(raw: String?, newPath: String): String =
            (splitTemplatePaths(raw) + newPath)
                .distinct()
                .joinToString("\n")

        fun thresholdToPrecision(threshold: Float): Int =
            (threshold.coerceIn(0f, 1f) * 100f).roundToInt()

        fun precisionToThreshold(precision: Int): Float =
            precision.coerceIn(0, 100) / 100f
    }
}
