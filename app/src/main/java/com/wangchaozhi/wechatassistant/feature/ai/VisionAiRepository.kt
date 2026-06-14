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
    private val defaultReasoningEffort: () -> AiReasoningEffort,
) {

    /** 拉取某供应商当前官方可用的模型 id 列表。 */
    suspend fun listModels(provider: AiProvider): Result<List<String>> = when (provider) {
        AiProvider.DASHSCOPE -> qwen.listModels()
        AiProvider.MODELSCOPE -> modelScope.listModels()
    }

    /** 把节点指定（可空）的供应商/模型解析成实际生效的一对，便于调用与历史记录。 */
    fun resolve(providerName: String?, modelName: String?): Pair<AiProvider, String> {
        val provider = AiProvider.parse(providerName) ?: defaultProvider()
        val model = modelName?.ifBlank { null } ?: when (provider) {
            AiProvider.DASHSCOPE -> defaultDashScopeModel()
            AiProvider.MODELSCOPE -> defaultModelScopeModel()
        }
        return provider to model
    }

    suspend fun ask(
        bitmap: Bitmap,
        prompt: String,
        providerName: String?,
        modelName: String?,
        maxSide: Int = 1280,
        quality: Int = 80,
        onPartial: ((String) -> Unit)? = null,
    ): Result<String> {
        val (provider, model) = resolve(providerName, modelName)
        val reasoningEffort = defaultReasoningEffort()
        return when (provider) {
            AiProvider.DASHSCOPE -> qwen.ask(
                bitmap,
                prompt,
                model,
                maxSide = maxSide,
                quality = quality,
                reasoningEffort = reasoningEffort,
                onPartial = onPartial,
            )
            AiProvider.MODELSCOPE -> modelScope.ask(
                bitmap,
                prompt,
                model,
                maxSide = maxSide,
                quality = quality,
                reasoningEffort = reasoningEffort,
                onPartial = onPartial,
            )
        }
    }
}
