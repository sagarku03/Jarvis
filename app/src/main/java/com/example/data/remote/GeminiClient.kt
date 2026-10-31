package com.example.data.remote

import com.example.data.local.ApiKeyStore
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.ToolCallSpec
import com.example.domain.tool.ToolDescriptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class KeyVerificationResult {
    data class Valid(
        val availableModels: List<String>,
        val defaultModelId: String
    ) : KeyVerificationResult()

    data class Invalid(val message: String) : KeyVerificationResult()
    data class RateLimited(val message: String, val availableModels: List<String> = listOf("gemini-2.5-flash")) : KeyVerificationResult()
    data class NetworkError(val message: String) : KeyVerificationResult()
}

sealed class GeminiTurnResult {
    data class Success(
        val assistantText: String?,
        val toolCalls: List<ToolCallSpec>,
        val promptTokens: Int = 0,
        val candidateTokens: Int = 0
    ) : GeminiTurnResult()

    data class KeyRejected(val message: String) : GeminiTurnResult()
    data class RateLimited(val message: String) : GeminiTurnResult()
    data class ServerError(val message: String) : GeminiTurnResult()
    data class Offline(val message: String) : GeminiTurnResult()
}

/**
 * Direct Google Gemini REST API client (`https://generativelanguage.googleapis.com`).
 *
 * Security invariants:
 * - Always sends the user's API key in the `x-goog-api-key` HTTP header.
 * - Never appends `?key=` to any URL.
 * - Never logs the API key.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyStore: ApiKeyStore,
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL }
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_FALLBACK_MODEL = "gemini-2.5-flash"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ALLOWED_SCHEMA_KEYS = setOf("type", "properties", "required", "description", "enum", "items")

        fun selectDefaultModel(models: List<String>): String {
            return models.firstOrNull { id ->
                val lower = id.lowercase()
                lower.contains("flash") &&
                    !lower.contains("lite") &&
                    !lower.contains("tts") &&
                    !lower.contains("image") &&
                    !lower.contains("live")
            } ?: models.firstOrNull() ?: DEFAULT_FALLBACK_MODEL
        }
    }

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    suspend fun verifyKeyAndListModels(rawKey: String): KeyVerificationResult = withContext(Dispatchers.IO) {
        val cleanKey = rawKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
        }

        val baseUrl = baseUrlProvider().trimEnd('/')
        val url = "$baseUrl/v1beta/models"
        val verifyClient = okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        try {
            verifyClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val bodyStr = response.body?.string().orEmpty()
                        val parsed = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull().orEmpty()
                        val rawModels = (parsed["models"] as? List<*>).orEmpty()
                        val supportedModels = rawModels.mapNotNull { item ->
                            val modelMap = item as? Map<*, *> ?: return@mapNotNull null
                            val name = (modelMap["name"] as? String)?.removePrefix("models/")?.trim()
                                ?: return@mapNotNull null
                            val methods = (modelMap["supportedGenerationMethods"] as? List<*>)
                                ?.mapNotNull { it as? String }
                                .orEmpty()
                            if ("generateContent" in methods) name else null
                        }.distinct()

                        val finalModels = supportedModels.ifEmpty { listOf(DEFAULT_FALLBACK_MODEL) }
                        val defaultModel = selectDefaultModel(finalModels)
                        KeyVerificationResult.Valid(
                            availableModels = finalModels,
                            defaultModelId = defaultModel
                        )
                    }
                    400, 401, 403 -> {
                        KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
                    }
                    429 -> {
                        KeyVerificationResult.RateLimited("Key is valid but its limit is reached")
                    }
                    else -> {
                        KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            KeyVerificationResult.NetworkError("No internet connection")
        } catch (_: Exception) {
            KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
        }
    }

    suspend fun generateContent(
        modelId: String,
        systemInstruction: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>
    ): GeminiTurnResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext GeminiTurnResult.KeyRejected("Your Gemini key was rejected. Update it in Settings.")
        }

        val resolvedModel = modelId.removePrefix("models/").trim().ifEmpty { DEFAULT_FALLBACK_MODEL }
        val baseUrl = baseUrlProvider().trimEnd('/')
        val url = "$baseUrl/v1beta/models/$resolvedModel:generateContent"

        val payloadMap = buildGenerateContentPayload(
            systemInstruction = systemInstruction,
            messages = messages,
            tools = tools
        )
        val requestJson = mapAdapter.toJson(payloadMap)

        var attempt = 0
        val maxRetries429 = 2
        val maxRetries5xx = 1

        while (attempt <= maxRetries429) {
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                val retryDelayMs: Long? = okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyStr = response.body?.string().orEmpty()

                    when {
                        code == 200 -> {
                            return@withContext parseSuccessResponse(bodyStr)
                        }
                        code == 400 || code == 401 || code == 403 -> {
                            return@withContext GeminiTurnResult.KeyRejected(
                                "Your Gemini key was rejected. Update it in Settings."
                            )
                        }
                        code == 429 -> {
                            if (attempt < maxRetries429) {
                                val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                                if (retryAfterSec != null) {
                                    (retryAfterSec * 1000L).coerceIn(200L, 5000L)
                                } else {
                                    500L * (attempt + 1)
                                }
                            } else {
                                return@withContext GeminiTurnResult.RateLimited(
                                    "Rate limit reached for your key. Try again in a minute."
                                )
                            }
                        }
                        code in 500..599 -> {
                            if (attempt < maxRetries5xx) {
                                600L
                            } else {
                                return@withContext GeminiTurnResult.ServerError(
                                    "Gemini service is temporarily unavailable (HTTP $code)."
                                )
                            }
                        }
                        else -> {
                            return@withContext GeminiTurnResult.ServerError(
                                "Gemini API error (HTTP $code)."
                            )
                        }
                    }
                }
                attempt++
                if (retryDelayMs != null) {
                    delay(retryDelayMs)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                return@withContext GeminiTurnResult.Offline("You appear to be offline.")
            }
        }
        GeminiTurnResult.RateLimited("Rate limit reached for your key. Try again in a minute.")
    }

    suspend fun generateStructuredJson(
        modelId: String,
        systemInstruction: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.get()?.trim() ?: return@withContext null
        val resolvedModel = modelId.removePrefix("models/").trim().ifEmpty { DEFAULT_FALLBACK_MODEL }
        val baseUrl = baseUrlProvider().trimEnd('/')
        val url = "$baseUrl/v1beta/models/$resolvedModel:generateContent"

        val payload = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to userPrompt))
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.2,
                "maxOutputTokens" to 512,
                "responseMimeType" to "application/json"
            )
        )

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(mapAdapter.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return@withContext try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyStr = response.body?.string().orEmpty()
                when (val parsed = parseSuccessResponse(bodyStr)) {
                    is GeminiTurnResult.Success -> parsed.assistantText
                    else -> null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildGenerateContentPayload(
        systemInstruction: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>
    ): Map<String, Any?> {
        val contents = mutableListOf<MutableMap<String, Any?>>()

        for (msg in messages) {
            val (role, parts) = when (msg.role) {
                MessageRole.USER, MessageRole.SYSTEM -> {
                    "user" to listOf(mapOf("text" to msg.content))
                }
                MessageRole.ASSISTANT -> {
                    val assistantParts = mutableListOf<Map<String, Any?>>()
                    if (msg.content.isNotBlank()) {
                        assistantParts.add(mapOf("text" to msg.content))
                    }
                    if (!msg.toolName.isNullOrBlank()) {
                        val parsedArgs = msg.toolArgsJson?.let { raw ->
                            runCatching { mapAdapter.fromJson(raw) }.getOrNull()
                        } ?: emptyMap()
                        assistantParts.add(
                            mapOf(
                                "functionCall" to mapOf(
                                    "name" to msg.toolName,
                                    "args" to parsedArgs
                                )
                            )
                        )
                    }
                    if (assistantParts.isEmpty()) {
                        assistantParts.add(mapOf("text" to "Done."))
                    }
                    "model" to assistantParts
                }
                MessageRole.TOOL -> {
                    val fnName = msg.toolName?.takeIf { it.isNotBlank() } ?: "tool_result"
                    // When sending a functionResponse, ensure the previous turn in contents is a model functionCall
                    // if one wasn't explicitly recorded as an ASSISTANT functionCall turn.
                    val prev = contents.lastOrNull()
                    val prevParts = (prev?.get("parts") as? List<*>).orEmpty()
                    val prevHasFunctionCall = prev?.get("role") == "model" && prevParts.any {
                        (it as? Map<*, *>)?.containsKey("functionCall") == true
                    }
                    if (!prevHasFunctionCall) {
                        val synthArgs = msg.toolArgsJson?.let { raw ->
                            runCatching { mapAdapter.fromJson(raw) }.getOrNull()
                        } ?: emptyMap()
                        contents.add(
                            mutableMapOf(
                                "role" to "model",
                                "parts" to mutableListOf<Map<String, Any?>>(
                                    mapOf(
                                        "functionCall" to mapOf(
                                            "name" to fnName,
                                            "args" to synthArgs
                                        )
                                    )
                                )
                            )
                        )
                    }
                    "user" to listOf(
                        mapOf(
                            "functionResponse" to mapOf(
                                "name" to fnName,
                                "response" to mapOf("result" to msg.content)
                            )
                        )
                    )
                }
            }

            val lastContent = contents.lastOrNull()
            if (lastContent != null && lastContent["role"] == role) {
                @Suppress("UNCHECKED_CAST")
                val existingParts = lastContent["parts"] as MutableList<Map<String, Any?>>
                existingParts.addAll(parts)
            } else {
                contents.add(
                    mutableMapOf(
                        "role" to role,
                        "parts" to parts.toMutableList()
                    )
                )
            }
        }

        if (contents.isEmpty()) {
            contents.add(
                mutableMapOf(
                    "role" to "user",
                    "parts" to mutableListOf<Map<String, Any?>>(mapOf("text" to "Hello"))
                )
            )
        }

        val functionDeclarations = tools.map { descriptor ->
            val rawSchema = runCatching { mapAdapter.fromJson(descriptor.jsonSchema) }.getOrNull()
            val sanitizedParams = sanitizeOpenApiSchema(rawSchema)
            mapOf(
                "name" to descriptor.name,
                "description" to descriptor.description,
                "parameters" to sanitizedParams
            )
        }

        val result = mutableMapOf<String, Any?>(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to contents,
            "generationConfig" to mapOf(
                "temperature" to 0.4,
                "maxOutputTokens" to 1024
            )
        )

        if (functionDeclarations.isNotEmpty()) {
            result["tools"] = listOf(
                mapOf("functionDeclarations" to functionDeclarations)
            )
        }

        return result
    }

    private fun sanitizeOpenApiSchema(raw: Map<String, Any?>?): Map<String, Any?> {
        if (raw == null) {
            return mapOf("type" to "OBJECT", "properties" to emptyMap<String, Any?>())
        }
        val cleaned = mutableMapOf<String, Any?>()
        for ((k, v) in raw) {
            if (k !in ALLOWED_SCHEMA_KEYS || v == null) continue
            when (k) {
                "type" -> cleaned["type"] = (v as? String)?.uppercase() ?: "STRING"
                "properties" -> {
                    val propsMap = v as? Map<*, *> ?: emptyMap<Any?, Any?>()
                    val sanitizedProps = mutableMapOf<String, Any?>()
                    for ((propName, propVal) in propsMap) {
                        val nameStr = propName as? String ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val subMap = propVal as? Map<String, Any?> ?: continue
                        sanitizedProps[nameStr] = sanitizeOpenApiSchema(subMap)
                    }
                    cleaned["properties"] = sanitizedProps
                }
                "items" -> {
                    @Suppress("UNCHECKED_CAST")
                    val subMap = v as? Map<String, Any?>
                    if (subMap != null) {
                        cleaned["items"] = sanitizeOpenApiSchema(subMap)
                    }
                }
                "required" -> {
                    val reqList = (v as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    if (reqList.isNotEmpty()) {
                        cleaned["required"] = reqList
                    }
                }
                "enum" -> {
                    val enumList = (v as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                    if (enumList.isNotEmpty()) {
                        cleaned["enum"] = enumList
                    }
                }
                "description" -> {
                    cleaned["description"] = v.toString()
                }
            }
        }
        if (!cleaned.containsKey("type")) {
            cleaned["type"] = "OBJECT"
        }
        return cleaned
    }

    internal fun parseSuccessResponse(bodyStr: String): GeminiTurnResult {
        val root = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull()
            ?: return GeminiTurnResult.ServerError("Invalid JSON response from Gemini.")

        val usage = root["usageMetadata"] as? Map<*, *>
        val promptTokens = (usage?.get("promptTokenCount") as? Number)?.toInt() ?: 0
        val candidateTokens = (usage?.get("candidatesTokenCount") as? Number)?.toInt() ?: 0

        val promptFeedback = root["promptFeedback"] as? Map<*, *>
        if (promptFeedback?.get("blockReason") != null) {
            return GeminiTurnResult.Success(
                assistantText = "I can't help with that.",
                toolCalls = emptyList(),
                promptTokens = promptTokens,
                candidateTokens = candidateTokens
            )
        }

        val candidates = root["candidates"] as? List<*>
        val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            ?: return GeminiTurnResult.Success(
                assistantText = "I can't help with that.",
                toolCalls = emptyList(),
                promptTokens = promptTokens,
                candidateTokens = candidateTokens
            )

        val finishReason = firstCandidate["finishReason"] as? String
        if (finishReason.equals("SAFETY", ignoreCase = true) ||
            finishReason.equals("BLOCKLIST", ignoreCase = true) ||
            finishReason.equals("PROHIBITED_CONTENT", ignoreCase = true)
        ) {
            return GeminiTurnResult.Success(
                assistantText = "I can't help with that.",
                toolCalls = emptyList(),
                promptTokens = promptTokens,
                candidateTokens = candidateTokens
            )
        }

        val content = firstCandidate["content"] as? Map<*, *>
        val parts = (content?.get("parts") as? List<*>).orEmpty()

        val textChunks = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCallSpec>()

        for (part in parts) {
            val partMap = part as? Map<*, *> ?: continue
            val textVal = partMap["text"] as? String
            if (!textVal.isNullOrBlank()) {
                textChunks.add(textVal.trim())
            }
            val fnCall = partMap["functionCall"] as? Map<*, *>
            if (fnCall != null) {
                val fnName = (fnCall["name"] as? String)?.trim().orEmpty()
                if (fnName.isNotEmpty()) {
                    val rawArgs = (fnCall["args"] as? Map<*, *>).orEmpty()
                    val stringArgs = mutableMapOf<String, String>()
                    for ((k, v) in rawArgs) {
                        val keyStr = k as? String ?: continue
                        val valStr = when (v) {
                            null -> ""
                            is Number -> {
                                val d = v.toDouble()
                                if (d == d.toLong().toDouble()) d.toLong().toString() else v.toString()
                            }
                            else -> v.toString()
                        }
                        stringArgs[keyStr] = valStr
                    }
                    toolCalls.add(
                        ToolCallSpec(
                            id = "call_${UUID.randomUUID()}",
                            name = fnName,
                            args = stringArgs
                        )
                    )
                }
            }
        }

        val combinedText = textChunks.joinToString("\n").takeIf { it.isNotBlank() }
        return GeminiTurnResult.Success(
            assistantText = combinedText,
            toolCalls = toolCalls,
            promptTokens = promptTokens,
            candidateTokens = candidateTokens
        )
    }
}
