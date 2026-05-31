package com.wangchaozhi.wechatassistant.feature.ai

import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.util.toBase64Jpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 魔搭社区（ModelScope）推理服务，采用 OpenAI 兼容的 /chat/completions 接口。
 * 与 [com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository] 接口一致，便于上层统一调度。
 */
class ModelScopeRepository(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ask(
        bitmap: Bitmap,
        prompt: String,
        model: String,
        maxSide: Int = 1280,
        quality: Int = 80,
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(
            IllegalStateException("尚未配置魔搭 API Key，请到设置中填入。")
        )
        val base64 = bitmap.toBase64Jpeg(quality = quality, maxSide = maxSide)
        val body = buildRequestBody(model, prompt, base64)
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("ModelScope HTTP ${resp.code}: ${text.take(300)}")
                    )
                }
                Result.success(parseAnswer(text))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** 拉取魔搭推理服务当前可用模型 id 列表。 */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(
            IllegalStateException("尚未配置魔搭 API Key，请到设置中填入。")
        )
        val req = Request.Builder()
            .url(MODELS_ENDPOINT)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("ModelScope HTTP ${resp.code}: ${text.take(200)}")
                    )
                }
                Result.success(parseModelIds(text))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parseModelIds(raw: String): List<String> {
        val data = json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }

    private fun buildRequestBody(model: String, prompt: String, base64: String): JsonObject =
        buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$base64")
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }

    private fun parseAnswer(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray ?: return raw
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return raw
        return message["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { raw }
    }

    companion object {
        private const val ENDPOINT = "https://api-inference.modelscope.cn/v1/chat/completions"
        private const val MODELS_ENDPOINT = "https://api-inference.modelscope.cn/v1/models"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
