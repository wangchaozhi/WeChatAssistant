package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.ui.MainActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var panelView: View? = null
    private var captureView: View? = null
    private var recording = false
    private val recordedTouches = mutableListOf<ServiceBus.RawTouch>()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        showPanel()
        ServiceBus.overlayReady.value = true
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, App.CHANNEL_OVERLAY)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showPanel() {
        if (panelView != null) return
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(180, 30, 30, 30))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val label = TextView(ctx).apply {
            text = "连点"
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(8), 0)
        }
        val btnRec = Button(ctx).apply {
            text = "录制"
            setOnClickListener { toggleRecording(this) }
        }
        val btnAi = Button(ctx).apply {
            text = "AI"
            setOnClickListener {
                val prompt = App.from(ctx).settingsRepo.defaultPrompt
                ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.TakeAndAsk(prompt))
            }
        }
        val btnStop = Button(ctx).apply {
            text = "停止"
            setOnClickListener { ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Stop) }
        }
        container.addView(label)
        container.addView(btnRec)
        container.addView(btnAi)
        container.addView(btnStop)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(80)
        }

        attachDrag(container, params)
        wm.addView(container, params)
        panelView = container
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var downRawX = 0f; var downRawY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downRawX = e.rawX; downRawY = e.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downRawX).toInt()
                    params.y = startY + (e.rawY - downRawY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleRecording(btn: Button) {
        if (!recording) {
            recording = true
            recordedTouches.clear()
            btn.text = "完成"
            showCaptureLayer()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StartRecording)
        } else {
            recording = false
            btn.text = "录制"
            hideCaptureLayer()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StopRecording)
            persistRecording()
        }
    }

    private fun persistRecording() {
        val touches = recordedTouches.toList()
        if (touches.isEmpty()) return
        val first = touches.first().timestamp
        val actions = touches.mapIndexed { i, t ->
            val dx = t.endX - t.startX
            val dy = t.endY - t.startY
            val type = when {
                hypot(dx, dy) > 20f -> ActionType.SWIPE
                t.durationMs > 500 -> ActionType.LONG_PRESS
                else -> ActionType.TAP
            }
            val prev = if (i == 0) first else touches[i - 1].timestamp + touches[i - 1].durationMs
            Action(
                scriptId = 0,
                index = i,
                type = type,
                startX = t.startX,
                startY = t.startY,
                endX = t.endX,
                endY = t.endY,
                durationMs = t.durationMs,
                delayBeforeMs = (t.timestamp - prev).coerceAtLeast(0),
            )
        }
        val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
        val script = Script(name = name)
        lifecycleScope.launch {
            App.from(this@OverlayService).scriptRepo.save(script, actions)
        }
    }

    private fun showCaptureLayer() {
        if (captureView != null) return
        val view = RecordingView(this) { raw ->
            recordedTouches += raw
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.RecordedAction(raw))
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(view, lp)
        captureView = view
        bringPanelToFront()
    }

    private fun bringPanelToFront() {
        val v = panelView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        wm.removeView(v)
        wm.addView(v, lp)
    }

    private fun hideCaptureLayer() {
        captureView?.let { wm.removeView(it) }
        captureView = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    override fun onDestroy() {
        super.onDestroy()
        ServiceBus.overlayReady.value = false
        captureView?.let { wm.removeView(it) }
        panelView?.let { wm.removeView(it) }
        captureView = null; panelView = null
    }

    companion object {
        private const val NOTIF_ID = 0x10A2
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}

private class RecordingView(
    context: Context,
    private val onTouch: (ServiceBus.RawTouch) -> Unit,
) : View(context) {

    private var downAt: Long = 0
    private var downX = 0f
    private var downY = 0f

    init {
        setBackgroundColor(Color.argb(40, 0, 150, 255))
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = e.rawX; downY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val dur = now - downAt
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (hypot(dx, dy) < 5 && dur < 60) return true
                onTouch(
                    ServiceBus.RawTouch(
                        startX = downX, startY = downY,
                        endX = e.rawX, endY = e.rawY,
                        durationMs = dur.coerceAtLeast(50),
                        timestamp = downAt,
                    )
                )
            }
        }
        return true
    }
}
