package com.wangchaozhi.wechatassistant.feature.ai

import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository

/**
 * 统一的视觉问答入口：按节点指定的供应商/模型把请求路由到对应的实现。
 * 供应商或模型为空时，回退到全局默认（由 [defaultDashScopeModel] / [defaultModelScopeModel] 提供）。
 */
class VisionAiRepository(
    private val qwen: QwenRepository,
    private val modelScope: ModelScopeRepository,
    private val defaultProvider: () -> AiProvider,
    private val defaultDashScopeModel: () -> String,
    private val defaultModelScopeModel: () -> String,
) {

    /** 拉取某供应商当前官方可用的模型 id 列表。 */
    suspend fun listModels(provider: AiProvider): Result<List<String>> = when (provider) {
        AiProvider.DASHSCOPE -> qwen.listModels()
        AiProvider.MODELSCOPE -> modelScope.listModels()
    }

    suspend fun ask(
        bitmap: Bitmap,
        prompt: String,
        providerName: String?,
        modelName: String?,
        maxSide: Int = 1280,
        quality: Int = 80,
    ): Result<String> {
        val provider = AiProvider.parse(providerName) ?: defaultProvider()
        return when (provider) {
            AiProvider.DASHSCOPE -> {
                val model = modelName?.ifBlank { null } ?: defaultDashScopeModel()
                qwen.ask(bitmap, prompt, model, maxSide = maxSide, quality = quality)
            }
            AiProvider.MODELSCOPE -> {
                val model = modelName?.ifBlank { null } ?: defaultModelScopeModel()
                modelScope.ask(bitmap, prompt, model, maxSide = maxSide, quality = quality)
            }
        }
    }
}
