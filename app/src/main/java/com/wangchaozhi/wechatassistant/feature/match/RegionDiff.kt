package com.wangchaozhi.wechatassistant.feature.match

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 用 OpenCV 判断两块区域图是否「变了」：归一化互相关 (TM_CCOEFF_NORMED)。
 * 两图先转灰度并缩放到同一尺寸，matchTemplate 同尺寸即得一个相关系数：
 * 1.0=完全一致，越低差异越大。对亮度/轻微重渲染不敏感，对内容/结构变化敏感。
 */
object RegionDiff {

    private const val SIZE = 128

    /** 返回相关度 [-1,1]，1 表示几乎相同；OpenCV 不可用或出错时返回 null。 */
    fun similarity(a: Bitmap, b: Bitmap): Double? {
        if (!TemplateMatchUseCase.ensureOpenCv()) return null
        val ma = Mat()
        val mb = Mat()
        val result = Mat()
        return try {
            Utils.bitmapToMat(a, ma)
            Utils.bitmapToMat(b, mb)
            Imgproc.cvtColor(ma, ma, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(mb, mb, Imgproc.COLOR_RGBA2GRAY)
            val size = Size(SIZE.toDouble(), SIZE.toDouble())
            Imgproc.resize(ma, ma, size)
            Imgproc.resize(mb, mb, size)
            Imgproc.matchTemplate(ma, mb, result, Imgproc.TM_CCOEFF_NORMED)
            Core.minMaxLoc(result).maxVal
        } catch (t: Throwable) {
            null
        } finally {
            ma.release(); mb.release(); result.release()
        }
    }
}
