package com.wangchaozhi.wechatassistant.service

import android.graphics.Bitmap
import android.graphics.Rect
import com.wangchaozhi.wechatassistant.data.model.Script
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object ServiceBus {

    val accessibilityReady = MutableStateFlow(false)
    val captureReady = MutableStateFlow(false)
    val overlayReady = MutableStateFlow(false)
    val overlayHidden = MutableStateFlow(false)

    sealed interface PlayerCmd {
        data class Play(val scriptId: Long) : PlayerCmd
        data object Stop : PlayerCmd
    }

    val playerCmd = MutableSharedFlow<PlayerCmd>(extraBufferCapacity = 4)

    sealed interface CaptureCmd {
        data class TakeAndAsk(val prompt: String) : CaptureCmd
        data object JustCapture : CaptureCmd
        data object StartStream : CaptureCmd
        data object StopStream : CaptureCmd
    }

    val captureCmd = MutableSharedFlow<CaptureCmd>(extraBufferCapacity = 4)

    val lastBitmap = MutableStateFlow<Bitmap?>(null)
    data class CaptureFrame(val id: Long, val bitmap: Bitmap)
    val streamFrame = MutableStateFlow<CaptureFrame?>(null)
    val lastAiAnswer = MutableStateFlow<String?>(null)

    sealed interface AiResult {
        data class Success(val answer: String) : AiResult
        data class Failure(val message: String) : AiResult
    }

    val lastAiResult = MutableStateFlow<AiResult?>(null)

    sealed interface OverlayCmd {
        data object StartRecording : OverlayCmd
        data object StopRecording : OverlayCmd
        data class RecordedAction(val raw: RawTouch) : OverlayCmd
        data class RequestTemplatePick(val requestId: Long, val scriptIdToEdit: Long?) : OverlayCmd
        data class FlashRegionMask(val rect: Rect) : OverlayCmd
        data class FlashPositionMarker(val marker: PositionMarker) : OverlayCmd
    }

    val overlayCmd = MutableSharedFlow<OverlayCmd>(extraBufferCapacity = 16)
    sealed interface PositionMarker {
        data class Region(val rect: Rect, val label: String) : PositionMarker
        data class Point(val x: Float, val y: Float, val label: String) : PositionMarker
        data class Swipe(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val label: String,
        ) : PositionMarker
    }

    data class TemplatePickResult(val requestId: Long, val templatePath: String, val rect: Rect)
    val templatePickResult = MutableSharedFlow<TemplatePickResult>(extraBufferCapacity = 4)
    val selectedScriptChanged = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    val pasteCmd = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val pasteResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    val enterCmd = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val enterResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    val recordingMode = MutableStateFlow(false)
    val adbRecording = MutableStateFlow(false)
    val recordedTap = MutableSharedFlow<RawTouch>(extraBufferCapacity = 64)

    // 「边录边放」：悬浮录制层把刚录到的手势丢给无障碍立刻投放给真实 App，让界面前进。
    // recordInject 发手势，无障碍执行完回一个 recordInjectDone。
    val recordInject = MutableSharedFlow<RawTouch>(extraBufferCapacity = 16)
    val recordInjectDone = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    enum class RawTouchSource {
        SHIZUKU,
        OVERLAY,
    }

    data class RawTouch(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        val timestamp: Long,
        val source: RawTouchSource,
    )

    val playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)

    sealed interface PlayerState {
        data object Idle : PlayerState
        data class Playing(val script: Script, val stepIndex: Int, val totalSteps: Int) : PlayerState
    }
}
