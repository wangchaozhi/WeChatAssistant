package com.wangchaozhi.wechatassistant.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wangchaozhi.wechatassistant.BuildConfig

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "wca_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        context.getSharedPreferences("wca_prefs", Context.MODE_PRIVATE)
    }

    var qwenApiKey: String
        get() = prefs.getString(KEY_QWEN_API, "").orEmpty().ifBlank { BuildConfig.QWEN_API_KEY }
        set(value) = prefs.edit().putString(KEY_QWEN_API, value).apply()

    var defaultPrompt: String
        get() = prefs.getString(KEY_DEFAULT_PROMPT, DEFAULT_PROMPT).orEmpty()
        set(value) = prefs.edit().putString(KEY_DEFAULT_PROMPT, value).apply()

    var qwenModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var modelScopeApiKey: String
        get() = prefs.getString(KEY_MS_API, "").orEmpty().ifBlank { BuildConfig.MODELSCOPE_API_KEY }
        set(value) = prefs.edit().putString(KEY_MS_API, value).apply()

    var modelScopeModel: String
        get() = prefs.getString(KEY_MS_MODEL, DEFAULT_MS_MODEL).orEmpty()
        set(value) = prefs.edit().putString(KEY_MS_MODEL, value).apply()

    // AI 节点「跟随全局」时使用的默认供应商（AiProvider.name）。
    var defaultAiProvider: String
        get() = prefs.getString(KEY_DEFAULT_PROVIDER, DEFAULT_PROVIDER).orEmpty()
        set(value) = prefs.edit().putString(KEY_DEFAULT_PROVIDER, value).apply()

    var aiReasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT).orEmpty()
        set(value) = prefs.edit().putString(KEY_REASONING_EFFORT, value).apply()

    var thumbnailMaxSide: Int
        get() = prefs.getInt(KEY_THUMB_SIDE, DEFAULT_THUMB_SIDE)
        set(value) = prefs.edit().putInt(KEY_THUMB_SIDE, value).apply()

    var aiImageMaxSide: Int
        get() = prefs.getInt(KEY_AI_SIDE, DEFAULT_AI_SIDE)
        set(value) = prefs.edit().putInt(KEY_AI_SIDE, value).apply()

    fun cachedModels(providerName: String): List<String> =
        prefs.getString("$KEY_MODEL_CACHE_PREFIX$providerName", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    fun setCachedModels(providerName: String, models: List<String>) {
        prefs.edit()
            .putString(
                "$KEY_MODEL_CACHE_PREFIX$providerName",
                models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
            )
            .apply()
    }

    var adbHost: String
        get() = prefs.getString(KEY_ADB_HOST, DEFAULT_ADB_HOST).orEmpty()
        set(value) = prefs.edit().putString(KEY_ADB_HOST, value).apply()

    var adbPairingPort: Int
        get() = prefs.getInt(KEY_ADB_PAIR_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_ADB_PAIR_PORT, value).apply()

    var adbConnectPort: Int
        get() = prefs.getInt(KEY_ADB_CONNECT_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_ADB_CONNECT_PORT, value).apply()

    // 录制方式：RECORD_ENGINE_OVERLAY（默认，悬浮层 + 边录边放）/ RECORD_ENGINE_WIFI_ADB（getevent）。
    var recordEngine: String
        get() = prefs.getString(KEY_RECORD_ENGINE, RECORD_ENGINE_OVERLAY).orEmpty()
        set(value) = prefs.edit().putString(KEY_RECORD_ENGINE, value).apply()

    // 悬浮窗上次勾选的脚本 id（-1 表示未选）。重启/重建悬浮窗后据此恢复所选。
    var selectedScriptId: Long
        get() = prefs.getLong(KEY_SELECTED_SCRIPT, -1L)
        set(value) = prefs.edit().putLong(KEY_SELECTED_SCRIPT, value).apply()

    companion object {
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
        private const val KEY_SELECTED_SCRIPT = "overlay_selected_script_id"
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
