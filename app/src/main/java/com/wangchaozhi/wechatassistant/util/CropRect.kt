package com.wangchaozhi.wechatassistant.util

import android.graphics.Bitmap
import kotlin.math.abs

enum class DragMode { NONE, MOVE, TL, TR, BL, BR }

/**
 * 裁剪框，坐标单位是「原图像素」。四角可拖拽改大小、整体可移动。与 UI 框架无关，
 * Compose 弹窗与经典 View 覆盖层共用同一套几何逻辑。
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 判断触点（原图坐标）抓住了哪个角 / 框内 / 外部。tol 是把手命中半径（原图坐标）。 */
    fun hitTest(px: Float, py: Float, tol: Float): DragMode {
        fun near(ax: Float, ay: Float) = abs(px - ax) <= tol && abs(py - ay) <= tol
        return when {
            near(left, top) -> DragMode.TL
            near(right, top) -> DragMode.TR
            near(left, bottom) -> DragMode.BL
            near(right, bottom) -> DragMode.BR
            px in left..right && py in top..bottom -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    /** 按拖动增量 (dx, dy) 调整裁剪框；bw/bh 是原图宽高，用于边界与最小尺寸约束。 */
    fun apply(mode: DragMode, dx: Float, dy: Float, bw: Float, bh: Float): CropRect = when (mode) {
        DragMode.NONE -> this
        DragMode.MOVE -> {
            val w = right - left
            val h = bottom - top
            val nl = (left + dx).coerceIn(0f, bw - w)
            val nt = (top + dy).coerceIn(0f, bh - h)
            CropRect(nl, nt, nl + w, nt + h)
        }
        DragMode.TL -> copy(
            left = (left + dx).coerceIn(0f, right - MIN),
            top = (top + dy).coerceIn(0f, bottom - MIN),
        )
        DragMode.TR -> copy(
            right = (right + dx).coerceIn(left + MIN, bw),
            top = (top + dy).coerceIn(0f, bottom - MIN),
        )
        DragMode.BL -> copy(
            left = (left + dx).coerceIn(0f, right - MIN),
            bottom = (bottom + dy).coerceIn(top + MIN, bh),
        )
        DragMode.BR -> copy(
            right = (right + dx).coerceIn(left + MIN, bw),
            bottom = (bottom + dy).coerceIn(top + MIN, bh),
        )
    }

    fun crop(bmp: Bitmap): Bitmap? {
        val l = left.toInt().coerceIn(0, bmp.width - 1)
        val t = top.toInt().coerceIn(0, bmp.height - 1)
        val r = right.toInt().coerceIn(l + 1, bmp.width)
        val b = bottom.toInt().coerceIn(t + 1, bmp.height)
        val w = r - l
        val h = b - t
        if (w < 8 || h < 8) return null
        return runCatching { Bitmap.createBitmap(bmp, l, t, w, h) }.getOrNull()
    }

    companion object {
        const val MIN = 24f

        /** 居中、占图一半的默认裁剪框。 */
        fun centered(bw: Float, bh: Float) =
            CropRect(bw * 0.25f, bh * 0.25f, bw * 0.75f, bh * 0.75f)
    }
}
