package com.wangchaozhi.wechatassistant.service

import android.graphics.Bitmap
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
    }

    val captureCmd = MutableSharedFlow<CaptureCmd>(extraBufferCapacity = 4)

    val lastBitmap = MutableStateFlow<Bitmap?>(null)
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
    }

    val overlayCmd = MutableSharedFlow<OverlayCmd>(extraBufferCapacity = 16)

    val pasteCmd = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val pasteResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    val recordingMode = MutableStateFlow(false)
    val shizukuRecording = MutableStateFlow(false)
    val recordedTap = MutableSharedFlow<RawTouch>(extraBufferCapacity = 64)

    enum class RawTouchSource {
        SHIZUKU,
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
