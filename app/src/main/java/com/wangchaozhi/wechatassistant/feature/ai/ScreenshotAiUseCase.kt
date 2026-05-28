package com.wangchaozhi.wechatassistant.feature.ai

import android.content.Context
import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.copyToClipboard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ScreenshotAiUseCase(
    private val context: Context,
    private val qwen: QwenRepository,
    private val history: AiAnswerRepository,
) {

    suspend fun run(prompt: String, scriptId: Long? = null): Result<String> {
        if (!ServiceBus.captureReady.value) {
            return Result.failure(IllegalStateException("截图服务未启动，请先在主界面开启屏幕共享。"))
        }
        val bitmap = withTimeoutOrNull(5_000) {
            ServiceBus.lastBitmap.value = null
            ServiceBus.captureCmd.tryEmit(ServiceBus.CaptureCmd.JustCapture)
            ServiceBus.lastBitmap.first { it != null }!!
        } ?: return Result.failure(IllegalStateException("截图超时。"))
        return runWithBitmap(bitmap, prompt, scriptId)
    }

    suspend fun runWithBitmap(
        bitmap: Bitmap,
        prompt: String,
        scriptId: Long? = null,
    ): Result<String> {
        val model = App.from(context).settingsRepo.qwenModel
        val effective = prompt.ifBlank { App.from(context).settingsRepo.defaultPrompt }
        val result = qwen.ask(bitmap, effective, model)
        result.onSuccess { answer ->
            context.copyToClipboard(answer)
            ServiceBus.lastAiAnswer.value = answer
            runCatching { history.save(bitmap, effective, answer, scriptId) }
        }
        return result
    }
}
