package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionDefaults
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import com.wangchaozhi.wechatassistant.ui.CaptureRequestActivity
import com.wangchaozhi.wechatassistant.ui.MainActivity
import com.wangchaozhi.wechatassistant.ui.typeLabel
import com.wangchaozhi.wechatassistant.util.WifiAdbManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var panelView: View? = null
    private var recording = false
    private var recBtn: Button? = null
    private var statusLabel: TextView? = null
    private var statusScriptLabel: TextView? = null
    private var playStopBtn: Button? = null
    private var editBtn: Button? = null
    private var cancelRecBtn: Button? = null
    private var pickBtn: Button? = null
    private var shareBtn: Button? = null
    private var liveRegionPickBtn: Button? = null
    // 已选脚本：「选」按钮设定，「▶」按钮据此直接播放，再次播放无需重选。
    private var selectedScriptId: Long? = null
    private var selectedScriptName: String? = null
    private var extraActionsRow: LinearLayout? = null
    private var scriptPickerView: View? = null
    private var scriptPickerOutsideDismissAt: Long = 0L
    private var renameView: View? = null
    private var bubble: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var bubbleActions: LinearLayout? = null
    private var bubbleRenameBtn: Button? = null
    private var bubbleDeleteBtn: Button? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { bubble?.visibility = View.GONE }
    private val recordedTouches = mutableListOf<ServiceBus.RawTouch>()
    private val recordedPastes = mutableListOf<RecordedPasteStep>()
    private val recordedEnters = mutableListOf<Long>()
    // 录制时点的 AI：start=点击时刻，end=AI 返回结果的时刻(默认等于 start，结果回来时回填)。
    // 用 end 作为这步的结束时间，下一步(如粘贴)的间隔才不会把 AI 识图耗时算进去。
    private val recordedAi = mutableListOf<RecordedAiStep>()
    // 录制时截的模板：时间戳 + 模板图路径，停止录制时插进时间线变成 IMAGE_MATCH 步骤。
    private val recordedTemplates = mutableListOf<Pair<Long, String>>()
    private var cropOverlay: View? = null
    private var pendingLiveTemplatePickRequestId: Long? = null
    private var pendingLiveTemplatePickScriptId: Long? = null
    private var positionMaskView: View? = null
    // 截模板/裁剪期间，屏蔽把全局触摸录进脚本（否则拖裁剪框会被当成操作录下来）。
    @Volatile private var suppressTouchRecording = false
    // 面板收起/展开
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelContent: View? = null
    private var collapsedBar: View? = null
    private var collapsedPlayBtn: Button? = null
    private var collapsed = false
    private val adbReader by lazy { WifiAdbTouchReader(this) }
    // 「边录边放」注入进行中：FLAG_NOT_TOUCHABLE 异步生效，注入的那一下可能在生效前又被本层抓到，
    // 用它做重入保护，避免一个手势被录两次/注入两次。
    @Volatile private var injecting = false
    // 悬浮层录制：全屏透明捕获层及其窗口参数。
    private var recordOverlay: RecordOverlayView? = null
    private var recordOverlayParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        if (!showPanel()) {
            ServiceBus.overlayReady.value = false
            stopSelf()
            return
        }
        restoreSelectedScript()
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
            ServiceBus.captureReady.collect { ready ->
                shareBtn?.post {
                    shareBtn?.setTextColor(if (ready) Color.parseColor("#7CFFB0") else Color.WHITE)
                }
            }
        }
        lifecycleScope.launch {
            ServiceBus.overlayHidden.collect { hidden ->
                panelView?.post {
                    panelView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                }
                // 录制层也跟着隐藏：模板/AI 截图时不能截到本层。
                recordOverlay?.post {
                    recordOverlay?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            ServiceBus.lastAiResult.collect { res ->
                if (res != null) {
                    // 录制中：AI 结果到了，把这步的结束时间回填为现在，
                    // 否则下一步会把 AI 识图耗时当成「执行前等待」记进去。
                    if (recording) {
                        recordedAi.lastOrNull()?.let { step ->
                            if (step.end == step.start) step.end = recordTimestamp()
                        }
                    }
                    panelView?.post { renderAiResult(res) }
                }
            }
        }
        lifecycleScope.launch {
            ServiceBus.selectedScriptChanged.collect { id ->
                if (id <= 0L) {
                    clearSelectedScript(notify = false)
                    return@collect
                }
                val data = App.from(this@OverlayService).scriptRepo.load(id)
                if (data != null) {
                    setSelectedScript(id, data.script.name, notify = false)
                } else if (selectedScriptId == id) {
                    clearSelectedScript(notify = false)
                }
            }
        }
        lifecycleScope.launch {
            ServiceBus.overlayCmd.collect { cmd ->
                when (cmd) {
                    ServiceBus.OverlayCmd.StartRecording,
                    ServiceBus.OverlayCmd.StopRecording,
                    is ServiceBus.OverlayCmd.RecordedAction -> Unit
                    is ServiceBus.OverlayCmd.RequestTemplatePick ->
                        beginLiveTemplatePick(cmd.requestId, cmd.scriptIdToEdit)
                    is ServiceBus.OverlayCmd.FlashRegionMask ->
                        showFlashingPositionMarker(ServiceBus.PositionMarker.Region(cmd.rect, "位置"))
                    is ServiceBus.OverlayCmd.FlashPositionMarker ->
                        showFlashingPositionMarker(cmd.marker)
                }
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
            cornerRadius = dp(14).toFloat()
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
        cornerRadius = dp(14).toFloat()
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
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            setPadding(dp(9), dp(5), dp(9), dp(5))
            stateListAnimator = null
            background = glassButtonBg()
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun safeAddOverlay(
        view: View,
        params: WindowManager.LayoutParams,
        label: String,
    ): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已关闭，请重新授权后再启动面板", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Cannot add $label: overlay permission is not granted")
            return false
        }
        return runCatching {
            wm.addView(view, params)
        }.onFailure { e ->
            Toast.makeText(this, "$label 启动失败，请重新授权悬浮窗权限", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Cannot add $label", e)
        }.isSuccess
    }

    private fun showPanel(): Boolean {
        if (panelView != null) return true
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelGlassBg()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val rowSpacer = GradientDrawable().apply {
            setSize(dp(3), 1)
            setColor(Color.TRANSPARENT)
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val scriptNameLabel = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            visibility = View.GONE
        }
        // 选脚本按钮：始终显示☰，点击弹出脚本列表（已选状态由右侧脚本名表达）。
        val btnPick = compactBtn(ctx, "☰") { }
        pickBtn = btnPick
        val statusBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(46)
            setPadding(0, 0, dp(6), 0)
            addView(btnPick)
            addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(4) })
            addView(scriptNameLabel, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ))
        }
        statusLabel = label
        statusScriptLabel = scriptNameLabel
        // 点选脚本按钮或状态栏名字区域均可弹出脚本列表选择/切换脚本。
        btnPick.setOnClickListener {
            lifecycleScope.launch { showScriptPicker(btnPick) }
        }
        statusBox.setOnClickListener {
            lifecycleScope.launch { showScriptPicker(statusBox) }
        }
        val btnRec = compactBtn(ctx, "录制") { toggleRecording(recBtn ?: return@compactBtn) }
        recBtn = btnRec
        val btnHome = compactBtn(ctx, "↗") { launchHome(null, togglePrevious = true) }
        val btnClose = compactBtn(ctx, "×") { stopSelf() }
        val btnCollapse = compactBtn(ctx, "⋮") { collapsePanel() }
        val systemRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val nodesRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val nodesLabel = TextView(ctx).apply {
            text = "节点"
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = dp(46)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(6), 0)
        }
        extraActionsRow = nodesRow
        // 「✎」：编辑当前所选脚本（未选时提示并弹列表，锚定到状态栏）。选脚本改到点状态栏「已选」。
        val btnEdit = compactBtn(ctx, "✎") { editSelectedScript(statusBox) }
        editBtn = btnEdit
        // 「▶/停止」：播放已选脚本；播放中则停止。未选脚本时打开选择器引导先选。
        val btnPlayStop = compactBtn(ctx, "▶") { togglePlayStop(statusBox) }
        playStopBtn = btnPlayStop
        // 「×」：取消录制并丢弃已录步骤；仅录制中显示，替代此时无意义的 ✎/▶。
        val btnCancelRec = compactBtn(ctx, "×") { cancelRecording() }.apply {
            visibility = View.GONE
        }
        cancelRecBtn = btnCancelRec
        val btnAi = compactBtn(ctx, "AI") {
            val prompt = App.from(ctx).settingsRepo.defaultPrompt
            // 录制中先记一步（即使此刻截图服务没开，回放时再截图问答），与粘贴/回车一致。
            // end 先等于 start，等 AI 结果回来时再回填(见 lastAiResult 收集器)。
            if (recording) {
                val ts = recordTimestamp()
                recordedAi += RecordedAiStep(ts, prompt, ts)
                refreshStatus()
            }
            if (!ServiceBus.captureReady.value) {
                Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            showBubbleLoading()
            ServiceBus.lastAiResult.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.TakeAndAsk(prompt))
        }
        val btnPaste = compactBtn(ctx, "粘贴") {
            if (recording) {
                recordedPastes += RecordedPasteStep(recordTimestamp(), currentPasteText())
                refreshStatus()
            }
            if (!ServiceBus.accessibilityReady.value) {
                Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            ServiceBus.pasteCmd.tryEmit(Unit)
        }
        val btnEnter = compactBtn(ctx, "回车") {
            if (recording) {
                recordedEnters += recordTimestamp()
                refreshStatus()
            }
            if (!ServiceBus.accessibilityReady.value) {
                Toast.makeText(ctx, "请先开启「无障碍」服务", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            ServiceBus.enterCmd.tryEmit(Unit)
        }
        val btnTemplate = compactBtn(ctx, "选图点击") {
            if (!recording) {
                Toast.makeText(ctx, "请先开始录制，再选图点击", Toast.LENGTH_SHORT).show()
                return@compactBtn
            }
            if (!ServiceBus.captureReady.value) {
                Toast.makeText(ctx, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
                return@compactBtn
            }
            captureTemplateForRecording()
        }
        val btnLiveRegionPick = compactBtn(ctx, "框选") {
            captureLivePickForEditor()
        }.apply {
            visibility = View.GONE
        }
        liveRegionPickBtn = btnLiveRegionPick
        topRow.addView(statusBox, LinearLayout.LayoutParams(
            dp(110),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        topRow.addView(btnRec)
        topRow.addView(btnEdit)
        topRow.addView(btnPlayStop)
        topRow.addView(btnCancelRec)
        nodesRow.addView(nodesLabel)
        nodesRow.addView(btnAi)
        nodesRow.addView(btnPaste)
        nodesRow.addView(btnEnter)
        val templateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = rowSpacer
        }
        val templateLabel = TextView(ctx).apply {
            text = "找图"
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = dp(46)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(0, 0, dp(6), 0)
        }
        templateRow.addView(templateLabel)
        templateRow.addView(btnTemplate)
        templateRow.addView(btnLiveRegionPick)
        systemRow.addView(btnHome)
        systemRow.addView(btnClose)
        systemRow.addView(btnCollapse)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(nodesRow)
            addView(templateRow)
            addView(systemRow)
        }
        panelContent = content
        // 收起后的竖排小工具条：手柄(拖动/展开) + 播放 + 选脚本 + 编辑所选脚本
        val collapsedBarView = buildCollapsedBar(ctx)
        collapsedBar = collapsedBarView
        container.addView(content)
        container.addView(buildBubble(ctx))
        container.addView(collapsedBarView)

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
        if (!safeAddOverlay(container, params, "悬浮控制面板")) return false
        panelView = container
        return true
    }

    private fun collapsePanel() {
        if (collapsed) return
        collapsed = true
        panelContent?.visibility = View.GONE
        bubble?.visibility = View.GONE
        collapsedBar?.visibility = View.VISIBLE
        // 停靠到屏幕右边缘
        panelView?.post {
            val p = panelParams ?: return@post
            val screenW = resources.displayMetrics.widthPixels
            p.x = (screenW - (panelView?.width ?: 0)).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(panelView, p) }
        }
    }

    /** 折叠态按钮：拖动移动整个窗口，未超过触摸阈值则执行原点击动作。 */
    private fun attachCollapsedDrag(view: View, clickAction: () -> Unit) {
        val slop = dp(6)
        var startX = 0; var startY = 0
        var downRawX = 0f; var downRawY = 0f
        var moved = false
        view.setOnTouchListener { _, e ->
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
                    if (!moved) clickAction()
                    true
                }
                else -> false
            }
        }
    }

    private fun expandPanel() {
        if (!collapsed) return
        collapsed = false
        collapsedBar?.visibility = View.GONE
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

    /** 折叠态竖排工具条：手柄(拖动/轻点展开) + 播放 + 选脚本 + 编辑所选脚本。 */
    private fun buildCollapsedBar(ctx: Context): LinearLayout {
        val colSpacer = GradientDrawable().apply {
            setSize(1, dp(6))
            setColor(Color.TRANSPARENT)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = colSpacer
        }
        // 选脚本：先声明，供播放/编辑的「未选」兜底当作选择器锚点。
        val select = compactBtn(ctx, "☰") { }
        attachCollapsedDrag(select) { lifecycleScope.launch { showScriptPicker(select) } }
        val play = compactBtn(ctx, "▶") { }
        collapsedPlayBtn = play
        attachCollapsedDrag(play) { togglePlayStop(select) }
        val edit = compactBtn(ctx, "✎") { }
        attachCollapsedDrag(edit) { editSelectedScript(select) }
        val handle = compactBtn(ctx, "‹") { }
        attachCollapsedDrag(handle) { expandPanel() }
        // 屏幕共享：放在收起工具条最下面，用图标；就绪时变绿。
        val share = compactBtn(ctx, "▣") { }
        shareBtn = share
        attachCollapsedDrag(share) { requestScreenShare() }
        attachCollapsedDrag(bar) { expandPanel() }
        bar.addView(play)
        bar.addView(edit)
        bar.addView(select)
        bar.addView(handle)
        bar.addView(share)
        return bar
    }

    /** 播放已选脚本；播放中则停止。未选脚本时提示并打开选择器（锚定到 [pickerAnchor]）。 */
    private fun togglePlayStop(pickerAnchor: View?) {
        if (ServiceBus.playerState.value is ServiceBus.PlayerState.Playing) {
            ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Stop)
            return
        }
        if (!ServiceBus.accessibilityReady.value) {
            Toast.makeText(this, "请先开启「无障碍」服务", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val scripts = App.from(this@OverlayService).scriptRepo.observeScripts().first()
            if (scripts.isEmpty()) {
                clearSelectedScript()
                Toast.makeText(this@OverlayService, "暂无脚本，请先录制或新建脚本", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val id = selectedScriptId
            if (id == null) {
                Toast.makeText(this@OverlayService, "请先用「选」选择脚本", Toast.LENGTH_SHORT).show()
                if (pickerAnchor != null) showScriptPicker(pickerAnchor)
                return@launch
            }
            if (scripts.none { it.id == id }) {
                clearSelectedScript()
                Toast.makeText(this@OverlayService, "所选脚本已删除，请重新选择", Toast.LENGTH_SHORT).show()
                if (pickerAnchor != null) showScriptPicker(pickerAnchor)
                return@launch
            }
            ServiceBus.playerCmd.tryEmit(ServiceBus.PlayerCmd.Play(id))
        }
    }

    /** 打开主界面编辑当前所选脚本。未选时提示并打开选择器（锚定到 [pickerAnchor]）。 */
    private fun editSelectedScript(pickerAnchor: View?) {
        val id = selectedScriptId
        if (id == null) {
            Toast.makeText(this, "请先用「选」选择脚本", Toast.LENGTH_SHORT).show()
            if (pickerAnchor != null) lifecycleScope.launch { showScriptPicker(pickerAnchor) }
            return
        }
        launchHome(id)
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

    private fun currentPasteText(): String? =
        ServiceBus.lastAiAnswer.value?.takeIf { it.isNotEmpty() } ?: clipboardText()

    private fun clipboardText(): String? {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        return clip?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun showRecordResult(scriptId: Long, scriptName: String) {
        // 记下录制前选中的脚本，删掉刚录的脚本时好恢复回去。
        val prevSelectedId = selectedScriptId
        // 录完即设为已选(并持久化)，按「▶」可直接回放，无需再「选」。
        setSelectedScript(scriptId, scriptName)
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
                    // 恢复到录制前选中的脚本；它要是也被删了/不存在就清空选中。
                    val prev = prevSelectedId
                        ?.takeIf { it != scriptId }
                        ?.let { App.from(this@OverlayService).scriptRepo.load(it) }
                    if (prev != null) {
                        setSelectedScript(prev.script.id, prev.script.name)
                    } else {
                        clearSelectedScript()
                    }
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
                        if (selectedScriptId == scriptId) {
                            selectedScriptName = newName
                            statusLabel?.post { refreshStatus() }
                        }
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
        if (!safeAddOverlay(root, params, "重命名窗口")) return
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

    private fun recordTimestamp(): Long = SystemClock.uptimeMillis()

    private fun toggleRecording(btn: Button) {
        val overlayEngine =
            App.from(this).settingsRepo.recordEngine != SettingsRepository.RECORD_ENGINE_WIFI_ADB
        if (overlayEngine) toggleOverlayRecording(btn) else toggleAdbRecording(btn)
    }

    /** 取消录制：停止采集并丢弃已录步骤，不保存为脚本（区别于「✓」完成保存）。 */
    private fun cancelRecording() {
        if (!recording) return
        val overlayEngine =
            App.from(this).settingsRepo.recordEngine != SettingsRepository.RECORD_ENGINE_WIFI_ADB
        recording = false
        recBtn?.text = "录制"
        ServiceBus.recordingMode.value = false
        ServiceBus.adbRecording.value = false
        if (overlayEngine) removeRecordOverlay() else adbReader.stop()
        ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StopRecording)
        recordedTouches.clear()
        recordedPastes.clear()
        recordedEnters.clear()
        recordedAi.clear()
        recordedTemplates.clear()
        refreshStatus()
        Toast.makeText(this, "已取消录制", Toast.LENGTH_SHORT).show()
    }

    /** 悬浮层录制（默认）：全屏透明层捕获手势 +「边录边放」用无障碍投回真实 App。 */
    private fun toggleOverlayRecording(btn: Button) {
        if (!recording) {
            if (!ServiceBus.accessibilityReady.value) {
                Toast.makeText(this, "请先开启「无障碍」服务再录制", Toast.LENGTH_LONG).show()
                return
            }
            recording = true
            recordedTouches.clear()
            recordedPastes.clear()
            recordedEnters.clear()
            recordedAi.clear()
            recordedTemplates.clear()
            btn.text = "✓"
            ServiceBus.recordingMode.value = true
            ServiceBus.adbRecording.value = false
            showRecordOverlay()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StartRecording)
        } else {
            recording = false
            btn.text = "录制"
            ServiceBus.recordingMode.value = false
            removeRecordOverlay()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StopRecording)
            persistRecording()
        }
        refreshStatus()
    }

    /** Wi-Fi ADB 录制（可选项）：getevent 被动读取 /dev/input。 */
    private fun toggleAdbRecording(btn: Button) {
        if (!recording) {
            WifiAdbManager.refresh()
            if (!WifiAdbManager.state.value.connected) {
                // 配对过就用旧密钥自动重连，不必回设置页重输配对码
                Toast.makeText(this, "正在自动连接 Wi-Fi ADB...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    if (WifiAdbManager.reconnect().isSuccess) {
                        toggleAdbRecording(btn)
                    } else {
                        Toast.makeText(
                            this@OverlayService,
                            "请先在设置中配对 Wi-Fi ADB 后再录制",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                return
            }
            recording = true
            recordedTouches.clear()
            recordedPastes.clear()
            recordedEnters.clear()
            recordedAi.clear()
            recordedTemplates.clear()
            btn.text = "✓"
            ServiceBus.recordingMode.value = true
            ServiceBus.adbRecording.value = true
            adbReader.start(lifecycleScope) { message ->
                if (recording && recordedTouches.isEmpty()) {
                    recording = false
                    btn.text = "录制"
                    ServiceBus.recordingMode.value = false
                    ServiceBus.adbRecording.value = false
                    refreshStatus()
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StartRecording)
        } else {
            recording = false
            btn.text = "录制"
            ServiceBus.recordingMode.value = false
            ServiceBus.adbRecording.value = false
            adbReader.stop()
            ServiceBus.overlayCmd.tryEmit(ServiceBus.OverlayCmd.StopRecording)
            persistRecording()
        }
        refreshStatus()
    }

    /** 添加全屏透明录制层，并把控制面板重新抬到最上层，保证 完成/模板 仍可点。 */
    private fun showRecordOverlay() {
        if (recordOverlay != null) return
        val view = RecordOverlayView(this) { raw -> onOverlayGesture(raw) }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        recordOverlayParams = params
        if (!safeAddOverlay(view, params, "录制覆盖层")) {
            recordOverlayParams = null
            recording = false
            ServiceBus.recordingMode.value = false
            ServiceBus.adbRecording.value = false
            recBtn?.text = "录制"
            refreshStatus()
            return
        }
        recordOverlay = view
        // 把面板从窗口栈里摘下再加回去，使其浮在录制层之上。
        panelView?.let { p ->
            val pp = panelParams ?: return@let
            runCatching { wm.removeView(p) }
            runCatching { safeAddOverlay(p, pp, "悬浮控制面板") }
        }
    }

    private fun removeRecordOverlay() {
        recordOverlay?.let { runCatching { wm.removeView(it) } }
        recordOverlay = null
        recordOverlayParams = null
    }

    /** 录制层捕获到一个完整手势：先记进脚本，再「边录边放」投给真实 App。 */
    private fun onOverlayGesture(raw: ServiceBus.RawTouch) {
        if (!recording || suppressTouchRecording || injecting) return
        if (isOnPanel(raw.startX, raw.startY)) return
        // 重入保护要同步置位：注入回放的那一下若赶在 FLAG_NOT_TOUCHABLE 生效前到达，会再触发本回调。
        injecting = true
        // 记录走既有管线（onCreate 里的 recordedTap 收集器）。
        ServiceBus.recordedTap.tryEmit(raw)
        lifecycleScope.launch {
            // 注入期间放行：切非触摸，否则 dispatchGesture 会被本录制层再次截获。
            setRecordOverlayTouchable(false)
            try {
                // FLAG_NOT_TOUCHABLE 经 updateViewLayout 异步生效，必须等它真正落地，
                // 注入才会打到底层 App 而不是本录制层自己。
                kotlinx.coroutines.delay(FLAG_APPLY_DELAY_MS)
                ServiceBus.recordInject.emit(raw)
                kotlinx.coroutines.withTimeoutOrNull(
                    raw.durationMs + INJECT_EXTRA_TIMEOUT_MS
                ) { ServiceBus.recordInjectDone.first() }
                // 留一点时间让 App 完成跳转/动画再恢复采集。
                kotlinx.coroutines.delay(INJECT_SETTLE_MS)
            } finally {
                setRecordOverlayTouchable(true)
                injecting = false
            }
        }
    }

    private fun setRecordOverlayTouchable(touchable: Boolean) {
        val view = recordOverlay ?: return
        val params = recordOverlayParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun refreshStatus() {
        statusScriptLabel?.visibility = View.GONE
        // 选脚本按钮始终显示☰（功能恒为打开脚本列表），已选状态由右侧脚本名表达。
        pickBtn?.text = "☰"
        // 录制中：✎/▶ 此刻无意义，隐藏并露出「×」取消录制。
        editBtn?.visibility = if (recording) View.GONE else View.VISIBLE
        playStopBtn?.visibility = if (recording) View.GONE else View.VISIBLE
        cancelRecBtn?.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) {
            val captured = recordedTouches.size + recordedPastes.size +
                recordedEnters.size + recordedAi.size +
                recordedTemplates.size
            statusLabel?.text = "录制中 · $captured 步"
            return
        }
        val st = ServiceBus.playerState.value
        when (st) {
            is ServiceBus.PlayerState.Playing -> {
                statusLabel?.text = "播放中 · ${st.stepIndex + 1}/${st.totalSteps}"
            }
            ServiceBus.PlayerState.Idle -> {
                val name = selectedScriptName
                if (name != null) {
                    // 已选脚本名直接显示在☰按钮右侧。
                    statusLabel?.text = ""
                    statusScriptLabel?.text = name
                    statusScriptLabel?.visibility = View.VISIBLE
                    statusScriptLabel?.isSelected = true
                } else {
                    statusLabel?.text = ""
                }
            }
        }
        val playing = st is ServiceBus.PlayerState.Playing
        playStopBtn?.text = if (playing) "停止" else "▶"
        collapsedPlayBtn?.text = if (playing) "停" else "▶"
    }

    /** 录制中点「模板」：在真实目标页上直接悬浮框选，确认后再截图裁剪。 */
    private fun captureTemplateForRecording() {
        if (cropOverlay != null) return
        suppressTouchRecording = true
        showLiveTemplateCropOverlayForRecording()
    }

    private fun beginLiveTemplatePick(requestId: Long, scriptIdToEdit: Long?) {
        pendingLiveTemplatePickRequestId = requestId
        pendingLiveTemplatePickScriptId = scriptIdToEdit
        liveRegionPickBtn?.visibility = View.VISIBLE
        Toast.makeText(this, "切到目标页面后，点悬浮面板「框选」", Toast.LENGTH_LONG).show()
    }

    private fun captureLivePickForEditor() {
        val templateRequestId = pendingLiveTemplatePickRequestId ?: return
        if (!ServiceBus.captureReady.value) {
            Toast.makeText(this, "请先在主界面启动「截图服务」", Toast.LENGTH_LONG).show()
            return
        }
        if (cropOverlay != null) return
        suppressTouchRecording = true
        showLiveTemplateCropOverlayForEditor(templateRequestId)
    }

    private suspend fun captureScreenBitmap(): android.graphics.Bitmap? {
        ServiceBus.lastBitmap.value = null
        ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
        return kotlinx.coroutines.withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.first { it != null }
        }
    }

    private fun cropBitmapByScreenRect(bmp: android.graphics.Bitmap, rect: Rect): android.graphics.Bitmap? {
        val dm = resources.displayMetrics
        val sx = bmp.width.toFloat() / dm.widthPixels.coerceAtLeast(1)
        val sy = bmp.height.toFloat() / dm.heightPixels.coerceAtLeast(1)
        val l = (rect.left * sx).roundToInt().coerceIn(0, bmp.width - 1)
        val t = (rect.top * sy).roundToInt().coerceIn(0, bmp.height - 1)
        val r = (rect.right * sx).roundToInt().coerceIn(l + 1, bmp.width)
        val b = (rect.bottom * sy).roundToInt().coerceIn(t + 1, bmp.height)
        val w = r - l
        val h = b - t
        if (w < 8 || h < 8) return null
        return runCatching { android.graphics.Bitmap.createBitmap(bmp, l, t, w, h) }.getOrNull()
    }

    private fun showLiveCropOverlay(
        hintText: String,
        tooSmallText: String,
        onConfirmRect: (Rect) -> Unit,
    ) {
        val ctx: Context = this
        val cropView = LiveScreenCropView(ctx)
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val hint = TextView(ctx).apply {
            text = hintText
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        val cancel = compactBtn(ctx, "取消") { removeCropOverlay() }
        val confirm = compactBtn(ctx, "确定") {
            val rect = cropView.cropRect()
            if (rect.width() < 8 || rect.height() < 8) {
                Toast.makeText(ctx, tooSmallText, Toast.LENGTH_SHORT).show()
                return@compactBtn
            }
            onConfirmRect(rect)
        }
        btnRow.addView(cancel)
        btnRow.addView(confirm)
        root.addView(cropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ))
        root.addView(btnRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ))

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (!safeAddOverlay(root, params, "框选覆盖层")) {
            suppressTouchRecording = false
            return
        }
        cropOverlay = root
    }

    private fun showLiveTemplateCropOverlayForRecording() {
        showLiveCropOverlay(
            hintText = "在真实页面上拖动框选要识别的目标，然后点「确定」",
            tooSmallText = "裁剪区域太小",
        ) { rect ->
            panelView?.visibility = View.INVISIBLE
            removeCropOverlay()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(160)
                val bmp = captureScreenBitmap()
                panelView?.visibility = View.VISIBLE
                val cropped = bmp?.let { cropBitmapByScreenRect(it, rect) }
                if (cropped == null) {
                    Toast.makeText(this@OverlayService, "截图失败或裁剪区域太小", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val path = com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
                    .saveTemplate(this@OverlayService, cropped)
                cropped.recycle()
                if (path != null) {
                    recordedTemplates += recordTimestamp() to path
                    refreshStatus()
                    Toast.makeText(this@OverlayService, "已记录找图点击步骤", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@OverlayService, "模板保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLiveTemplateCropOverlayForEditor(requestId: Long) {
        showLiveCropOverlay(
            hintText = "在真实页面上拖动框选要识别的图片，然后点「确定」",
            tooSmallText = "裁剪区域太小",
        ) { rect ->
            val scriptIdToEdit = pendingLiveTemplatePickScriptId
            pendingLiveTemplatePickRequestId = null
            pendingLiveTemplatePickScriptId = null
            liveRegionPickBtn?.visibility = View.GONE
            panelView?.visibility = View.INVISIBLE
            removeCropOverlay()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(160)
                val bmp = captureScreenBitmap()
                panelView?.visibility = View.VISIBLE
                val cropped = bmp?.let { cropBitmapByScreenRect(it, rect) }
                if (cropped == null) {
                    Toast.makeText(this@OverlayService, "截图失败或裁剪区域太小", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val path = com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
                    .saveTemplate(this@OverlayService, cropped)
                cropped.recycle()
                if (path == null) {
                    Toast.makeText(this@OverlayService, "模板保存失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                ServiceBus.templatePickResult.tryEmit(ServiceBus.TemplatePickResult(requestId, path, rect))
                Toast.makeText(this@OverlayService, "已回填图片模板", Toast.LENGTH_SHORT).show()
                launchHome(scriptIdToEdit)
            }
        }
    }

    private fun showCropOverlay(bmp: android.graphics.Bitmap) {
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
                recordedTemplates += recordTimestamp() to path
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
        if (!safeAddOverlay(root, params, "模板裁剪窗口")) return
        cropOverlay = root
    }

    private fun showEditorTemplateCropOverlay(requestId: Long, bmp: android.graphics.Bitmap) {
        val ctx: Context = this
        val cropView = TemplateCropView(ctx, bmp)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2000000"))
        }
        val hint = TextView(ctx).apply {
            text = "拖动框选要识别的图片，然后点「确定」"
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
            cropped.recycle()
            if (path == null) {
                Toast.makeText(ctx, "模板保存失败", Toast.LENGTH_SHORT).show()
                return@compactBtn
            }
            val crop = cropView.cropRect()
            val dm = resources.displayMetrics
            val sx = dm.widthPixels.toFloat() / bmp.width.coerceAtLeast(1)
            val sy = dm.heightPixels.toFloat() / bmp.height.coerceAtLeast(1)
            val rect = android.graphics.Rect(
                (crop.left * sx).roundToInt().coerceIn(0, dm.widthPixels),
                (crop.top * sy).roundToInt().coerceIn(0, dm.heightPixels),
                (crop.right * sx).roundToInt().coerceIn(0, dm.widthPixels),
                (crop.bottom * sy).roundToInt().coerceIn(0, dm.heightPixels),
            )
            val scriptIdToEdit = pendingLiveTemplatePickScriptId
            pendingLiveTemplatePickRequestId = null
            pendingLiveTemplatePickScriptId = null
            liveRegionPickBtn?.visibility = View.GONE
            ServiceBus.templatePickResult.tryEmit(ServiceBus.TemplatePickResult(requestId, path, rect))
            Toast.makeText(ctx, "已回填图片模板", Toast.LENGTH_SHORT).show()
            removeCropOverlay()
            launchHome(scriptIdToEdit)
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
        if (!safeAddOverlay(root, params, "模板裁剪窗口")) return
        cropOverlay = root
    }

    private fun removeCropOverlay() {
        cropOverlay?.let { runCatching { wm.removeView(it) } }
        cropOverlay = null
        // 稍延迟再恢复，吞掉关闭裁剪层时「确定/取消」那一下的触摸，避免被录进去。
        bubbleHandler.postDelayed({ suppressTouchRecording = false }, 250)
    }

    private fun showFlashingPositionMarker(marker: ServiceBus.PositionMarker) {
        removePositionMask()
        val dm = resources.displayMetrics
        val view = object : View(this) {
            private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(170, 0, 0, 0)
                style = Paint.Style.FILL
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 0, 229, 255)
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 229, 255)
                style = Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(14).toFloat()
                style = Paint.Style.FILL
            }
            var visibleFrame = true

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val originX = loc[0].toFloat()
                val originY = loc[1].toFloat()
                when (marker) {
                    is ServiceBus.PositionMarker.Region -> {
                        val safe = Rect(
                            (marker.rect.left - originX).roundToInt().coerceIn(0, width),
                            (marker.rect.top - originY).roundToInt().coerceIn(0, height),
                            (marker.rect.right - originX).roundToInt().coerceIn(0, width),
                            (marker.rect.bottom - originY).roundToInt().coerceIn(0, height),
                        )
                        canvas.drawRect(0f, 0f, width.toFloat(), safe.top.toFloat(), dimPaint)
                        canvas.drawRect(0f, safe.bottom.toFloat(), width.toFloat(), height.toFloat(), dimPaint)
                        canvas.drawRect(0f, safe.top.toFloat(), safe.left.toFloat(), safe.bottom.toFloat(), dimPaint)
                        canvas.drawRect(safe.right.toFloat(), safe.top.toFloat(), width.toFloat(), safe.bottom.toFloat(), dimPaint)
                        if (visibleFrame) {
                            canvas.drawRect(safe, fillPaint)
                            canvas.drawRect(safe, strokePaint)
                            canvas.drawText(marker.label, safe.left.toFloat(), (safe.top - dp(8)).coerceAtLeast(dp(20)).toFloat(), textPaint)
                        }
                    }
                    is ServiceBus.PositionMarker.Point -> {
                        canvas.drawColor(Color.argb(90, 0, 0, 0))
                        if (visibleFrame) {
                            val x = (marker.x - originX).coerceIn(0f, width.toFloat())
                            val y = (marker.y - originY).coerceIn(0f, height.toFloat())
                            val r = dp(22).toFloat()
                            canvas.drawCircle(x, y, r, fillPaint)
                            canvas.drawCircle(x, y, r, strokePaint)
                            canvas.drawLine(x - r * 1.6f, y, x + r * 1.6f, y, strokePaint)
                            canvas.drawLine(x, y - r * 1.6f, x, y + r * 1.6f, strokePaint)
                            canvas.drawText(marker.label, x + r, (y - r).coerceAtLeast(dp(20).toFloat()), textPaint)
                        }
                    }
                    is ServiceBus.PositionMarker.Swipe -> {
                        canvas.drawColor(Color.argb(90, 0, 0, 0))
                        if (visibleFrame) {
                            val sx = (marker.startX - originX).coerceIn(0f, width.toFloat())
                            val sy = (marker.startY - originY).coerceIn(0f, height.toFloat())
                            val ex = (marker.endX - originX).coerceIn(0f, width.toFloat())
                            val ey = (marker.endY - originY).coerceIn(0f, height.toFloat())
                            canvas.drawCircle(sx, sy, dp(12).toFloat(), fillPaint)
                            canvas.drawLine(sx, sy, ex, ey, strokePaint)
                            canvas.drawCircle(ex, ey, dp(16).toFloat(), strokePaint)
                            val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble()).toFloat()
                            val arrow = dp(22).toFloat()
                            val a1 = angle + 2.6f
                            val a2 = angle - 2.6f
                            canvas.drawLine(ex, ey, ex + cos(a1) * arrow, ey + sin(a1) * arrow, strokePaint)
                            canvas.drawLine(ex, ey, ex + cos(a2) * arrow, ey + sin(a2) * arrow, strokePaint)
                            canvas.drawText(marker.label, sx, (sy - dp(18)).coerceAtLeast(dp(20).toFloat()), textPaint)
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        if (!safeAddOverlay(view, params, "位置标记")) return
        positionMaskView = view
        bubbleHandler.postDelayed({ removePositionMask() }, POSITION_MARKER_VISIBLE_MS)
    }

    private fun removePositionMask() {
        positionMaskView?.let { runCatching { wm.removeView(it) } }
        positionMaskView = null
    }

    private fun persistRecording() {
        val touches = recordedTouches.toList()
        val pastes = recordedPastes.toList()
        val enters = recordedEnters.toList()
        val ais = recordedAi.toList()
        val templates = recordedTemplates.toList()
        recordedTouches.clear()
        recordedPastes.clear()
        recordedEnters.clear()
        recordedAi.clear()
        recordedTemplates.clear()
        if (touches.isEmpty() && pastes.isEmpty() && enters.isEmpty() &&
            ais.isEmpty() && templates.isEmpty()) return
        val events: List<RecordedEvent> =
            touches.map { RecordedEvent.Touch(it) } +
                pastes.map { RecordedEvent.Paste(it.timestamp, it.text) } +
                enters.map { RecordedEvent.Enter(it) } +
                ais.map { RecordedEvent.Ai(it.start, it.end, it.prompt) } +
                templates.map { RecordedEvent.ImageMatch(it.first, it.second) }
        val sorted = events.sortedBy { it.timestamp }
        val actions = sorted.mapIndexed { i, ev ->
            // 不再用录制时实测的步间隔，所有节点的「执行前等待」统一用默认值（手势时长仍按录制）。
            // 第一个节点前面没有任何动作，无需等待，置 0。
            val delay = if (i == 0) 0L else ActionDefaults.DEFAULT_CLICK_DELAY_MS
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
                    pasteText = ev.text,
                )
                is RecordedEvent.Ai -> Action(
                    scriptId = 0,
                    index = i,
                    type = ActionType.SCREENSHOT_AI,
                    startX = 0f,
                    startY = 0f,
                    durationMs = 0L,
                    delayBeforeMs = delay,
                    aiPrompt = ev.prompt,
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
                    durationMs = ActionDefaults.QUICK_TAP_MS,
                    // 找图节点要等目标出现，给的等待比普通点击更长；首节点仍不等待。
                    delayBeforeMs = if (i == 0) 0L else ActionDefaults.DEFAULT_IMAGE_DELAY_MS,
                    retryCount = ActionDefaults.DEFAULT_IMAGE_DOWN_FALLBACK_PX,
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
        data class Paste(override val timestamp: Long, val text: String?) : RecordedEvent {
            override val endTimestamp: Long get() = timestamp
        }
        data class Enter(override val timestamp: Long) : RecordedEvent {
            override val endTimestamp: Long get() = timestamp
        }
        data class Ai(
            override val timestamp: Long,
            override val endTimestamp: Long,
            val prompt: String,
        ) : RecordedEvent
        data class ImageMatch(override val timestamp: Long, val templatePath: String) : RecordedEvent {
            override val endTimestamp: Long get() = timestamp
        }
    }

    private data class RecordedAiStep(val start: Long, val prompt: String, var end: Long)
    private data class RecordedPasteStep(val timestamp: Long, val text: String?)

    /** 记录所选脚本：更新内存状态、持久化 id、刷新面板标签。 */
    private fun setSelectedScript(id: Long, name: String?, notify: Boolean = true) {
        selectedScriptId = id
        selectedScriptName = name
        App.from(this).settingsRepo.selectedScriptId = id
        if (notify) ServiceBus.selectedScriptChanged.tryEmit(id)
        refreshStatus()
    }

    /** 清空所选脚本：内存与持久化都清掉，面板回到「连点」。 */
    private fun clearSelectedScript(notify: Boolean = true) {
        selectedScriptId = null
        selectedScriptName = null
        App.from(this).settingsRepo.selectedScriptId = -1L
        if (notify) ServiceBus.selectedScriptChanged.tryEmit(-1L)
        refreshStatus()
    }

    /** 启动时按持久化的 id 恢复所选脚本；脚本已被删则清除记录。 */
    private fun restoreSelectedScript() {
        val id = App.from(this).settingsRepo.selectedScriptId
        if (id <= 0L) return
        lifecycleScope.launch {
            val data = App.from(this@OverlayService).scriptRepo.load(id)
            if (data != null) {
                selectedScriptId = id
                selectedScriptName = data.script.name
                statusLabel?.post { refreshStatus() }
            } else {
                App.from(this@OverlayService).settingsRepo.selectedScriptId = -1L
            }
        }
    }

    private suspend fun showScriptPicker(anchor: View) {
        if (scriptPickerView != null) {
            dismissScriptPicker()
            return
        }
        if (SystemClock.uptimeMillis() - scriptPickerOutsideDismissAt < 250L) {
            return
        }
        val scripts = App.from(this).scriptRepo.observeScripts().first()
        val ctx = this
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelGlassBg()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scripts.forEach { s ->
            val checked = s.id == selectedScriptId
            val scriptBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(ctx).apply {
                // 已选脚本最左边显示钩，并高亮文字，便于一眼看出当前选中项。
                text = (if (checked) "✓ " else "    ") + s.name
                setTextColor(if (checked) Color.parseColor("#80D8FF") else Color.WHITE)
                textSize = 12f
                isSingleLine = true
                if (checked) {
                    // 当前选中项用跑马灯滚动完整名称（列表里只有一行选中，不会显得乱）。
                    ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    setHorizontallyScrolling(true)
                    isSelected = true
                } else {
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    // 只选中、不播放：记录(持久化)所选脚本，播放交给「▶」按钮。
                    setSelectedScript(s.id, s.name)
                    dismissScriptPicker()
                }
            }
            // 展开后列出该脚本的节点；点节点会在真实屏幕闪烁其位置。首次展开才懒加载。
            val nodeList = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(16), 0, dp(4), dp(6))
            }
            val arrow = smallBtn(ctx).apply {
                text = "▸🔗"
                textSize = 10f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    if (nodeList.visibility == View.GONE) {
                        text = "▾🔗"
                        nodeList.visibility = View.VISIBLE
                        if (nodeList.childCount == 0) {
                            lifecycleScope.launch { populateNodeList(nodeList, s.id) }
                        }
                    } else {
                        text = "▸🔗"
                        nodeList.visibility = View.GONE
                    }
                }
            }
            // 「🏷」改名：弹出悬浮重命名输入框（复用 showRenameDialog）。编辑脚本走顶行✎（选中后编辑）。
            val rename = smallBtn(ctx).apply {
                text = "📝"
                textSize = 10f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    dismissScriptPicker()
                    showRenameDialog(s.id, s.name)
                }
            }
            // 「🗑」删除脚本：两步确认，先点变「确定?」，3 秒内再点才真正删除。
            val del = smallBtn(ctx).apply {
                var armed = false
                val disarm = Runnable {
                    armed = false
                    text = "🗑"
                    setTextColor(Color.WHITE)
                }
                text = "🗑"
                textSize = 10f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    if (!armed) {
                        armed = true
                        text = "确定"
                        setTextColor(Color.parseColor("#FF6E6E"))
                        postDelayed(disarm, 1000)
                        return@setOnClickListener
                    }
                    removeCallbacks(disarm)
                    lifecycleScope.launch {
                        App.from(this@OverlayService).scriptRepo.delete(s.id)
                        if (selectedScriptId == s.id) clearSelectedScript()
                        list.removeView(scriptBox)
                        Toast.makeText(this@OverlayService, "已删除「${s.name}」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            header.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
            header.addView(arrow)
            header.addView(rename)
            header.addView(del)
            scriptBox.addView(header)
            scriptBox.addView(nodeList)
            list.addView(scriptBox)
        }
        val newItem = TextView(ctx).apply {
            text = "+ 新建脚本"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                dismissScriptPicker()
                launchHome(null, createNewScript = true)
            }
        }
        list.addView(newItem)
        // 节点全展开后可能超出屏幕，套一层限高(屏高 60%)的滚动容器。
        val scroller = object : ScrollView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val pickerWidth = dp(200)
        val gap = dp(8)
        val screenW = resources.displayMetrics.widthPixels
        val openToLeft = loc[0] + anchor.width / 2 > screenW / 2
        val params = WindowManager.LayoutParams(
            pickerWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (openToLeft) {
                (loc[0] - pickerWidth - gap).coerceAtLeast(gap)
            } else {
                (loc[0] + anchor.width + gap).coerceAtMost(screenW - pickerWidth - gap)
            }
            y = (loc[1] - gap).coerceAtLeast(gap)
        }
        scroller.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                scriptPickerOutsideDismissAt = SystemClock.uptimeMillis()
                dismissScriptPicker(); true
            } else false
        }
        if (!safeAddOverlay(scroller, params, "脚本选择器")) return
        scriptPickerView = scroller
    }

    /** 加载脚本节点并填进展开区：每条点一下就在真实屏幕闪烁该节点的位置。 */
    private suspend fun populateNodeList(container: LinearLayout, scriptId: Long) {
        val data = App.from(this).scriptRepo.load(scriptId) ?: return
        val ctx = this
        if (data.actions.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "（无节点）"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 11f
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
            return
        }
        data.actions.forEach { a ->
            container.addView(TextView(ctx).apply {
                text = nodeLabel(a)
                setTextColor(Color.WHITE)
                textSize = 11f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val marker = markerForAction(a)
                    if (marker != null) {
                        showFlashingPositionMarker(marker)
                    } else {
                        Toast.makeText(ctx, "该节点没有固定屏幕位置", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun nodeLabel(a: Action): String {
        val pos = when (a.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> "  (${a.startX.toInt()}, ${a.startY.toInt()})"
            ActionType.SWIPE ->
                "  (${a.startX.toInt()},${a.startY.toInt()})→(${a.endX.toInt()},${a.endY.toInt()})"
            else -> ""
        }
        val label = a.alias?.takeIf { it.isNotBlank() } ?: typeLabel(a.type)
        return "#${a.index + 1} $label$pos"
    }

    /** 把节点映射成屏幕高亮标记；无固定屏幕位置(等待/粘贴/回车等)返回 null。 */
    private fun markerForAction(a: Action): ServiceBus.PositionMarker? = when (a.type) {
        ActionType.TAP -> ServiceBus.PositionMarker.Point(a.startX, a.startY, "点击")
        ActionType.LONG_PRESS -> ServiceBus.PositionMarker.Point(a.startX, a.startY, "长按")
        ActionType.SWIPE ->
            ServiceBus.PositionMarker.Swipe(a.startX, a.startY, a.endX, a.endY, "滑动")
        else ->
            // 仅当节点确实带矩形区域(end 大于 start)时才高亮区域。
            if (a.endX > a.startX && a.endY > a.startY) {
                ServiceBus.PositionMarker.Region(
                    Rect(a.startX.toInt(), a.startY.toInt(), a.endX.toInt(), a.endY.toInt()),
                    typeLabel(a.type),
                )
            } else null
    }

    private fun dismissScriptPicker() {
        scriptPickerView?.let { runCatching { wm.removeView(it) } }
        scriptPickerView = null
    }

    private fun launchHome(
        scriptIdToEdit: Long?,
        createNewScript: Boolean = false,
        togglePrevious: Boolean = false,
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (togglePrevious && ServiceBus.mainActivityInForeground.value) {
                putExtra(MainActivity.EXTRA_RETURN_TO_PREVIOUS, true)
                return@apply
            }
            if (scriptIdToEdit != null) {
                putExtra(MainActivity.EXTRA_EDIT_SCRIPT_ID, scriptIdToEdit)
            }
            if (createNewScript) {
                putExtra(MainActivity.EXTRA_NEW_SCRIPT, true)
            }
        }
        startActivity(intent)
    }

    /**
     * 请求屏幕共享：已就绪则提示；否则启动无界面的中转 Activity 弹 MediaProjection 授权框。
     * 授权弹窗盖在当前 App 上，授权后直接回到原处，不会跳回本应用主界面。
     */
    private fun requestScreenShare() {
        if (ServiceBus.captureReady.value) {
            Toast.makeText(this, "屏幕共享已就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CaptureRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
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
        adbReader.stop()
        removeRecordOverlay()
        ServiceBus.overlayReady.value = false
        ServiceBus.recordingMode.value = false
        removeCropOverlay()
        removePositionMask()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        recBtn = null
        statusLabel = null
        statusScriptLabel = null
        bubbleHandler.removeCallbacks(hideBubble)
        bubble = null
        bubbleText = null
        bubbleActions = null
        bubbleRenameBtn = null
        bubbleDeleteBtn = null
        playStopBtn = null
        liveRegionPickBtn = null
        pendingLiveTemplatePickRequestId = null
        pendingLiveTemplatePickScriptId = null
        positionMaskView = null
        pickBtn = null
        collapsedBar = null
        collapsedPlayBtn = null
        extraActionsRow = null
        dismissScriptPicker()
        dismissRename()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 0x10A2
        // 切 FLAG_NOT_TOUCHABLE 后等它经 WindowManager 落地的时间，之后再注入。约 4 帧。
        private const val FLAG_APPLY_DELAY_MS = 64L
        // 注入超时 = 手势时长 + 这点富余（等无障碍回 done）。
        private const val INJECT_EXTRA_TIMEOUT_MS = 1_500L
        // 注入后留给真实 App 完成跳转/动画的安定时间，再恢复采集。
        private const val INJECT_SETTLE_MS = 120L
        // 运行时位置/识别框短暂显示，能肉眼确认，又避免高频节点长时间遮罩和重绘。
        private const val POSITION_MARKER_VISIBLE_MS = 300L
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
