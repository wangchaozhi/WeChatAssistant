package com.wangchaozhi.wechatassistant.feature.match

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 用 OpenCV 判断两块区域图是否「变了」。
 * 两图先转灰度并缩放，再在小范围内滑动对齐，取归一化互相关 (TM_CCOEFF_NORMED) 的最高值。
 * 1.0=完全一致，越低差异越大；允许轻微上下左右位移，减少页面滚动/重排造成的误判。
 */
object RegionDiff {

    private const val SIZE = 128
    private const val SHIFT = 32

    data class MatchResult(
        val score: Double,
        val offsetX: Int,
        val offsetY: Int,
    )

    /** 返回相关度 [-1,1]，1 表示几乎相同；OpenCV 不可用或出错时返回 null。 */
    fun similarity(a: Bitmap, b: Bitmap): Double? = compare(a, b)?.score

    /** 返回最佳相关度，以及在 128x128 缩放坐标系里的最佳对齐偏移。 */
    fun compare(a: Bitmap, b: Bitmap): MatchResult? {
        if (!TemplateMatchUseCase.ensureOpenCv()) return null
        val ma = Mat()
        val mb = Mat()
        val padded = Mat()
        val result = Mat()
        return try {
            Utils.bitmapToMat(a, ma)
            Utils.bitmapToMat(b, mb)
            Imgproc.cvtColor(ma, ma, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(mb, mb, Imgproc.COLOR_RGBA2GRAY)
            val size = Size(SIZE.toDouble(), SIZE.toDouble())
            Imgproc.resize(ma, ma, size)
            Imgproc.resize(mb, mb, size)

            // 给实时图留出可滑动边界。BORDER_REPLICATE 避免边缘补黑带来额外差异。
            Core.copyMakeBorder(
                mb,
                padded,
                SHIFT,
                SHIFT,
                SHIFT,
                SHIFT,
                Core.BORDER_REPLICATE,
            )
            Imgproc.matchTemplate(padded, ma, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            MatchResult(
                score = mm.maxVal,
                offsetX = mm.maxLoc.x.toInt() - SHIFT,
                offsetY = mm.maxLoc.y.toInt() - SHIFT,
            )
        } catch (t: Throwable) {
            null
        } finally {
            ma.release(); mb.release(); padded.release(); result.release()
        }
    }
}
