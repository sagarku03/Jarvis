package com.example.data.remote

import com.example.data.local.ApiKeyStore
import com.example.data.local.DiagnosticsLogger
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
import android.os.SystemClock
import android.util.Base64
import com.example.domain.model.ConnectionPingDetail
import com.example.domain.model.DualConnectionReport
import com.example.domain.model.FullSelfTestReport
import com.example.domain.model.SelfTestStepResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class KeyVerificationResult {
    data class Valid(
        val availableModels: List<String>,
        val defaultModelId: String
    ) : KeyVerificationResult()

    data class Invalid(val message: String) : KeyVerificationResult()
    data class RateLimited(val message: String, val availableModels: List<String> = listOf("gemini-3.8-flash", "gemini-3.8-live")) : KeyVerificationResult()
    data class NetworkError(val message: String) : KeyVerificationResult()
}

sealed class GeminiTurnResult {
    data class Success(
        val assistantText: String?,
        val toolCalls: List<ToolCallSpec>,
        val promptTokens: Int = 0,
        val candidateTokens: Int = 0,
        val rawPartsJson: String? = null
    ) : GeminiTurnResult()

    data class KeyRejected(val message: String) : GeminiTurnResult()
    data class BadRequest(val message: String) : GeminiTurnResult()
    data class RateLimited(val message: String, val retryAfterSec: Long? = null) : GeminiTurnResult()
    data class ServerError(val message: String) : GeminiTurnResult()
    data class Timeout(val message: String) : GeminiTurnResult()
    data class Offline(val message: String) : GeminiTurnResult()
}

