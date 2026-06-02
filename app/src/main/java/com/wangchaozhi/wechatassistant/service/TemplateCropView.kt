package com.wangchaozhi.wechatassistant.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.wangchaozhi.wechatassistant.util.CropRect
import com.wangchaozhi.wechatassistant.util.DragMode
import kotlin.math.min

/**
 * 悬浮裁剪层：把一张冻结的截图按比例显示，给一个可调裁剪框——四角拖拽改大小、框内拖动移动、框外变暗。
 * 裁剪框以原图像素坐标存（[CropRect]），crop() 直接用。供 OverlayService 在录制时截模板用。
 */
class TemplateCropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000") }

    private val dstRect = RectF()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private val handlePx = dp(22f)

    private var crop = CropRect.centered(bitmap.width.toFloat(), bitmap.height.toFloat())
    private var mode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private fun dp(v: Float) =
        v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val dispW = bitmap.width * scale
        val dispH = bitmap.height * scale
        offX = (w - dispW) / 2f
        offY = (h - dispH) / 2f
        dstRect.set(offX, offY, offX + dispW, offY + dispH)
    }

    // 原图坐标 → 屏幕坐标
    private fun sx(x: Float) = offX + x * scale
    private fun sy(y: Float) = offY + y * scale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, null, dstRect, null)
        val l = sx(crop.left); val t = sy(crop.top); val r = sx(crop.right); val b = sy(crop.bottom)
        // 框外四块变暗（限制在图片显示区域内）
        canvas.drawRect(dstRect.left, dstRect.top, dstRect.right, t, dimPaint)
        canvas.drawRect(dstRect.left, b, dstRect.right, dstRect.bottom, dimPaint)
        canvas.drawRect(dstRect.left, t, l, b, dimPaint)
        canvas.drawRect(r, t, dstRect.right, b, dimPaint)
        // 边框 + 四角把手
        canvas.drawRect(l, t, r, b, borderPaint)
        val hr = handlePx * 0.5f
        canvas.drawCircle(l, t, hr, handlePaint)
        canvas.drawCircle(r, t, hr, handlePaint)
        canvas.drawCircle(l, b, hr, handlePaint)
        canvas.drawCircle(r, b, hr, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val px = (event.x - offX) / scale
                val py = (event.y - offY) / scale
                mode = crop.hitTest(px, py, handlePx / scale)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) / scale
                val dy = (event.y - lastY) / scale
                crop = crop.apply(mode, dx, dy, bitmap.width.toFloat(), bitmap.height.toFloat())
                lastX = event.x
                lastY = event.y
                invalidate()
            }
        }
        return true
    }

    /** 按当前裁剪框裁出模板；过小返回 null。 */
    fun crop(): Bitmap? = crop.crop(bitmap)

    /** 当前裁剪框，坐标单位是原截图像素。 */
    fun cropRect(): CropRect = crop
}

/**
 * 实时屏幕框选层：不绘制截图，只在真实页面上盖灰蒙版与可拖拽选框。
 * cropRect() 返回屏幕像素坐标，后续再按该坐标裁当前截图，避免“先截图再框选”的映射误差。
 */
class LiveScreenCropView(context: Context) : View(context) {

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val handlePx = dp(22f)

    private var crop = CropRect(0f, 0f, 0f, 0f)
    private var mode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private fun dp(v: Float) =
        v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (crop.right <= crop.left || crop.bottom <= crop.top) {
            crop = CropRect.centered(w.toFloat(), h.toFloat())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = crop.left
        val t = crop.top
        val r = crop.right
        val b = crop.bottom
        canvas.drawRect(0f, 0f, width.toFloat(), t, dimPaint)
        canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, t, l, b, dimPaint)
        canvas.drawRect(r, t, width.toFloat(), b, dimPaint)
        canvas.drawRect(l, t, r, b, borderPaint)
        val hr = handlePx * 0.5f
        canvas.drawCircle(l, t, hr, handlePaint)
        canvas.drawCircle(r, t, hr, handlePaint)
        canvas.drawCircle(l, b, hr, handlePaint)
        canvas.drawCircle(r, b, hr, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = crop.hitTest(event.x, event.y, handlePx)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                crop = crop.apply(mode, dx, dy, width.toFloat(), height.toFloat())
                lastX = event.x
                lastY = event.y
                invalidate()
            }
        }
        return true
    }

    fun cropRect(): android.graphics.Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val l = crop.left.toInt().coerceIn(0, width)
        val t = crop.top.toInt().coerceIn(0, height)
        val r = crop.right.toInt().coerceIn(l, width)
        val b = crop.bottom.toInt().coerceIn(t, height)
        return android.graphics.Rect(l + loc[0], t + loc[1], r + loc[0], b + loc[1])
    }
}
