package com.wangchaozhi.wechatassistant.feature.ai

/**
 * Per-request reasoning control for Qwen-compatible thinking models.
 * DEFAULT keeps the provider/model default by omitting reasoning parameters.
 */
enum class AiReasoningEffort(
    val label: String,
    val enableThinking: Boolean?,
    val thinkingBudget: Int?,
) {
    DEFAULT("默认模型行为", null, null),
    OFF("关闭推理", false, null),
    LOW("低", true, 2048),
    MEDIUM("中", true, 8192),
    HIGH("高", true, 24576);

    companion object {
        fun parse(name: String?): AiReasoningEffort =
            name?.takeIf { it.isNotBlank() }?.let { n ->
                entries.firstOrNull { it.name == n }
            } ?: DEFAULT
    }
}