/**
 * Direct Google Gemini REST API client (`https://generativelanguage.googleapis.com`).
 *
 * Security invariants:
 * - Always sends the user's API key in the `x-goog-api-key` HTTP header.
 * - Never appends `?key=` to any URL.
 * - Never logs the API key or raw user chat texts.
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
        const val DEFAULT_TEXT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_VOICE_MODEL = "gemini-3.8-live"
        const val DEFAULT_TTS_MODEL = "gemini-3.8-flash-tts"
        const val DEFAULT_FALLBACK_MODEL = "gemini-3.8-flash"
        const val DEFAULT_STABLE_FALLBACK_MODEL = "gemini-3.8-flash"
        const val DEFAULT_LIGHT_FALLBACK_MODEL = "gemini-3.8-flash"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ALLOWED_SCHEMA_KEYS = setOf("type", "properties", "required", "description", "enum", "items")

        private val EXCLUDED_MODEL_PATTERNS = listOf(
            "preview", "exp", "experimental", "tts", "image", "embedding", "veo", "imagen", "aqa"
        )

        fun friendlyModelName(rawId: String): String {
            val clean = rawId.removePrefix("models/").lowercase(Locale.US)
            return when {
                clean.contains("3.8-live") || clean.contains("3-8-live") || clean == "gemini-3.8-live" ->
                    "Gemini 3.8 Live (Voice)"
                clean.contains("3.8-flash") || clean.contains("3-8-flash") || clean == "gemini-3.8-flash" ->
                    "Gemini 3.8 Flash (Text)"
                clean.contains("flash-lite") || clean.contains("flash_lite") || clean.contains("8b") ->
                    "Gemini Flash-Lite — fastest"
                clean.contains("flash") ->
                    "Gemini Flash — fast (recommended)"
                clean.contains("pro") ->
                    "Gemini Pro — smartest, slower"
                else -> rawId.removePrefix("models/")
            }
        }

        fun getNextFallbackModel(failedModel: String): String {
            val clean = failedModel.removePrefix("models/").lowercase(Locale.US)
            return when {
                clean.contains("live") || clean.contains("3.8-live") || clean.contains("3-8-live") -> DEFAULT_TEXT_MODEL
                else -> DEFAULT_TEXT_MODEL
            }
        }

        fun selectDefaultModel(models: List<String>): String {
            return models.firstOrNull { it.contains("3.8-flash", ignoreCase = true) }
                ?: models.firstOrNull { it.contains("3.8", ignoreCase = true) }
                ?: models.firstOrNull { id ->
                    val lower = id.lowercase()
                    lower.contains("flash") && !lower.contains("lite") && !lower.contains("pro") && !lower.contains("2.0")
                } ?: models.firstOrNull { it.lowercase().contains("flash") && !it.lowercase().contains("2.0") }
                ?: models.firstOrNull { !it.lowercase().contains("2.0") }
                ?: models.firstOrNull()
                ?: DEFAULT_TEXT_MODEL
        }

        fun selectFallbackModel(models: List<String>, currentModel: String): String {
            val others = models.filterNot { it.equals(currentModel, ignoreCase = true) || it.contains("2.0") }
            return others.firstOrNull { it.contains("3.8-flash", ignoreCase = true) }
                ?: others.firstOrNull { it.lowercase().contains("flash") && !it.lowercase().contains("lite") }
                ?: others.firstOrNull { it.lowercase().contains("flash") }
                ?: others.firstOrNull()
                ?: DEFAULT_FALLBACK_MODEL
        }
    }

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val listAdapter = moshi.adapter<List<*>>(List::class.java)

    private val unsupportedThinkingModels = ConcurrentHashMap.newKeySet<String>()

    private val streamingOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun verifyKeyAndListModels(rawKey: String): KeyVerificationResult = withContext(Dispatchers.IO) {
        val cleanKey = rawKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
        }

        val baseUrl = baseUrlProvider().trimEnd('/')
        val url = "$baseUrl/v1beta/models"
        val verifyClient = okHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        try {
            verifyClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> {
                        val parsed = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull().orEmpty()
                        val rawModels = (parsed["models"] as? List<*>).orEmpty()
                        val supportedModels = rawModels.mapNotNull { item ->
                            val modelMap = item as? Map<*, *> ?: return@mapNotNull null
                            val name = (modelMap["name"] as? String)?.removePrefix("models/")?.trim()
                                ?: return@mapNotNull null
                            val methods = (modelMap["supportedGenerationMethods"] as? List<*>)
                                ?.mapNotNull { it as? String }
                                .orEmpty()
                            val lowerName = name.lowercase(Locale.US)
                            val isExcluded = EXCLUDED_MODEL_PATTERNS.any { lowerName.contains(it) }
                            val isDead = lowerName.contains("2.0") || lowerName.contains("2-0")
                            if ("generateContent" in methods && !isExcluded && !isDead) name else null
                        }.distinct()

                        val finalModels = supportedModels.ifEmpty { listOf(DEFAULT_TEXT_MODEL, DEFAULT_VOICE_MODEL) }
                        val defaultModel = selectDefaultModel(finalModels)
                        DiagnosticsLogger.log("GeminiClient", "Key verified. Available stable models: ${finalModels.size}")
                        KeyVerificationResult.Valid(
                            availableModels = finalModels,
                            defaultModelId = defaultModel
                        )
                    }
                    401 -> KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
                    403 -> {
                        val isKeyError = isKeyAuthError(bodyStr)
                        if (isKeyError) {
                            KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
                        } else {
                            KeyVerificationResult.Invalid("Access denied for this Gemini key")
                        }
                    }
                    429 -> KeyVerificationResult.RateLimited("Key is valid but its rate limit has been reached")
                    else -> KeyVerificationResult.Invalid("This key is not valid or not allowed for the Gemini API")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SocketTimeoutException) {
            KeyVerificationResult.NetworkError("Verification timed out. Check your internet connection.")
        } catch (_: UnknownHostException) {
            KeyVerificationResult.NetworkError("No internet connection")
        } catch (_: ConnectException) {
            KeyVerificationResult.NetworkError("No internet connection")
        } catch (_: IOException) {
            KeyVerificationResult.NetworkError("No internet connection")
        } catch (e: Exception) {
            KeyVerificationResult.Invalid("Verification failed: ${e.localizedMessage ?: "error"}")
        }
    }

    suspend fun generateStructuredJson(
        modelId: String,
        systemInstruction: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val result = generateContent(
            modelId = modelId,
            systemInstruction = systemInstruction,
            messages = listOf(ChatMessage(role = MessageRole.USER, content = userPrompt)),
            tools = emptyList()
        )
        if (result is GeminiTurnResult.Success) {
            result.assistantText
        } else {
            null
        }
    }

    suspend fun testDualConnection(
        textModelId: String = DEFAULT_TEXT_MODEL,
        ttsModelId: String = DEFAULT_TTS_MODEL,
        voiceName: String = "Puck",
        overrideKey: String? = null
    ): DualConnectionReport = withContext(Dispatchers.IO) {
        val apiKey = overrideKey?.trim() ?: apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext DualConnectionReport(
                isOverallSuccess = false,
                textPing = ConnectionPingDetail(textModelId, false, 401, 0L, "No API key configured", "Key missing"),
                ttsPing = ConnectionPingDetail(ttsModelId, false, 401, 0L, "No API key configured", "Key missing")
            )
        }

        val baseUrl = baseUrlProvider().trimEnd('/')
        val testClient = okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        // 1. Text Model Ping
        val cleanTextModel = textModelId.removePrefix("models/").trim().ifEmpty { DEFAULT_TEXT_MODEL }
        val textUrl = "$baseUrl/v1beta/models/$cleanTextModel:generateContent"
        val textPayload = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to "Say OK"))
                )
            )
        )
        val textRequest = Request.Builder()
            .url(textUrl)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(mapAdapter.toJson(textPayload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val textStart = SystemClock.elapsedRealtime()
        val textPing = try {
            testClient.newCall(textRequest).execute().use { response ->
                val latency = SystemClock.elapsedRealtime() - textStart
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull()
                    val candidates = parsed?.get("candidates") as? List<*>
                    val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                    val content = firstCandidate?.get("content") as? Map<*, *>
                    val parts = content?.get("parts") as? List<*>
                    val firstPart = parts?.firstOrNull() as? Map<*, *>
                    val reply = (firstPart?.get("text") as? String)?.trim() ?: "OK"
                    ConnectionPingDetail(
                        modelId = cleanTextModel,
                        success = true,
                        httpCode = response.code,
                        latencyMs = latency,
                        details = "HTTP ${response.code} OK: \"$reply\" (${latency}ms)"
                    )
                } else {
                    val err = parseErrorDetails(bodyStr)
                    ConnectionPingDetail(
                        modelId = cleanTextModel,
                        success = false,
                        httpCode = response.code,
                        latencyMs = latency,
                        details = "HTTP ${response.code} ${err.status}",
                        errorSnippet = err.message.ifBlank { "HTTP ${response.code}" }
                    )
                }
            }
        } catch (e: Exception) {
            val latency = SystemClock.elapsedRealtime() - textStart
            ConnectionPingDetail(
                modelId = cleanTextModel,
                success = false,
                httpCode = 0,
                latencyMs = latency,
                details = "Network failure",
                errorSnippet = e.localizedMessage ?: "Connection error"
            )
        }

        // 2. TTS Voice Model Ping
        val cleanTtsModel = ttsModelId.removePrefix("models/").trim().ifEmpty { DEFAULT_TTS_MODEL }
        val ttsUrl = "$baseUrl/v1beta/models/$cleanTtsModel:generateContent"
        val ttsPayload = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to "Hello"))
                )
            ),
            "generationConfig" to mapOf(
                "responseModalities" to listOf("AUDIO"),
                "speechConfig" to mapOf(
                    "voiceConfig" to mapOf(
                        "prebuiltVoiceConfig" to mapOf(
                            "voiceName" to voiceName
                        )
                    )
                )
            )
        )
        val ttsRequest = Request.Builder()
            .url(ttsUrl)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(mapAdapter.toJson(ttsPayload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val ttsStart = SystemClock.elapsedRealtime()
        val ttsPing = try {
            testClient.newCall(ttsRequest).execute().use { response ->
                val latency = SystemClock.elapsedRealtime() - ttsStart
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull()
                    val candidates = parsed?.get("candidates") as? List<*>
                    val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                    val content = firstCandidate?.get("content") as? Map<*, *>
                    val parts = (content?.get("parts") as? List<*>).orEmpty()
                    var audioBytes = 0
                    for (p in parts) {
                        val pMap = p as? Map<*, *> ?: continue
                        val inlineData = pMap["inlineData"] as? Map<*, *> ?: continue
                        val b64 = inlineData["data"] as? String
                        if (!b64.isNullOrEmpty()) {
                            audioBytes = Base64.decode(b64, Base64.DEFAULT).size
                            break
                        }
                    }
                    ConnectionPingDetail(
                        modelId = cleanTtsModel,
                        success = audioBytes > 0,
                        httpCode = response.code,
                        latencyMs = latency,
                        details = if (audioBytes > 0) "HTTP ${response.code} OK: $audioBytes audio bytes ($voiceName, ${latency}ms)" else "HTTP 200 but 0 audio bytes",
                        errorSnippet = if (audioBytes > 0) null else "No audio in inlineData"
                    )
                } else {
                    val err = parseErrorDetails(bodyStr)
                    ConnectionPingDetail(
                        modelId = cleanTtsModel,
                        success = false,
                        httpCode = response.code,
                        latencyMs = latency,
                        details = "HTTP ${response.code} ${err.status}",
                        errorSnippet = err.message.ifBlank { "HTTP ${response.code}" }
                    )
                }
            }
        } catch (e: Exception) {
            val latency = SystemClock.elapsedRealtime() - ttsStart
            ConnectionPingDetail(
                modelId = cleanTtsModel,
                success = false,
                httpCode = 0,
                latencyMs = latency,
                details = "Network failure",
                errorSnippet = e.localizedMessage ?: "Connection error"
            )
        }

        DualConnectionReport(
            isOverallSuccess = textPing.success && ttsPing.success,
            textPing = textPing,
            ttsPing = ttsPing
        )
    }

    suspend fun testConnection(overrideKey: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val report = testDualConnection(
            textModelId = DEFAULT_TEXT_MODEL,
            ttsModelId = DEFAULT_TTS_MODEL,
            voiceName = "Puck",
            overrideKey = overrideKey
        )
        if (report.isOverallSuccess) {
            Result.success("Working (${report.textPing.details}; ${report.ttsPing.details})")
        } else {
            val failureDetail = when {
                !report.textPing.success -> "${report.textPing.modelId}: ${report.textPing.errorSnippet ?: report.textPing.details}"
                !report.ttsPing.success -> "${report.ttsPing.modelId}: ${report.ttsPing.errorSnippet ?: report.ttsPing.details}"
                else -> "Connection failed"
            }
            Result.failure(Exception(failureDetail))
        }
    }

    suspend fun runSelfTestSuite(
        apiKeyOverride: String? = null,
        onStepProgress: ((SelfTestStepResult) -> Unit)? = null
    ): FullSelfTestReport = withContext(Dispatchers.IO) {
        val apiKey = apiKeyOverride?.trim() ?: apiKeyStore.get()?.trim().orEmpty()
        val steps = mutableListOf<SelfTestStepResult>()

        if (apiKey.isEmpty()) {
            val failStep = SelfTestStepResult(
                stepIndex = 1,
                title = "Gemini API Key Check",
                passed = false,
                detail = "No API key configured in Settings"
            )
            steps.add(failStep)
            onStepProgress?.invoke(failStep)
            return@withContext FullSelfTestReport(steps = steps, allPassed = false)
        }

        // 1. ListModels
        val step1Start = SystemClock.elapsedRealtime()
        val step1 = try {
            val res = verifyKeyAndListModels(apiKey)
            val latency = SystemClock.elapsedRealtime() - step1Start
            when (res) {
                is KeyVerificationResult.Valid -> SelfTestStepResult(
                    stepIndex = 1,
                    title = "ListModels API",
                    passed = true,
                    detail = "HTTP 200: Found ${res.availableModels.size} available models",
                    latencyMs = latency
                )
                is KeyVerificationResult.Invalid -> SelfTestStepResult(
                    stepIndex = 1,
                    title = "ListModels API",
                    passed = false,
                    detail = "Key invalid: ${res.message}",
                    latencyMs = latency
                )
                is KeyVerificationResult.RateLimited -> SelfTestStepResult(
                    stepIndex = 1,
                    title = "ListModels API",
                    passed = false,
                    detail = "Rate limited: ${res.message}",
                    latencyMs = latency
                )
                is KeyVerificationResult.NetworkError -> SelfTestStepResult(
                    stepIndex = 1,
                    title = "ListModels API",
                    passed = false,
                    detail = "Network error: ${res.message}",
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            SelfTestStepResult(
                stepIndex = 1,
                title = "ListModels API",
                passed = false,
                detail = "Exception: ${e.localizedMessage ?: "error"}",
                latencyMs = SystemClock.elapsedRealtime() - step1Start
            )
        }
        steps.add(step1)
        onStepProgress?.invoke(step1)

        // 2. Text generateContent
        val step2Start = SystemClock.elapsedRealtime()
        val step2 = try {
            val res = generateContent(
                modelId = DEFAULT_TEXT_MODEL,
                systemInstruction = "You are a test assistant.",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Say OK")),
                tools = emptyList()
            )
            val latency = SystemClock.elapsedRealtime() - step2Start
            when (res) {
                is GeminiTurnResult.Success -> SelfTestStepResult(
                    stepIndex = 2,
                    title = "Text Reasoning ($DEFAULT_TEXT_MODEL)",
                    passed = true,
                    detail = "HTTP 200 OK: \"${res.assistantText?.trim()?.take(40)}\"",
                    latencyMs = latency
                )
                else -> SelfTestStepResult(
                    stepIndex = 2,
                    title = "Text Reasoning ($DEFAULT_TEXT_MODEL)",
                    passed = false,
                    detail = "Failed: $res",
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            SelfTestStepResult(
                stepIndex = 2,
                title = "Text Reasoning ($DEFAULT_TEXT_MODEL)",
                passed = false,
                detail = "Exception: ${e.localizedMessage}",
                latencyMs = SystemClock.elapsedRealtime() - step2Start
            )
        }
        steps.add(step2)
        onStepProgress?.invoke(step2)

        // 3. Streaming SSE
        val step3Start = SystemClock.elapsedRealtime()
        val step3 = try {
            var chunkCount = 0
            val res = streamGenerateContent(
                modelId = DEFAULT_TEXT_MODEL,
                systemInstruction = "You are a test assistant.",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Count 1 to 3")),
                tools = emptyList(),
                onTextChunk = { chunkCount++ }
            )
            val latency = SystemClock.elapsedRealtime() - step3Start
            if (res is GeminiTurnResult.Success && chunkCount > 0) {
                SelfTestStepResult(
                    stepIndex = 3,
                    title = "SSE Streaming ($DEFAULT_TEXT_MODEL)",
                    passed = true,
                    detail = "HTTP 200 OK: Received $chunkCount streaming chunks",
                    latencyMs = latency
                )
            } else {
                SelfTestStepResult(
                    stepIndex = 3,
                    title = "SSE Streaming ($DEFAULT_TEXT_MODEL)",
                    passed = false,
                    detail = "Stream finished with $chunkCount chunks: $res",
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            SelfTestStepResult(
                stepIndex = 3,
                title = "SSE Streaming ($DEFAULT_TEXT_MODEL)",
                passed = false,
                detail = "Exception: ${e.localizedMessage}",
                latencyMs = SystemClock.elapsedRealtime() - step3Start
            )
        }
        steps.add(step3)
        onStepProgress?.invoke(step3)

        // 4. TTS 6 Voices Test
        val step4Start = SystemClock.elapsedRealtime()
        val voices = listOf("Puck", "Charon", "Fenrir", "Aoede", "Kore", "Leda")
        val voiceResults = mutableMapOf<String, Int>()
        var allVoicesOk = true
        var ttsErrorReason = ""

        val baseUrl = baseUrlProvider().trimEnd('/')
        val ttsTestClient = okHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        for (vName in voices) {
            val ttsPayload = mapOf(
                "contents" to listOf(
                    mapOf("role" to "user", "parts" to listOf(mapOf("text" to "Hello")))
                ),
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO"),
                    "speechConfig" to mapOf(
                        "voiceConfig" to mapOf(
                            "prebuiltVoiceConfig" to mapOf("voiceName" to vName)
                        )
                    )
                )
            )
            val req = Request.Builder()
                .url("$baseUrl/v1beta/models/$DEFAULT_TTS_MODEL:generateContent")
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(mapAdapter.toJson(ttsPayload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                ttsTestClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val parsed = runCatching { mapAdapter.fromJson(body) }.getOrNull()
                        val candidates = parsed?.get("candidates") as? List<*>
                        val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                        val content = firstCandidate?.get("content") as? Map<*, *>
                        val parts = (content?.get("parts") as? List<*>).orEmpty()
                        var bytes = 0
                        for (p in parts) {
                            val inlineData = (p as? Map<*, *>)?.get("inlineData") as? Map<*, *> ?: continue
                            val b64 = inlineData["data"] as? String
                            if (!b64.isNullOrEmpty()) {
                                bytes = Base64.decode(b64, Base64.DEFAULT).size
                                break
                            }
                        }
                        voiceResults[vName] = bytes
                        if (bytes == 0) allVoicesOk = false
                    } else {
                        allVoicesOk = false
                        ttsErrorReason = "HTTP ${resp.code} on voice $vName"
                    }
                }
            } catch (e: Exception) {
                allVoicesOk = false
                ttsErrorReason = "$vName error: ${e.localizedMessage}"
            }
        }
        val latency4 = SystemClock.elapsedRealtime() - step4Start
        val step4 = if (allVoicesOk) {
            val summary = voiceResults.entries.joinToString(", ") { "${it.key}:${it.value}B" }
            SelfTestStepResult(
                stepIndex = 4,
                title = "Natural TTS (6 Voices: Puck, Charon, Fenrir, Aoede, Kore, Leda)",
                passed = true,
                detail = "HTTP 200: All 6 voices synthesized audio ($summary)",
                latencyMs = latency4
            )
        } else {
            SelfTestStepResult(
                stepIndex = 4,
                title = "Natural TTS (6 Voices: Puck, Charon, Fenrir, Aoede, Kore, Leda)",
                passed = false,
                detail = if (ttsErrorReason.isNotBlank()) ttsErrorReason else "Some voices failed to synthesize audio: $voiceResults",
                latencyMs = latency4
            )
        }
        steps.add(step4)
        onStepProgress?.invoke(step4)

        // 5. Gemini Live WebSocket Handshake
        val step5Start = SystemClock.elapsedRealtime()
        val wsLatch = java.util.concurrent.CountDownLatch(1)
        var wsConnected = false
        var wsDetail = ""
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val wsRequest = Request.Builder().url(wsUrl).build()

        val wsClient = okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        val webSocket = wsClient.newWebSocket(wsRequest, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                val setupFrame = mapOf(
                    "setup" to mapOf(
                        "model" to "models/gemini-3.8-live",
                        "generationConfig" to mapOf(
                            "responseModalities" to listOf("AUDIO"),
                            "speechConfig" to mapOf(
                                "voiceConfig" to mapOf(
                                    "prebuiltVoiceConfig" to mapOf("voiceName" to "Puck")
                                )
                            )
                        )
                    )
                )
                webSocket.send(mapAdapter.toJson(setupFrame))
                wsConnected = true
                wsDetail = "WebSocket Handshake Connected (HTTP ${response.code})"
                webSocket.close(1000, "Self-test finished")
                wsLatch.countDown()
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                wsConnected = false
                wsDetail = "Handshake failed: ${t.localizedMessage ?: "error"} (HTTP ${response?.code ?: 0})"
                wsLatch.countDown()
            }
        })

        wsLatch.await(5, TimeUnit.SECONDS)
        val latency5 = SystemClock.elapsedRealtime() - step5Start
        val step5 = SelfTestStepResult(
            stepIndex = 5,
            title = "Gemini Live WebSocket Handshake",
            passed = wsConnected,
            detail = if (wsDetail.isNotBlank()) wsDetail else if (wsConnected) "Live WebSocket Handshake Success" else "WebSocket handshake timed out",
            latencyMs = latency5
        )
        steps.add(step5)
        onStepProgress?.invoke(step5)

        val allPassed = steps.all { it.passed }
        FullSelfTestReport(
            steps = steps,
            allPassed = allPassed
        )
    }

    suspend fun generateContent(
        modelId: String,
        systemInstruction: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        allowThinkingConfig: Boolean = true,
        includeTools: Boolean = true
    ): GeminiTurnResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext GeminiTurnResult.KeyRejected("Your Gemini key was rejected. Update it in Settings.")
        }

        var activeModel = modelId.removePrefix("models/").trim().ifEmpty { DEFAULT_TEXT_MODEL }
        val baseUrl = baseUrlProvider().trimEnd('/')
        var url = "$baseUrl/v1beta/models/$activeModel:generateContent"

        val effectiveTools = if (includeTools) tools else emptyList()
        val payloadMap = buildGenerateContentPayload(
            modelId = activeModel,
            systemInstruction = systemInstruction,
            messages = messages,
            tools = effectiveTools,
            allowThinking = allowThinkingConfig && (activeModel !in unsupportedThinkingModels)
        )
        var requestJson = mapAdapter.toJson(payloadMap)

        var attempt = 0
        val maxRetries = 3

        while (attempt <= maxRetries) {
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                var retryDelayMs: Long? = null
                val result: GeminiTurnResult? = okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyStr = response.body?.string().orEmpty()

                    when {
                        code == 200 -> {
                            parseSuccessResponse(bodyStr)
                        }
                        code == 401 -> {
                            DiagnosticsLogger.log("GeminiClient", "HTTP 401 key rejected")
                            GeminiTurnResult.KeyRejected("Your Gemini key was rejected. Update it in Settings.")
                        }
                        code == 403 -> {
                            val isKeyRejected = isKeyAuthError(bodyStr)
                            DiagnosticsLogger.log("GeminiClient", "HTTP 403 key error=$isKeyRejected")
                            if (isKeyRejected) {
                                GeminiTurnResult.KeyRejected("Your Gemini key was rejected. Update it in Settings.")
                            } else {
                                val err = parseErrorDetails(bodyStr)
                                GeminiTurnResult.BadRequest("Access denied: ${err.message}")
                            }
                        }
                        code == 404 -> {
                            DiagnosticsLogger.log("GeminiClient", "HTTP 404 model not found ($activeModel). Response: $bodyStr")
                            val nextModel = getNextFallbackModel(activeModel)
                            DiagnosticsLogger.log("GeminiClient", "Switching to fallback ($nextModel).")
                            if (activeModel.equals(nextModel, ignoreCase = true)) {
                                val err = parseErrorDetails(bodyStr)
                                val desc = if (err.message.isNotBlank()) err.message else "Model $activeModel not found (404)."
                                GeminiTurnResult.BadRequest("Gemini model not found: $desc")
                            } else {
                                activeModel = nextModel
                                url = "$baseUrl/v1beta/models/$activeModel:generateContent"
                                val fallbackPayload = buildGenerateContentPayload(
                                    modelId = activeModel,
                                    systemInstruction = systemInstruction,
                                    messages = messages,
                                    tools = effectiveTools,
                                    allowThinking = false
                                )
                                requestJson = mapAdapter.toJson(fallbackPayload)
                                retryDelayMs = 100L
                                null
                            }
                        }
                        code == 400 -> {
                            val err = parseErrorDetails(bodyStr)
                            DiagnosticsLogger.log("GeminiClient", "HTTP 400 INVALID_ARGUMENT on $activeModel: ${err.status} - ${err.message}. Response: $bodyStr")

                            // If error was about thinking config, remember that for this model
                            if (err.message.contains("thinking", ignoreCase = true) || err.message.contains("thinkingConfig", ignoreCase = true)) {
                                unsupportedThinkingModels.add(activeModel)
                            }

                            // Retry once with simplified request (no thinking config, then without tools)
                            if (allowThinkingConfig) {
                                return@withContext generateContent(
                                    modelId = activeModel,
                                    systemInstruction = systemInstruction,
                                    messages = messages,
                                    tools = tools,
                                    allowThinkingConfig = false,
                                    includeTools = true
                                )
                            } else if (includeTools && tools.isNotEmpty()) {
                                return@withContext generateContent(
                                    modelId = activeModel,
                                    systemInstruction = systemInstruction,
                                    messages = messages,
                                    tools = tools,
                                    allowThinkingConfig = false,
                                    includeTools = false
                                )
                            }
                            val cleanMsg = if (err.message.isNotBlank()) {
                                "Gemini API error (400): ${err.message}"
                            } else {
                                "Bad request to Gemini API (400). Please check model parameters."
                            }
                            GeminiTurnResult.BadRequest(cleanMsg)
                        }
                        code == 429 -> {
                            val retrySec = response.header("Retry-After")?.toLongOrNull()
                            if (attempt < 1 && retrySec != null && retrySec <= 2) {
                                retryDelayMs = retrySec * 1000L
                                null
                            } else {
                                GeminiTurnResult.RateLimited(
                                    "Rate limit reached for your key. Try again in a minute.",
                                    retryAfterSec = retrySec
                                )
                            }
                        }
                        code in 500..599 -> {
                            DiagnosticsLogger.log("GeminiClient", "HTTP $code server error on $activeModel. Response: $bodyStr")
                            if (activeModel != DEFAULT_TEXT_MODEL) {
                                activeModel = DEFAULT_TEXT_MODEL
                                url = "$baseUrl/v1beta/models/$activeModel:generateContent"
                                val fallbackPayload = buildGenerateContentPayload(
                                    modelId = activeModel,
                                    systemInstruction = systemInstruction,
                                    messages = messages,
                                    tools = effectiveTools,
                                    allowThinking = false
                                )
                                requestJson = mapAdapter.toJson(fallbackPayload)
                                retryDelayMs = 50L
                                null
                            } else {
                                GeminiTurnResult.ServerError("Google's AI is busy right now. Try again in a moment.")
                            }
                        }
                        else -> {
                            GeminiTurnResult.ServerError("Gemini service is temporarily unavailable.")
                        }
                    }
                }

                if (result != null) {
                    return@withContext result
                }
                attempt++
                if (retryDelayMs != null) {
                    delay(retryDelayMs)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: SocketTimeoutException) {
                DiagnosticsLogger.log("GeminiClient", "Socket timeout")
                return@withContext GeminiTurnResult.Timeout("That took too long. Try again.")
            } catch (_: UnknownHostException) {
                return@withContext GeminiTurnResult.Offline("You appear to be offline.")
            } catch (_: ConnectException) {
                return@withContext GeminiTurnResult.Offline("You appear to be offline.")
            } catch (_: IOException) {
                return@withContext GeminiTurnResult.Offline("You appear to be offline.")
            }
        }

        GeminiTurnResult.RateLimited("Rate limit reached for your key. Try again in a minute.")
    }

    suspend fun streamGenerateContent(
        modelId: String,
        systemInstruction: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        onTextChunk: (String) -> Unit
    ): GeminiTurnResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext GeminiTurnResult.KeyRejected("Your Gemini key was rejected. Update it in Settings.")
        }

        val activeModel = modelId.removePrefix("models/").trim().ifEmpty { DEFAULT_TEXT_MODEL }
        val baseUrl = baseUrlProvider().trimEnd('/')
        val url = "$baseUrl/v1beta/models/$activeModel:streamGenerateContent?alt=sse"

        val payloadMap = buildGenerateContentPayload(
            modelId = activeModel,
            systemInstruction = systemInstruction,
            messages = messages,
            tools = tools,
            allowThinking = activeModel !in unsupportedThinkingModels
        )
        val requestJson = mapAdapter.toJson(payloadMap)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            streamingOkHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    DiagnosticsLogger.log("GeminiClient", "streamGenerateContent failed HTTP $code on $activeModel. Response: $errorBody")
                    // Fall back to non-streaming generateContent so all recovery and classification rules apply
                    return@withContext generateContent(
                        modelId = activeModel,
                        systemInstruction = systemInstruction,
                        messages = messages,
                        tools = tools
                    )
                }

                val source = response.body?.byteStream() ?: return@withContext generateContent(
                    modelId = activeModel,
                    systemInstruction = systemInstruction,
                    messages = messages,
                    tools = tools
                )

                val reader = BufferedReader(InputStreamReader(source))
                val accumulatedText = StringBuilder()
                val toolCalls = mutableListOf<ToolCallSpec>()
                val allParts = mutableListOf<Any?>()
                var promptTokens = 0
                var candidateTokens = 0

                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val json = line.removePrefix("data: ").trim()
                        if (json.isNotEmpty() && json != "[DONE]") {
                            val parsed = runCatching { mapAdapter.fromJson(json) }.getOrNull()
                            if (parsed != null) {
                                val usage = parsed["usageMetadata"] as? Map<*, *>
                                if (usage != null) {
                                    promptTokens = (usage["promptTokenCount"] as? Number)?.toInt() ?: promptTokens
                                    candidateTokens = (usage["candidatesTokenCount"] as? Number)?.toInt() ?: candidateTokens
                                }
                                val candidates = parsed["candidates"] as? List<*>
                                val first = candidates?.firstOrNull() as? Map<*, *>
                                val content = first?.get("content") as? Map<*, *>
                                val parts = (content?.get("parts") as? List<*>).orEmpty()
                                for (p in parts) {
                                    allParts.add(p)
                                    val partMap = p as? Map<*, *> ?: continue
                                    val text = partMap["text"] as? String
                                    if (!text.isNullOrEmpty()) {
                                        accumulatedText.append(text)
                                        onTextChunk(text)
                                    }
                                    val fnCall = partMap["functionCall"] as? Map<*, *>
                                    if (fnCall != null) {
                                        val fnName = (fnCall["name"] as? String)?.trim().orEmpty()
                                        if (fnName.isNotEmpty()) {
                                            val rawArgs = (fnCall["args"] as? Map<*, *>).orEmpty()
                                            val stringArgs = mutableMapOf<String, String>()
                                            for ((k, v) in rawArgs) {
                                                val keyStr = k as? String ?: continue
                                                stringArgs[keyStr] = v?.toString().orEmpty()
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
                            }
                        }
                    }
                    line = reader.readLine()
                }

                val finalPartsJson = runCatching { listAdapter.toJson(allParts) }.getOrNull()
                GeminiTurnResult.Success(
                    assistantText = accumulatedText.toString().takeIf { it.isNotBlank() },
                    toolCalls = toolCalls,
                    promptTokens = promptTokens,
                    candidateTokens = candidateTokens,
                    rawPartsJson = finalPartsJson
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SocketTimeoutException) {
            GeminiTurnResult.Timeout("That took too long. Try again.")
        } catch (_: UnknownHostException) {
            GeminiTurnResult.Offline("You appear to be offline.")
        } catch (_: ConnectException) {
            GeminiTurnResult.Offline("You appear to be offline.")
        } catch (_: IOException) {
            GeminiTurnResult.Offline("You appear to be offline.")
        }
    }

    internal fun buildGenerateContentPayload(
        modelId: String,
        systemInstruction: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        allowThinking: Boolean = true
    ): Map<String, Any?> {
        val contents = mutableListOf<MutableMap<String, Any?>>()

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER, MessageRole.SYSTEM -> {
                    contents.add(
                        mutableMapOf(
                            "role" to "user",
                            "parts" to mutableListOf<Map<String, Any?>>(mapOf("text" to msg.content))
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    // Exact preservation of raw model parts if present
                    val rawParts = msg.rawModelPartsJson?.let { json ->
                        runCatching {
                            val parsed = listAdapter.fromJson(json) as? List<*>
                            parsed?.mapNotNull { it as? Map<String, Any?> }
                        }.getOrNull()
                    }

                    if (!rawParts.isNullOrEmpty()) {
                        contents.add(
                            mutableMapOf(
                                "role" to "model",
                                "parts" to rawParts.toMutableList()
                            )
                        )
                    } else {
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
                        contents.add(
                            mutableMapOf(
                                "role" to "model",
                                "parts" to assistantParts
                            )
                        )
                    }
                }
                MessageRole.TOOL -> {
                    val fnName = msg.toolName?.takeIf { it.isNotBlank() } ?: "tool_result"
                    val fnResponsePart = mapOf(
                        "functionResponse" to mapOf(
                            "name" to fnName,
                            "response" to mapOf("result" to msg.content)
                        )
                    )

                    // For parallel tool calls, group all consecutive functionResponse parts into ONE single user turn
                    val lastTurn = contents.lastOrNull()
                    if (lastTurn != null && lastTurn["role"] == "user") {
                        @Suppress("UNCHECKED_CAST")
                        val existingParts = lastTurn["parts"] as? MutableList<Map<String, Any?>>
                        val hasFunctionResponse = existingParts?.any { it.containsKey("functionResponse") } == true
                        if (hasFunctionResponse) {
                            existingParts.add(fnResponsePart)
                        } else {
                            contents.add(
                                mutableMapOf(
                                    "role" to "user",
                                    "parts" to mutableListOf(fnResponsePart)
                                )
                            )
                        }
                    } else {
                        contents.add(
                            mutableMapOf(
                                "role" to "user",
                                "parts" to mutableListOf(fnResponsePart)
                            )
                        )
                    }
                }
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

        val generationConfig = mutableMapOf<String, Any?>(
            "temperature" to 0.4,
            "maxOutputTokens" to 1024
        )

        // Minimum thinking configuration per model family
        if (allowThinking) {
            val lowerModel = modelId.lowercase(Locale.US)
            if (!lowerModel.contains("live") && (lowerModel.contains("3-") || lowerModel.contains("gemini-3") || lowerModel.contains("3.8"))) {
                generationConfig["thinkingConfig"] = mapOf("thinkingLevel" to "low")
            } else if (lowerModel.contains("2.5")) {
                generationConfig["thinkingConfig"] = mapOf("thinkingBudget" to 0)
            }
        }

        val result = mutableMapOf<String, Any?>(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to contents,
            "generationConfig" to generationConfig
        )

        if (functionDeclarations.isNotEmpty()) {
            result["tools"] = listOf(
                mapOf("functionDeclarations" to functionDeclarations)
            )
        }

        return result
    }

    internal fun sanitizeOpenApiSchema(raw: Map<String, Any?>?): Map<String, Any?> {
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

        val rawPartsJson = runCatching { listAdapter.toJson(parts) }.getOrNull()

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
            candidateTokens = candidateTokens,
            rawPartsJson = rawPartsJson
        )
    }

    private data class ParsedError(
        val code: Int = 0,
        val status: String = "",
        val message: String = "",
        val reason: String = ""
    )

    private fun parseErrorDetails(bodyStr: String): ParsedError {
        val root = runCatching { mapAdapter.fromJson(bodyStr) }.getOrNull()
        val errMap = root?.get("error") as? Map<*, *> ?: return ParsedError()
        val code = (errMap["code"] as? Number)?.toInt() ?: 0
        val status = (errMap["status"] as? String).orEmpty()
        val message = (errMap["message"] as? String).orEmpty()
        val details = (errMap["details"] as? List<*>).orEmpty()
        val reason = details.firstNotNullOfOrNull { item ->
            val detailMap = item as? Map<*, *>
            detailMap?.get("reason") as? String
        }.orEmpty()
        return ParsedError(code = code, status = status, message = message, reason = reason)
    }

    private fun isKeyAuthError(bodyStr: String): Boolean {
        val err = parseErrorDetails(bodyStr)
        if (err.reason == "API_KEY_INVALID" || err.reason == "API_KEY_SERVICE_BLOCKED") return true
        val combined = "${err.status} ${err.message} ${err.reason}".lowercase(Locale.US)
        return combined.contains("api_key_invalid") ||
            combined.contains("invalid key") ||
            combined.contains("api key not valid") ||
            (combined.contains("permission_denied") && (combined.contains("api key") || combined.contains("key invalid") || combined.contains("invalid key")))
    }
}
