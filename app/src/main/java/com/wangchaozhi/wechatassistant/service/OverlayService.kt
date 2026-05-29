package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.ui.MainActivity
import com.wangchaozhi.wechatassistant.util.ShizukuManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var panelView: View? = null
    private var recording = false
    private var recBtn: Button? = null
    private var statusLabel: TextView? = null
    private val recordedTouches = mutableListOf<ServiceBus.RawTouch>()
    private val shizukuReader by lazy { ShizukuTouchReader(this) }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        showPanel()
        ServiceBus.overlayReady.value = true
        lifecycleScope.launch {
            ServiceBus.recordedTap.collect { tap ->
                if (recording && acceptTap(tap)) {
                    recordedTouches += tap
                    refreshStatus()
                }
            }
        }
        lifecycleScope.launch {
            ServiceBus.playerState.collect { refreshStatus() }
        }
        lifecycleScope.launch {
            ServiceBus.overlayHidden.collect { hidden ->
                panelView?.post {
                    panelView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                }
            }
        }
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
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(180, 30, 30, 30))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(ctx).apply {
            text = "连点"
            setTextColor(Color.WHITE)
            textSize = 13f
            minWidth = dp(96)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(8), 0)
        }
        statusLabel = label
        val btnRec = Button(ctx).apply {
            text = "录制"
            setOnClickListener { toggleRecording(this) }
        }
        recBtn = btnRec
        val extraActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val btnMore = Button(ctx).apply {
            text = "⋮"
            minWidth = dp(44)
            setOnClickListener {
                val expanded = extraActions.visibility != View.VISIBLE
                extraActions.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "×" else "⋮"
            }
        }
        val btnPlay = Button(ctx).apply {
            text = "▶"
            setOnClickListener { playMostRecent() }
        }
        val btnAi = Button(ctx).apply {
            text = "AI"
            setOnClickListener {
                if (!ServiceBus.captureReady.value) {
                    Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val prompt = App.from(ctx).settingsRepo.defaultPrompt
                Toast.makeText(ctx, "正在请求 AI…", Toast.LENGTH_SHORT).show()
                ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.TakeAndAsk(prompt))
            }
        }
        val btnStop = Button(ctx).apply {
            text = "停止"
            setOnClickListener {
                if (ServiceBus.playerState.value !is ServiceBus.PlayerState.Playing) {
                    Toast.makeText(ctx, "当前没有正在播放的脚本", Toast.LENGTH_SHORT).show()
                } else {
                    ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Stop)
                }
            }
        }
        topRow.addView(label)
        topRow.addView(btnRec)
        topRow.addView(btnMore)
        extraActions.addView(btnPlay)
        extraActions.addView(btnAi)
        extraActions.addView(btnStop)
        container.addView(topRow)
        container.addView(extraActions)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                MotionEvent.ACTION_OUTSIDE -> {
                    false
                }
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

    private fun acceptTap(tap: ServiceBus.RawTouch): Boolean {
        val last = recordedTouches.lastOrNull() ?: return true
        val dt = tap.timestamp - last.timestamp
        if (dt !in 0..150) return true
        val dx = abs(tap.startX - last.startX)
        val dy = abs(tap.startY - last.startY)
        if (dx > 24f || dy > 24f) return true
        return tap.source == last.source
    }

    private fun toggleRecording(btn: Button) {
        if (!recording) {
            ShizukuManager.refresh()
            val shizuku = ShizukuManager.state.value
            if (!shizuku.available) {
                Toast.makeText(this, "请先启动 Shizuku 后再录制", Toast.LENGTH_SHORT).show()
                return
            }
            if (!shizuku.granted) {
                Toast.makeText(this, "请先在设置中授权 Shizuku", Toast.LENGTH_SHORT).show()
                return
            }
            recording = true
            recordedTouches.clear()
            btn.text = "完成"
            ServiceBus.recordingMode.value = true
            ServiceBus.shizukuRecording.value = true
            shizukuReader.start(lifecycleScope) { message ->
                if (recording && recordedTouches.isEmpty()) {
                    recording = false
                    btn.text = "录制"
                    ServiceBus.recordingMode.value = false
                    ServiceBus.shizukuRecording.value = false
                    refreshStatus()
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StartRecording)
        } else {
            recording = false
            btn.text = "录制"
            ServiceBus.recordingMode.value = false
            ServiceBus.shizukuRecording.value = false
            shizukuReader.stop()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StopRecording)
            persistRecording()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        if (recording) {
            val captured = recordedTouches.size
            statusLabel?.text = "录制中(Shizuku) · $captured 步"
            return
        }
        val st = ServiceBus.playerState.value
        statusLabel?.text = when (st) {
            is ServiceBus.PlayerState.Playing ->
                "播放中 · ${st.stepIndex + 1}/${st.totalSteps}"
            ServiceBus.PlayerState.Idle -> "连点"
        }
    }

    private fun persistRecording() {
        val touches = recordedTouches.toList().sortedBy { it.timestamp }
        recordedTouches.clear()
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

    private fun playMostRecent() {
        if (!ServiceBus.accessibilityReady.value) {
            Toast.makeText(this, "请先开启「无障碍」服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (ServiceBus.playerState.value is ServiceBus.PlayerState.Playing) {
            Toast.makeText(this, "正在播放中，先停止", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val list = App.from(this@OverlayService).scriptRepo.observeScripts().first()
            val latest = list.firstOrNull()
            if (latest == null) {
                Toast.makeText(this@OverlayService, "还没有脚本", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val data = App.from(this@OverlayService).scriptRepo.load(latest.id)
            if (data == null || data.actions.isEmpty()) {
                Toast.makeText(this@OverlayService, "脚本「${latest.name}」没有动作", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ServiceBus.playerCmd.emit(ServiceBus.PlayerCmd.Play(latest.id))
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    override fun onDestroy() {
        super.onDestroy()
        shizukuReader.stop()
        ServiceBus.overlayReady.value = false
        ServiceBus.recordingMode.value = false
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        recBtn = null
        statusLabel = null
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
