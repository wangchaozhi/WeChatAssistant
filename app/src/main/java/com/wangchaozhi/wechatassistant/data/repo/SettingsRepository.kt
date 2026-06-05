package com.wangchaozhi.wechatassistant.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.BuildConfig
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.feature.ai.AiReasoningEffort

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context)

    /** 是否落在了明文降级分支（加密存储不可用）。UI 可据此提示用户敏感数据未加密。 */
    var isEncrypted: Boolean = true
        private set

    var qwenApiKey: String
        get() = prefs.getString(KEY_QWEN_API, "").orEmpty().ifBlank { BuildConfig.QWEN_API_KEY }
        set(value) = prefs.edit { putString(KEY_QWEN_API, value) }

    var defaultPrompt: String
        get() = prefs.getString(KEY_DEFAULT_PROMPT, DEFAULT_PROMPT).orEmpty()
        set(value) = prefs.edit { putString(KEY_DEFAULT_PROMPT, value) }

    var qwenModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var modelScopeApiKey: String
        get() = prefs.getString(KEY_MS_API, "").orEmpty().ifBlank { BuildConfig.MODELSCOPE_API_KEY }
        set(value) = prefs.edit { putString(KEY_MS_API, value) }

    var modelScopeModel: String
        get() = prefs.getString(KEY_MS_MODEL, DEFAULT_MS_MODEL).orEmpty()
        set(value) = prefs.edit { putString(KEY_MS_MODEL, value) }

    // AI 节点「跟随全局」时使用的默认供应商（AiProvider.name）。非法值回退到默认供应商。
    var defaultAiProvider: String
        get() = prefs.getString(KEY_DEFAULT_PROVIDER, DEFAULT_PROVIDER).orEmpty()
            .let { stored -> AiProvider.parse(stored)?.name ?: DEFAULT_PROVIDER }
        set(value) = prefs.edit { putString(KEY_DEFAULT_PROVIDER, value) }

    // 推理强度（AiReasoningEffort.name）。非法值回退到 DEFAULT。
    var aiReasoningEffort: String
        get() = AiReasoningEffort.parse(prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT)).name
        set(value) = prefs.edit { putString(KEY_REASONING_EFFORT, value) }

    var thumbnailMaxSide: Int
        get() = prefs.getInt(KEY_THUMB_SIDE, DEFAULT_THUMB_SIDE)
        set(value) = prefs.edit { putInt(KEY_THUMB_SIDE, value.coerceIn(MIN_IMAGE_SIDE, MAX_IMAGE_SIDE)) }

    var aiImageMaxSide: Int
        get() = prefs.getInt(KEY_AI_SIDE, DEFAULT_AI_SIDE)
        set(value) = prefs.edit { putInt(KEY_AI_SIDE, value.coerceIn(MIN_IMAGE_SIDE, MAX_IMAGE_SIDE)) }

    fun cachedModels(providerName: String): List<String> =
        prefs.getString("$KEY_MODEL_CACHE_PREFIX$providerName", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    fun setCachedModels(providerName: String, models: List<String>) {
        prefs.edit {
            putString(
                "$KEY_MODEL_CACHE_PREFIX$providerName",
                models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
            )
        }
    }

    var adbHost: String
        get() = prefs.getString(KEY_ADB_HOST, DEFAULT_ADB_HOST).orEmpty()
        set(value) = prefs.edit { putString(KEY_ADB_HOST, value) }

    var adbPairingPort: Int
        get() = prefs.getInt(KEY_ADB_PAIR_PORT, 0)
        set(value) = prefs.edit { putInt(KEY_ADB_PAIR_PORT, value.coerceIn(0, MAX_PORT)) }

    var adbConnectPort: Int
        get() = prefs.getInt(KEY_ADB_CONNECT_PORT, 0)
        set(value) = prefs.edit { putInt(KEY_ADB_CONNECT_PORT, value.coerceIn(0, MAX_PORT)) }

    // 录制方式：RECORD_ENGINE_OVERLAY（默认，悬浮层 + 边录边放）/ RECORD_ENGINE_WIFI_ADB（getevent）。
    // 非法值回退到默认录制方式。
    var recordEngine: String
        get() = prefs.getString(KEY_RECORD_ENGINE, RECORD_ENGINE_OVERLAY).orEmpty()
            .let { if (it in RECORD_ENGINES) it else RECORD_ENGINE_OVERLAY }
        set(value) = prefs.edit { putString(KEY_RECORD_ENGINE, value) }

    // 悬浮窗上次勾选的脚本 id（-1 表示未选）。重启/重建悬浮窗后据此恢复所选。
    var selectedScriptId: Long
        get() = prefs.getLong(KEY_SELECTED_SCRIPT, -1L)
        set(value) = prefs.edit { putLong(KEY_SELECTED_SCRIPT, value) }

    // 回放时是否在屏幕上闪现点击/滑动/找图的位置标记。默认开，可在设置里关闭。
    var showPlaybackMarker: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PLAYBACK_MARKER, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_PLAYBACK_MARKER, value) }

    /**
     * 创建加密的 SharedPreferences。
     *
     * EncryptedSharedPreferences 在 master key 被系统轮换/损坏时，create 会持续抛异常。
     * 旧实现一旦失败就永久落到一个**不同文件名**的明文存储，导致之前加密存的所有设置
     * （API key / 端口 / prompt 等）静默「消失」并回退到默认值。
     *
     * 这里先正常创建；失败后删掉损坏的加密文件再重建一次（多数轮换/损坏可借此自愈，
     * 代价是该文件里的旧数据确实已不可解密、只能从默认值重来）；仍失败才降级到明文，
     * 并记录日志、置 [isEncrypted] = false，让上层有机会提示用户。
     */
    // androidx.security:security-crypto 已废弃；迁移到 Tink/Keystore + DataStore 是单独的较大改动，
    // 暂以 @Suppress 保留现状并消除告警。
    @Suppress("DEPRECATION")
    private fun createSecurePrefs(context: Context): SharedPreferences {
        fun build(): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            build()
        } catch (t: Throwable) {
            runCatching { context.deleteSharedPreferences(SECURE_PREFS_NAME) }
            try {
                build()
            } catch (t2: Throwable) {
                isEncrypted = false
                runCatching {
                    App.from(context)
                        .appendLog("SettingsRepository: 加密存储不可用，降级到明文：${t2.message}")
                }
                context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    companion object {
        private const val SECURE_PREFS_NAME = "wca_secure_prefs"
        private const val PLAIN_PREFS_NAME = "wca_prefs"
        private const val MAX_PORT = 65535
        private const val MIN_IMAGE_SIDE = 64
        private const val MAX_IMAGE_SIDE = 4096
        private const val KEY_QWEN_API = "qwen_api_key"
        private const val KEY_DEFAULT_PROMPT = "default_prompt"
        private const val KEY_MODEL = "qwen_model"
        private const val KEY_MS_API = "modelscope_api_key"
        private const val KEY_MS_MODEL = "modelscope_model"
        private const val KEY_DEFAULT_PROVIDER = "default_ai_provider"
        private const val KEY_REASONING_EFFORT = "ai_reasoning_effort"
        private const val KEY_THUMB_SIDE = "thumb_max_side"
        private const val KEY_AI_SIDE = "ai_image_max_side"
        private const val KEY_MODEL_CACHE_PREFIX = "model_cache_"
        private const val KEY_ADB_HOST = "adb_host"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"
        private const val KEY_ADB_CONNECT_PORT = "adb_connect_port"
        private const val KEY_RECORD_ENGINE = "record_engine"
        const val RECORD_ENGINE_OVERLAY = "OVERLAY"
        const val RECORD_ENGINE_WIFI_ADB = "WIFI_ADB"
        private val RECORD_ENGINES = setOf(RECORD_ENGINE_OVERLAY, RECORD_ENGINE_WIFI_ADB)
        private const val KEY_SELECTED_SCRIPT = "overlay_selected_script_id"
        private const val KEY_SHOW_PLAYBACK_MARKER = "show_playback_marker"
        const val DEFAULT_MODEL = "qwen3.5-omni-flash"
        const val DEFAULT_MS_MODEL = "Qwen/Qwen3.5-122B-A10B"
        const val DEFAULT_PROVIDER = "DASHSCOPE"
        const val DEFAULT_REASONING_EFFORT = "DEFAULT"
        const val DEFAULT_PROMPT = "请识别截图中的内容并简要回答。"
        const val DEFAULT_THUMB_SIDE = 480
        const val DEFAULT_AI_SIDE = 1280
        const val DEFAULT_ADB_HOST = "127.0.0.1"
    }
}
