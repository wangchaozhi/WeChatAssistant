package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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
import android.widget.EditText
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
import com.wangchaozhi.wechatassistant.data.model.Edge
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
    private var extraActionsRow: LinearLayout? = null
    private var scriptPickerView: View? = null
    private var renameView: View? = null
    private var bubble: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var bubbleActions: LinearLayout? = null
    private var bubbleRenameBtn: Button? = null
    private var bubbleDeleteBtn: Button? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { bubble?.visibility = View.GONE }
    private val recordedTouches = mutableListOf<ServiceBus.RawTouch>()
    private val recordedPastes = mutableListOf<Long>()
    private val recordedEnters = mutableListOf<Long>()
    // 录制时截的模板：时间戳 + 模板图路径，停止录制时插进时间线变成 IMAGE_MATCH 步骤。
    private val recordedTemplates = mutableListOf<Pair<Long, String>>()
    private var cropOverlay: View? = null
    // 截模板/裁剪期间，屏蔽把全局触摸录进脚本（否则拖裁剪框会被当成操作录下来）。
    @Volatile private var suppressTouchRecording = false
    // 面板收起/展开
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelContent: View? = null
    private var collapsedHandle: View? = null
    private var collapsed = false
    private val shizukuReader by lazy { ShizukuTouchReader(this) }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        showPanel()
        ServiceBus.overlayReady.value = true
        lifecycleScope.launch {
            ServiceBus.recordedTap.collect { tap ->
                if (recording && !suppressTouchRecording &&
                    !isOnPanel(tap.startX, tap.startY) && acceptTap(tap)
                ) {
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
        lifecycleScope.launch {
            ServiceBus.enterResult.collect { ok ->
                val text = if (ok) "已回车" else "回车未生效"
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

    private fun glassButtonBg(): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            colors = intArrayOf(
                Color.argb(80, 255, 255, 255),
                Color.argb(40, 255, 255, 255),
            )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            content,
            null,
        )
    }

    private fun panelGlassBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(Color.argb(160, 18, 18, 22))
        setStroke(dp(1), Color.argb(70, 255, 255, 255))
    }

    private fun smallBtn(ctx: Context): Button = Button(ctx).apply {
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(dp(12), dp(6), dp(12), dp(6))
        stateListAnimator = null
        background = glassButtonBg()
        setTextColor(Color.WHITE)
    }

    private fun compactBtn(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
            stateListAnimator = null
            background = glassButtonBg()
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun showPanel() {
        if (panelView != null) return
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelGlassBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val rowSpacer = GradientDrawable().apply {
            setSize(dp(4), 1)
            setColor(Color.TRANSPARENT)
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val label = TextView(ctx).apply {
            text = "连点"
            setTextColor(Color.WHITE)
            textSize = 14f
            minWidth = dp(84)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(8), 0)
        }
        statusLabel = label
        val btnRec = compactBtn(ctx, "录制") { toggleRecording(recBtn ?: return@compactBtn) }
        recBtn = btnRec
        val btnHome = compactBtn(ctx, "↗") { launchHome(null) }
        val btnClose = compactBtn(ctx, "×") { stopSelf() }
        val btnCollapse = compactBtn(ctx, "⋮") { collapsePanel() }
        val nodesRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val nodesLabel = TextView(ctx).apply {
            text = "节点"
            setTextColor(Color.WHITE)
            textSize = 14f
            minWidth = dp(84)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(8), 0)
        }
        extraActionsRow = nodesRow
        val btnPlayStop = compactBtn(ctx, "▶") {
            if (ServiceBus.playerState.value is ServiceBus.PlayerState.Playing) {
                ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Stop)
            } else {
                if (!ServiceBus.accessibilityReady.value) {
                    Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_SHORT).show()
                    return@compactBtn
                }
                lifecycleScope.launch {
                    playStopBtn?.let { showScriptPicker(it) }
                }
            }
        }
        playStopBtn = btnPlayStop
        val btnAi = compactBtn(ctx, "AI") {
            if (!ServiceBus.captureReady.value) {
                Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            val prompt = App.from(ctx).settingsRepo.defaultPrompt
            showBubbleLoading()
            ServiceBus.lastAiResult.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.TakeAndAsk(prompt))
        }
        val btnPaste = compactBtn(ctx, "粘贴") {
            if (recording) recordedPastes += System.currentTimeMillis()
            if (!ServiceBus.accessibilityReady.value) {
                Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            ServiceBus.pasteCmd.tryEmit(Unit)
        }
        val btnEnter = compactBtn(ctx, "回车") {
            if (recording) recordedEnters += System.currentTimeMillis()
            if (!ServiceBus.accessibilityReady.value) {
                Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            ServiceBus.enterCmd.tryEmit(Unit)
        }
        val btnTemplate = compactBtn(ctx, "模板") {
            if (!recording) {
                Toast.makeText(ctx, "请先开始录制，再截模板", Toast.LENGTH_SHORT).show()
                return@compactBtn
            }
            if (!ServiceBus.captureReady.value) {
                Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            captureTemplateForRecording()
        }
        topRow.addView(label)
        topRow.addView(btnRec)
        topRow.addView(btnPlayStop)
        topRow.addView(btnHome)
        topRow.addView(btnClose)
        topRow.addView(btnCollapse)
        nodesRow.addView(nodesLabel)
        nodesRow.addView(btnAi)
        nodesRow.addView(btnPaste)
        nodesRow.addView(btnEnter)
        val templateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val templateLabel = TextView(ctx).apply {
            text = "找图"
            setTextColor(Color.WHITE)
            textSize = 14f
            minWidth = dp(84)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(8), 0)
        }
        templateRow.addView(templateLabel)
        templateRow.addView(btnTemplate)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(nodesRow)
            addView(templateRow)
        }
        panelContent = content
        // 收起后的小把手：可拖到任意位置，轻点展开
        val handle = compactBtn(ctx, "‹") { }.apply { visibility = View.GONE }
        collapsedHandle = handle
        attachHandleDrag(handle)
        container.addView(content)
        container.addView(buildBubble(ctx))
        container.addView(handle)

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
        panelParams = params

        attachDrag(container, params)
        wm.addView(container, params)
        panelView = container
    }

    private fun collapsePanel() {
        if (collapsed) return
        collapsed = true
        panelContent?.visibility = View.GONE
        bubble?.visibility = View.GONE
        collapsedHandle?.visibility = View.VISIBLE
        // 停靠到屏幕右边缘
        panelView?.post {
            val p = panelParams ?: return@post
            val screenW = resources.displayMetrics.widthPixels
            p.x = (screenW - (panelView?.width ?: 0)).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(panelView, p) }
        }
    }

    /** 把手：拖动移动整个窗口，未超过触摸阈值则视为轻点 → 展开。 */
    private fun attachHandleDrag(handle: View) {
        val slop = dp(6)
        var startX = 0; var startY = 0
        var downRawX = 0f; var downRawY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            val p = panelParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    downRawX = e.rawX; downRawY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop) moved = true
                    p.x = startX + (e.rawX - downRawX).toInt()
                    p.y = startY + (e.rawY - downRawY).toInt()
                    runCatching { wm.updateViewLayout(panelView, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) expandPanel()
                    true
                }
                else -> false
            }
        }
    }

    private fun expandPanel() {
        if (!collapsed) return
        collapsed = false
        collapsedHandle?.visibility = View.GONE
        panelContent?.visibility = View.VISIBLE
        // 展开后若超出右边缘则回拉，避免被屏幕裁掉
        panelView?.post {
            val p = panelParams ?: return@post
            val screenW = resources.displayMetrics.widthPixels
            val w = panelView?.width ?: 0
            if (p.x + w > screenW) p.x = (screenW - w - dp(8)).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(panelView, p) }
        }
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
        val renameBtn = smallBtn(ctx)
        val deleteBtn = smallBtn(ctx)
        val closeBtn = smallBtn(ctx).apply {
            text = "确定"
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
        dismissRename()
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
            setOnClickListener { showRenameDialog(scriptId, scriptName) }
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

    private fun showRenameDialog(scriptId: Long, currentName: String) {
        dismissRename()
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val title = TextView(ctx).apply {
            text = "重命名脚本"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        val edit = EditText(ctx).apply {
            setText(currentName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            isSingleLine = true
            setSelection(0, currentName.length)
            layoutParams = LinearLayout.LayoutParams(
                dp(220),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnOk = compactBtn(ctx, "保存") {
            val newName = edit.text.toString().trim()
            if (newName.isNotEmpty() && newName != currentName) {
                lifecycleScope.launch {
                    val data = App.from(this@OverlayService).scriptRepo.load(scriptId)
                    if (data != null) {
                        App.from(this@OverlayService).scriptRepo
                            .updateScript(data.script.copy(name = newName))
                        bubbleText?.post { bubbleText?.text = "已保存：$newName" }
                        bubbleRenameBtn?.setOnClickListener {
                            showRenameDialog(scriptId, newName)
                        }
                    }
                }
            }
            dismissRename()
        }
        val btnCancel = compactBtn(ctx, "取消") { dismissRename() }
        btnRow.addView(btnOk)
        btnRow.addView(btnCancel)
        root.addView(title)
        root.addView(edit)
        root.addView(btnRow)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, params)
        renameView = root
        edit.requestFocus()
    }

    private fun dismissRename() {
        renameView?.let { runCatching { wm.removeView(it) } }
        renameView = null
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

    /** 触摸是否落在悬浮面板范围内（含一点外扩）。面板上的按钮点击不该被录成操作。 */
    private fun isOnPanel(x: Float, y: Float): Boolean {
        val v = panelView ?: return false
        if (v.width == 0 || v.height == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val pad = dp(8)
        return x >= loc[0] - pad && x <= loc[0] + v.width + pad &&
            y >= loc[1] - pad && y <= loc[1] + v.height + pad
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
            recordedEnters.clear()
            recordedTemplates.clear()
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
            val captured = recordedTouches.size + recordedPastes.size +
                recordedEnters.size + recordedTemplates.size
            statusLabel?.text = "录制中 · $captured 步"
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

    /** 录制中点「模板」：先截当前目标屏（此时裁剪层尚未显示，截到的是真实页面），再弹裁剪层。 */
    private fun captureTemplateForRecording() {
        if (cropOverlay != null) return
        // 从按下「模板」起就停止录入触摸，直到裁剪层关闭。
        suppressTouchRecording = true
        val stamp = System.currentTimeMillis()
        lifecycleScope.launch {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            val bmp = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                ServiceBus.lastBitmap.first { it != null }
            }
            if (bmp == null) {
                Toast.makeText(this@OverlayService, "截图失败", Toast.LENGTH_SHORT).show()
                suppressTouchRecording = false
                return@launch
            }
            showCropOverlay(bmp, stamp)
        }
    }

    private fun showCropOverlay(bmp: android.graphics.Bitmap, stamp: Long) {
        val ctx: Context = this
        val cropView = TemplateCropView(ctx, bmp)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2000000"))
        }
        val hint = TextView(ctx).apply {
            text = "拖动框选要识别的目标，然后点「确定」"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val cancel = compactBtn(ctx, "取消") { removeCropOverlay() }
        val confirm = compactBtn(ctx, "确定") {
            val cropped = cropView.crop()
            if (cropped == null) {
                Toast.makeText(ctx, "裁剪区域太小", Toast.LENGTH_SHORT).show()
                return@compactBtn
            }
            val path = com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
                .saveTemplate(ctx, cropped)
            if (path != null) {
                recordedTemplates += stamp to path
                refreshStatus()
                Toast.makeText(ctx, "已记录找图点击步骤", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "模板保存失败", Toast.LENGTH_SHORT).show()
            }
            removeCropOverlay()
        }
        btnRow.addView(cancel)
        btnRow.addView(confirm)
        root.addView(hint)
        root.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnRow)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        cropOverlay = root
        wm.addView(root, params)
    }

    private fun removeCropOverlay() {
        cropOverlay?.let { runCatching { wm.removeView(it) } }
        cropOverlay = null
        // 稍延迟再恢复，吞掉关闭裁剪层时「确定/取消」那一下的触摸，避免被录进去。
        bubbleHandler.postDelayed({ suppressTouchRecording = false }, 250)
    }

    private fun persistRecording() {
        val touches = recordedTouches.toList()
        val pastes = recordedPastes.toList()
        val enters = recordedEnters.toList()
        val templates = recordedTemplates.toList()
        recordedTouches.clear()
        recordedPastes.clear()
        recordedEnters.clear()
        recordedTemplates.clear()
        if (touches.isEmpty() && pastes.isEmpty() && enters.isEmpty() && templates.isEmpty()) return
        val events: List<RecordedEvent> =
            touches.map { RecordedEvent.Touch(it) } +
                pastes.map { RecordedEvent.Paste(it) } +
                enters.map { RecordedEvent.Enter(it) } +
                templates.map { RecordedEvent.ImageMatch(it.first, it.second) }
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
                is RecordedEvent.Enter -> Action(
                    scriptId = 0,
                    index = i,
                    type = ActionType.ENTER,
                    startX = 0f,
                    startY = 0f,
                    durationMs = 0L,
                    delayBeforeMs = delay,
                )
                is RecordedEvent.ImageMatch -> Action(
                    scriptId = 0,
                    index = i,
                    type = ActionType.IMAGE_MATCH,
                    startX = 0f,
                    startY = 0f,
                    durationMs = 0L,
                    delayBeforeMs = delay,
                    templatePath = ev.templatePath,
                )
            }
        }
        // 转成节点图：录制的动作向下排成一列，前置 START，连成一条直链。
        // 临时 id 用负数（START=-1，其余 -(i+2)），saveGraph 会按位置映射成真实 id 并重写边。
        val positioned = actions.mapIndexed { i, a ->
            a.copy(id = -(i + 2L), posX = 120f, posY = 200f + i * 220f)
        }
        val start = Action(
            id = -1L, scriptId = 0, index = 0, type = ActionType.START,
            startX = 0f, startY = 0f, posX = 120f, posY = 40f,
        )
        val chain = listOf(start) + positioned
        val edges = chain.zipWithNext { a, b ->
            Edge(scriptId = 0, fromActionId = a.id, toActionId = b.id, fromPort = 0)
        }
        val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
        val script = Script(name = name)
        lifecycleScope.launch {
            val id = App.from(this@OverlayService).scriptRepo.saveGraph(script, chain, edges)
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
        data class Enter(override val timestamp: Long) : RecordedEvent {
            override val endTimestamp: Long get() = timestamp
        }
        data class ImageMatch(override val timestamp: Long, val templatePath: String) : RecordedEvent {
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
        removeCropOverlay()
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
        extraActionsRow = null
        dismissScriptPicker()
        dismissRename()
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
