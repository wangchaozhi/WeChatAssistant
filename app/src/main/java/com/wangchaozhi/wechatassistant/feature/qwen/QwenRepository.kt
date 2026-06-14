package com.wangchaozhi.wechatassistant.feature.qwen

import android.graphics.Bitmap
import android.os.SystemClock
import com.wangchaozhi.wechatassistant.BuildConfig
import com.wangchaozhi.wechatassistant.feature.ai.AiReasoningEffort
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
    private val debugLogger: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ask(
        bitmap: Bitmap,
        prompt: String,
        model: String = "qwen3.5-omni-flash",
        maxSide: Int = 1280,
        quality: Int = 80,
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.DEFAULT,
    ): Result<String> = withContext(Dispatchers.IO) {
        val totalStart = SystemClock.uptimeMillis()
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(
            IllegalStateException("尚未配置千问 API Key，请到设置中填入。")
        )
        val encodeStart = SystemClock.uptimeMillis()
        val base64 = bitmap.toBase64Jpeg(quality = quality, maxSide = maxSide)
        val encodeMs = SystemClock.uptimeMillis() - encodeStart
        val bodyStart = SystemClock.uptimeMillis()
        val body = buildRequestBody(model, prompt, base64, reasoningEffort)
        val requestJson = body.toString()
        val bodyMs = SystemClock.uptimeMillis() - bodyStart
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody(JSON_MEDIA))
            .build()
        debugLog(
            "Qwen ask start model=$model image=${bitmap.width}x${bitmap.height} " +
                "maxSide=$maxSide quality=$quality promptLen=${prompt.length} " +
                "encode=${encodeMs}ms body=${bodyMs}ms base64Chars=${base64.length} " +
                "requestChars=${requestJson.length}"
        )
        try {
            val httpStart = SystemClock.uptimeMillis()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val httpMs = SystemClock.uptimeMillis() - httpStart
                if (!resp.isSuccessful) {
                    debugLog(
                        "Qwen ask failure code=${resp.code} http=${httpMs}ms " +
                            "total=${SystemClock.uptimeMillis() - totalStart}ms bodyChars=${text.length}"
                    )
                    return@withContext Result.failure(
                        IOException("Qwen HTTP ${resp.code}: ${text.take(300)}")
                    )
                }
                val parseStart = SystemClock.uptimeMillis()
                val answer = parseAnswer(text)
                val parseMs = SystemClock.uptimeMillis() - parseStart
                debugLog(
                    "Qwen ask success code=${resp.code} http=${httpMs}ms parse=${parseMs}ms " +
                        "total=${SystemClock.uptimeMillis() - totalStart}ms bodyChars=${text.length} " +
                        "answerLen=${answer.length}"
                )
                Result.success(answer)
            }
        } catch (t: Throwable) {
            debugLog(
                "Qwen ask exception ${t.javaClass.simpleName}: ${t.message} " +
                    "total=${SystemClock.uptimeMillis() - totalStart}ms"
            )
            Result.failure(t)
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) debugLogger(message)
    }

    /** 拉取 DashScope（OpenAI 兼容模式）当前可用模型 id 列表。 */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(
            IllegalStateException("尚未配置千问 API Key，请到设置中填入。")
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
                        IOException("Qwen HTTP ${resp.code}: ${text.take(200)}")
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

    private fun buildRequestBody(
        model: String,
        prompt: String,
        base64: String,
        reasoningEffort: AiReasoningEffort,
    ): JsonObject =
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
                reasoningEffort.enableThinking?.let { put("enable_thinking", it) }
                reasoningEffort.thinkingBudget?.let { put("thinking_budget", it) }
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
        private const val MODELS_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/models"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class QwenError(@SerialName("message") val message: String? = null)
