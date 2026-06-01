package com.wangchaozhi.wechatassistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * 全屏透明录制层：接管触摸，把每一个完整手势（down→move→up）回调出去。
 * 坐标用 [MotionEvent.getRawX]/[MotionEvent.getRawY]（屏幕像素），与无障碍 dispatchGesture 一致。
 *
 * 录制层之上还盖着控制面板窗口；面板上的点击由面板自己消费，落到本层的只会是面板之外的手势。
 * 「边录边放」注入期间，Service 会把本窗口切成 FLAG_NOT_TOUCHABLE 放行，此时本 View 收不到事件。
 */
@SuppressLint("ViewConstructor")
class RecordOverlayView(
    context: Context,
    private val onGesture: (ServiceBus.RawTouch) -> Unit,
) : View(context) {

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    // 绘制用的 View 本地坐标（rawX/rawY 是屏幕坐标，全屏层一般相等，但本地坐标更稳妥）。
    private var curLocalX = 0f
    private var curLocalY = 0f
    private var downAt = 0L
    private var tracking = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 64, 196, 255)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 64, 196, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    init {
        // 透明背景：底层真实 App 透过本层可见。
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                curX = downX
                curY = downY
                curLocalX = event.x
                curLocalY = event.y
                downAt = SystemClock.uptimeMillis()
                tracking = true
                path.reset()
                path.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                curX = event.rawX
                curY = event.rawY
                curLocalX = event.x
                curLocalY = event.y
                path.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                tracking = false
                curX = event.rawX
                curY = event.rawY
                val dur = (SystemClock.uptimeMillis() - downAt).coerceAtLeast(60L)
                path.reset()
                invalidate()
                onGesture(
                    ServiceBus.RawTouch(
                        startX = downX,
                        startY = downY,
                        endX = curX,
                        endY = curY,
                        durationMs = dur,
                        timestamp = downAt,
                        source = ServiceBus.RawTouchSource.OVERLAY,
                    )
                )
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                path.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 把整层声明为系统手势排除区，争取接管边缘返回滑动。
        // 注意：系统对每条边的排除高度有 ~200dp 上限，可能盖不满整条边。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!tracking) return
        // 实时反馈：滑动画轨迹，落点画圆点（注入前真实 App 还没响应，靠这个让用户看到自己点了哪）。
        canvas.drawPath(path, linePaint)
        canvas.drawCircle(curLocalX, curLocalY, 22f, dotPaint)
    }
}
