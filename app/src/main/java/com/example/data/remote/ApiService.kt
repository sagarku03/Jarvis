package com.example.data.remote

import com.example.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

data class AgentMessageDto(
    val role: String,
    val content: String,
    val toolName: String? = null
)

data class AgentToolDto(
    val name: String,
    val description: String,
    val jsonSchema: String,
    val riskLevel: String
)

data class AgentScreenContextDto(
    val packageName: String,
    val summaryText: String,
    val structuredJson: String = "[]"
)

data class AgentStepRequestDto(
    val messages: List<AgentMessageDto>,
    val tools: List<AgentToolDto>,
    val screenContext: AgentScreenContextDto? = null,
    val languageCode: String = "en-IN"
)

data class ToolCallDto(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

data class AgentStepResponseDto(
    val assistantText: String? = null,
    val toolCalls: List<ToolCallDto> = emptyList()
)

data class SttRequestDto(
    val audioBase64: String,
    val sampleRateHz: Int = 16000,
    val languageCode: String = "en-IN"
)

data class SttResponseDto(
    val transcript: String,
    val confidence: Float = 1.0f,
    val isFinal: Boolean = true
)

data class TtsRequestDto(
    val text: String,
    val languageCode: String = "en-IN",
    val voiceId: String = "en-IN-Neural2-D",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

interface ApiService {
    @POST("v1/agent/step")
    suspend fun agentStep(@Body request: AgentStepRequestDto): AgentStepResponseDto

    @POST("v1/stt")
    suspend fun speechToText(@Body request: SttRequestDto): SttResponseDto

    @POST("v1/tts")
    suspend fun synthesizeSpeech(@Body request: TtsRequestDto): ResponseBody
}

interface TokenProvider {
    fun getFirebaseIdToken(): String?
    fun getAppCheckToken(): String?
}

class AuthHeaderInterceptor(
    private val tokenProvider: TokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val idToken = tokenProvider.getFirebaseIdToken()
        val appCheckToken = tokenProvider.getAppCheckToken()

        val builder = original.newBuilder()
            .header("X-Jarvis-Mode", if (BuildConfig.STORE_SAFE_MODE) "STORE_SAFE" else "FULL_CONTROL")

        if (!idToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $idToken")
        } else if (BuildConfig.IS_DEV_FLAVOR) {
            builder.header("Authorization", "Bearer dev-local-unauthenticated")
        }

        if (!appCheckToken.isNullOrBlank()) {
            builder.header("X-Firebase-AppCheck", appCheckToken)
        } else if (BuildConfig.IS_DEV_FLAVOR) {
            builder.header("X-Firebase-AppCheck", "dev-local-appcheck")
        }

        return chain.proceed(builder.build())
    }
}
