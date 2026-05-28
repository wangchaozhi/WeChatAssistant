package com.wangchaozhi.wechatassistant.feature.qwen

import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.util.toBase64Jpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

class QwenRepository(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ask(
        bitmap: Bitmap,
        prompt: String,
        model: String = "qwen-vl-max-latest",
        maxSide: Int = 1280,
        quality: Int = 80,
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(
            IllegalStateException("尚未配置千问 API Key，请到设置中填入。")
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
                        IOException("Qwen HTTP ${resp.code}: ${text.take(300)}")
                    )
                }
                Result.success(parseAnswer(text))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun buildRequestBody(model: String, prompt: String, base64: String): JsonObject =
        buildJsonObject {
            put("model", model)
            put("input", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("image", "data:image/jpeg;base64,$base64")
                            })
                            add(buildJsonObject {
                                put("text", prompt)
                            })
                        })
                    })
                })
            })
            put("parameters", buildJsonObject {
                put("result_format", "message")
            })
        }

    private fun parseAnswer(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val output = root["output"]?.jsonObject ?: return raw
        val choices = output["choices"]?.jsonArray ?: return findTextDeep(output) ?: raw
        val first = choices.firstOrNull()?.jsonObject ?: return raw
        val message = first["message"]?.jsonObject ?: return raw
        val content = message["content"] ?: return raw
        return extractText(content)
    }

    private fun extractText(content: JsonElement): String = when (content) {
        is JsonArray -> content.joinToString("\n") { item ->
            when (item) {
                is JsonObject -> item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> item.toString()
            }
        }.trim()
        is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> content.jsonPrimitive.contentOrNull.orEmpty()
    }

    private fun findTextDeep(obj: JsonObject): String? {
        obj["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        for ((_, v) in obj) {
            if (v is JsonObject) findTextDeep(v)?.let { return it }
        }
        return null
    }

    companion object {
        private const val ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class QwenError(@SerialName("message") val message: String? = null)
