package com.wangchaozhi.wechatassistant.feature.ai

/**
 * AI 模型供应商。AI 节点可在 [DASHSCOPE]（阿里千问 DashScope）与 [MODELSCOPE]（魔搭社区，
 * OpenAI 兼容接口）之间选择，并各自指定一个具体模型。节点未指定时回退到全局设置。
 */
enum class AiProvider(val label: String) {
    DASHSCOPE("阿里千问"),
    MODELSCOPE("魔搭社区");

    /** 该供应商可选的多模态（视觉）模型；列表仅作建议，节点里也允许手填自定义模型。 */
    val models: List<String>
        get() = when (this) {
            DASHSCOPE -> DASHSCOPE_MODELS
            MODELSCOPE -> MODELSCOPE_MODELS
        }

    companion object {
        /** 按存储的名字解析；非法/为空返回 null（表示「跟随全局」）。 */
        fun parse(name: String?): AiProvider? =
            name?.takeIf { it.isNotBlank() }?.let { n -> entries.firstOrNull { it.name == n } }

        val DASHSCOPE_MODELS = listOf(
            "qwen3.5-omni-flash",
            "qwen3.5-omni-plus",
            "qwen-vl-max",
            "qwen-vl-plus",
        )

        // 魔搭社区推理服务（api-inference.modelscope.cn）上的模型，OpenAI 兼容。
        val MODELSCOPE_MODELS = listOf(
            "Qwen/Qwen3.5-35B-A3B",
            "Qwen/Qwen3.5-122B-A10B",
        )
    }
}
