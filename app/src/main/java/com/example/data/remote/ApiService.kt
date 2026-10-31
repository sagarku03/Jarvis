package com.example.data.remote

import com.google.firebase.auth.FirebaseAuth
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

data class WireMessageDto(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class WireToolDescriptorDto(
    val name: String,
    val description: String,
    val jsonSchema: String,
    val riskLevel: String
)

data class AgentStepRequestDto(
    val messages: List<WireMessageDto>,
    val tools: List<WireToolDescriptorDto>,
    val screenContext: String? = null,
    val languageCode: String = "hinglish"
)

data class WireToolCallDto(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

data class WireMemoryCandidateDto(
    val id: String,
    val category: String,
    val keyLabel: String,
    val contentValue: String,
    val sourceUtterance: String
)

data class AgentStepResponseDto(
    val assistantText: String? = null,
    val toolCalls: List<WireToolCallDto> = emptyList(),
    val memoryCandidates: List<WireMemoryCandidateDto> = emptyList()
)

data class SttRequestDto(
    val audioBase64: String,
    val sampleRateHz: Int = 16000,
    val languageCode: String = "hi-IN"
)

data class SttResponseDto(
    val text: String
)

data class TtsRequestDto(
    val text: String,
    val languageCode: String = "hi-IN",
    val voiceProfile: String = "jarvis-alpha-neural",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

/**
 * Backend proxy Retrofit contract (/v1/agent/step, /v1/stt, /v1/tts).
 * The app talks ONLY to our backend proxy (base URL from BuildConfig) and never embeds LLM API keys.
 */
interface ApiService {
    @POST("v1/agent/step")
    suspend fun agentStep(@Body request: AgentStepRequestDto): AgentStepResponseDto

    @POST("v1/stt")
    suspend fun speechToText(@Body request: SttRequestDto): SttResponseDto

    @POST("v1/tts")
    suspend fun textToSpeech(@Body request: TtsRequestDto): ResponseBody
}

/**
 * Attaches real Firebase Auth ID Token and Firebase App Check Token headers for backend verification.
 * Never sends hardcoded placeholder tokens in prod builds.
 */
class FirebaseProxyAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val idToken: String? = runCatching {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                com.google.android.gms.tasks.Tasks.await(
                    user.getIdToken(false),
                    5,
                    java.util.concurrent.TimeUnit.SECONDS
                ).token
            } else {
                null
            }
        }.getOrNull()

        val appCheckToken: String? = runCatching {
            com.google.android.gms.tasks.Tasks.await(
                com.google.firebase.appcheck.FirebaseAppCheck.getInstance().getAppCheckToken(false),
                5,
                java.util.concurrent.TimeUnit.SECONDS
            ).token
        }.getOrNull()

        val builder = original.newBuilder()
            .header("X-Client-Version", "JARVIS-Android-${com.example.BuildConfig.VERSION_NAME}")

        if (!idToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $idToken")
        } else if (com.example.BuildConfig.IS_DEV_FLAVOR) {
            builder.header("Authorization", "Bearer dev-local-unauthenticated")
        }

        if (!appCheckToken.isNullOrBlank()) {
            builder.header("X-Firebase-AppCheck", appCheckToken)
        } else if (com.example.BuildConfig.IS_DEV_FLAVOR) {
            builder.header("X-Firebase-AppCheck", "dev-local-appcheck")
        }

        return chain.proceed(builder.build())
    }
}
