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

    var thumbnailMaxSide: Int
        get() = prefs.getInt(KEY_THUMB_SIDE, DEFAULT_THUMB_SIDE)
        set(value) = prefs.edit().putInt(KEY_THUMB_SIDE, value).apply()

    var aiImageMaxSide: Int
        get() = prefs.getInt(KEY_AI_SIDE, DEFAULT_AI_SIDE)
        set(value) = prefs.edit().putInt(KEY_AI_SIDE, value).apply()

    companion object {
        private const val KEY_QWEN_API = "qwen_api_key"
        private const val KEY_DEFAULT_PROMPT = "default_prompt"
        private const val KEY_MODEL = "qwen_model"
        private const val KEY_THUMB_SIDE = "thumb_max_side"
        private const val KEY_AI_SIDE = "ai_image_max_side"
        const val DEFAULT_MODEL = "qwen3.5-omni-flash"
        const val DEFAULT_PROMPT = "请识别截图中的内容并简要回答。"
        const val DEFAULT_THUMB_SIDE = 480
        const val DEFAULT_AI_SIDE = 1280
    }
}
