package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var playStopBtn: Button? = null
    private var scriptPickerView: View? = null
    private var bubble: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var bubbleActions: LinearLayout? = null
    private var bubbleRenameBtn: Button? = null
    private var bubbleDeleteBtn: Button? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { bubble?.visibility = View.GONE }
    private val recordedTouches = mutableListOf<ServiceBus.RawTouch>()
    private val recordedPastes = mutableListOf<Long>()
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
        lifecycleScope.launch {
            ServiceBus.lastAiResult.collect { res ->
                if (res != null) panelView?.post { renderAiResult(res) }
            }
        }
        lifecycleScope.launch {
            ServiceBus.pasteResult.collect { ok ->
                val text = if (ok) "已粘贴" else "找不到可粘贴的输入框"
                panelView?.post {
                    Toast.makeText(this@OverlayService, text, Toast.LENGTH_SHORT).show()
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
        val btnHome = Button(ctx).apply {
            text = "↗"
            minWidth = dp(44)
            setOnClickListener { launchHome(null) }
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
        val btnPlayStop = Button(ctx).apply {
            text = "▶"
            setOnClickListener {
                if (ServiceBus.playerState.value is ServiceBus.PlayerState.Playing) {
                    ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Stop)
                } else {
                    if (!ServiceBus.accessibilityReady.value) {
                        Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch { showScriptPicker(this@apply) }
                }
            }
        }
        playStopBtn = btnPlayStop
        val btnAi = Button(ctx).apply {
            text = "AI"
            setOnClickListener {
                if (!ServiceBus.captureReady.value) {
                    Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val prompt = App.from(ctx).settingsRepo.defaultPrompt
                showBubbleLoading()
                ServiceBus.lastAiResult.value = null
                ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.TakeAndAsk(prompt))
            }
        }
        val btnPaste = Button(ctx).apply {
            text = "粘贴"
            setOnClickListener {
                if (recording) recordedPastes += System.currentTimeMillis()
                if (!ServiceBus.accessibilityReady.value) {
                    Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                ServiceBus.pasteCmd.tryEmit(Unit)
            }
        }
        topRow.addView(label)
        topRow.addView(btnRec)
        topRow.addView(btnHome)
        topRow.addView(btnMore)
        extraActions.addView(btnPlayStop)
        extraActions.addView(btnAi)
        extraActions.addView(btnPaste)
        container.addView(topRow)
        container.addView(extraActions)
        container.addView(buildBubble(ctx))

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

    private fun buildBubble(ctx: Context): LinearLayout {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        val status = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        val renameBtn = Button(ctx)
        val deleteBtn = Button(ctx)
        val closeBtn = Button(ctx).apply {
            text = "×"
            minWidth = dp(44)
            setOnClickListener { hideBubbleNow() }
        }
        actions.addView(renameBtn)
        actions.addView(deleteBtn)
        actions.addView(closeBtn)
        outer.addView(status)
        outer.addView(actions)
        bubble = outer
        bubbleText = status
        bubbleActions = actions
        bubbleRenameBtn = renameBtn
        bubbleDeleteBtn = deleteBtn
        return outer
    }

    private fun hideBubbleNow() {
        bubbleHandler.removeCallbacks(hideBubble)
        bubble?.visibility = View.GONE
        bubbleActions?.visibility = View.GONE
    }

    private fun showBubbleLoading() {
        bubbleHandler.removeCallbacks(hideBubble)
        bubble?.visibility = View.VISIBLE
        bubbleActions?.visibility = View.GONE
        bubbleText?.setTextColor(Color.WHITE)
        bubbleText?.text = "正在请求 AI…"
    }

    private fun renderAiResult(res: ServiceBus.AiResult) {
        bubbleHandler.removeCallbacks(hideBubble)
        bubble?.visibility = View.VISIBLE
        bubbleActions?.visibility = View.GONE
        when (res) {
            is ServiceBus.AiResult.Success -> {
                bubbleText?.setTextColor(Color.WHITE)
                bubbleText?.text = "已复制到粘贴板可粘贴"
            }
            is ServiceBus.AiResult.Failure -> {
                bubbleText?.setTextColor(Color.parseColor("#FFB4AB"))
                bubbleText?.text = "AI 请求失败：${res.message}"
            }
        }
        bubbleHandler.postDelayed(hideBubble, 500)
    }

    private fun showRecordResult(scriptId: Long, scriptName: String) {
        bubbleHandler.removeCallbacks(hideBubble)
        bubble?.visibility = View.VISIBLE
        bubbleText?.setTextColor(Color.WHITE)
        bubbleText?.text = "已保存：$scriptName"
        bubbleActions?.visibility = View.VISIBLE
        bubbleRenameBtn?.apply {
            text = "改名"
            setOnClickListener {
                launchHome(scriptId)
                hideBubbleNow()
            }
        }
        bubbleDeleteBtn?.apply {
            text = "删除"
            setOnClickListener {
                lifecycleScope.launch {
                    App.from(this@OverlayService).scriptRepo.delete(scriptId)
                }
                hideBubbleNow()
            }
        }
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
            recordedPastes.clear()
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
        playStopBtn?.text = if (st is ServiceBus.PlayerState.Playing) "停止" else "▶"
    }

    private fun persistRecording() {
        val touches = recordedTouches.toList()
        val pastes = recordedPastes.toList()
        recordedTouches.clear()
        recordedPastes.clear()
        if (touches.isEmpty() && pastes.isEmpty()) return
        val events: List<RecordedEvent> =
            touches.map { RecordedEvent.Touch(it) } + pastes.map { RecordedEvent.Paste(it) }
        val sorted = events.sortedBy { it.timestamp }
        val firstTs = sorted.first().timestamp
        val actions = sorted.mapIndexed { i, ev ->
            val prevEnd = if (i == 0) firstTs else sorted[i - 1].endTimestamp
            val delay = (ev.timestamp - prevEnd).coerceAtLeast(0)
            when (ev) {
                is RecordedEvent.Touch -> {
                    val t = ev.raw
                    val dx = t.endX - t.startX
                    val dy = t.endY - t.startY
                    val type = when {
                        hypot(dx, dy) > 20f -> ActionType.SWIPE
                        t.durationMs > 500 -> ActionType.LONG_PRESS
                        else -> ActionType.TAP
                    }
                    Action(
                        scriptId = 0,
                        index = i,
                        type = type,
                        startX = t.startX,
                        startY = t.startY,
                        endX = t.endX,
                        endY = t.endY,
                        durationMs = t.durationMs,
                        delayBeforeMs = delay,
                    )
                }
                is RecordedEvent.Paste -> Action(
                    scriptId = 0,
                    index = i,
                    type = ActionType.PASTE,
                    startX = 0f,
                    startY = 0f,
                    durationMs = 0L,
                    delayBeforeMs = delay,
                )
            }
        }
        val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
        val script = Script(name = name)
        lifecycleScope.launch {
            val id = App.from(this@OverlayService).scriptRepo.save(script, actions)
            panelView?.post { showRecordResult(id, name) }
        }
    }

    private sealed interface RecordedEvent {
        val timestamp: Long
        val endTimestamp: Long
        data class Touch(val raw: ServiceBus.RawTouch) : RecordedEvent {
            override val timestamp: Long get() = raw.timestamp
            override val endTimestamp: Long get() = raw.timestamp + raw.durationMs
        }
        data class Paste(override val timestamp: Long) : RecordedEvent {
            override val endTimestamp: Long get() = timestamp
        }
    }

    private suspend fun showScriptPicker(anchor: View) {
        if (scriptPickerView != null) {
            dismissScriptPicker()
            return
        }
        val scripts = App.from(this).scriptRepo.observeScripts().first()
        val ctx = this
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 40, 40, 40))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        scripts.forEach { s ->
            val item = TextView(ctx).apply {
                text = s.name
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Play(s.id))
                    dismissScriptPicker()
                }
            }
            list.addView(item)
        }
        val newItem = TextView(ctx).apply {
            text = "+ 新建脚本"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                dismissScriptPicker()
                lifecycleScope.launch {
                    val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault())
                        .format(Date())
                    val id = App.from(this@OverlayService).scriptRepo
                        .save(Script(name = name), emptyList())
                    launchHome(id)
                }
            }
        }
        list.addView(newItem)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val params = WindowManager.LayoutParams(
            dp(200),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = loc[0]
            y = loc[1] + anchor.height
        }
        list.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                dismissScriptPicker(); true
            } else false
        }
        wm.addView(list, params)
        scriptPickerView = list
    }

    private fun dismissScriptPicker() {
        scriptPickerView?.let { runCatching { wm.removeView(it) } }
        scriptPickerView = null
    }

    private fun launchHome(scriptIdToEdit: Long?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (scriptIdToEdit != null) {
                putExtra(MainActivity.EXTRA_EDIT_SCRIPT_ID, scriptIdToEdit)
            }
        }
        startActivity(intent)
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
        bubbleHandler.removeCallbacks(hideBubble)
        bubble = null
        bubbleText = null
        bubbleActions = null
        bubbleRenameBtn = null
        bubbleDeleteBtn = null
        playStopBtn = null
        dismissScriptPicker()
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
