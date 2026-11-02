package com.example.services.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import com.example.data.local.ApiKeyStore
import com.example.data.local.DiagnosticsLogger
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.DeviceVoiceOption
import com.example.domain.model.VoiceEngineOption
import com.example.domain.repository.SettingsRepository
import com.squareup.moshi.Moshi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface VoiceProvider {
    val id: String
    val displayName: String
    val isNatural: Boolean
    suspend fun speak(
        sentence: String,
        language: AppLanguage,
        voiceId: String,
        speed: Float,
        onAudioStarted: () -> Unit,
        onAudioFinished: () -> Unit
    ): Boolean
    fun stop()
}

@Singleton
class TtsManager @Inject constructor(
    private val context: Context,
    private val deviceTts: AndroidTtsEngineWrapper,
    private val apiKeyStore: ApiKeyStore,
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val NATURAL_FAILURE_BACKOFF_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val FRIENDLY_NATURAL_VOICES = listOf(
            // Male voices
            DeviceVoiceOption("Puck", "Puck — Dynamic (Male)", "en-US", 4, false),
            DeviceVoiceOption("Charon", "Charon — Deep (Male)", "en-US", 4, false),
            DeviceVoiceOption("Fenrir", "Fenrir — Authoritative (Male)", "en-US", 4, false),
            // Female voices
            DeviceVoiceOption("Aoede", "Aoede — Warm (Female)", "en-US", 4, false),
            DeviceVoiceOption("Kore", "Kore — Calm (Female)", "en-US", 4, false),
            DeviceVoiceOption("Leda", "Leda — Clear (Female)", "en-US", 4, false)
        )

        fun resolveLiveVoiceName(raw: String, gender: String = "MALE"): String {
            val clean = raw.trim()
            if (clean.isBlank() || clean.equals("default", ignoreCase = true)) {
                return if (gender.equals("FEMALE", ignoreCase = true)) "Aoede" else "Puck"
            }
            val match = com.example.domain.model.GeminiVoice.entries.firstOrNull {
                it.voiceName.equals(clean, ignoreCase = true) || clean.startsWith(it.voiceName, ignoreCase = true)
            }
            if (match != null) return match.voiceName

            val lower = clean.lowercase(Locale.US)
            return when {
                lower.contains("charon") -> "Charon"
                lower.contains("fenrir") -> "Fenrir"
                lower.contains("puck") -> "Puck"
                lower.contains("aoede") -> "Aoede"
                lower.contains("leda") -> "Leda"
                lower.contains("kore") -> "Kore"
                lower.contains("female") && (lower.contains("warm") || lower.contains("3")) -> "Aoede"
                lower.contains("female") && lower.contains("clear") -> "Leda"
                lower.contains("female") -> "Kore"
                lower.contains("male") && (lower.contains("deep") || lower.contains("2")) -> "Charon"
                lower.contains("male") && lower.contains("authoritative") -> "Fenrir"
                lower.contains("male") -> "Puck"
                gender.equals("FEMALE", ignoreCase = true) -> "Aoede"
                else -> "Puck"
            }
        }

        fun cleanTextForSpeech(raw: String): String {
            if (raw.isBlank()) return ""
            var text = raw
            // Remove code fences, JSON blocks, markdown links, URLs
            text = text.replace(Regex("```[\\s\\S]*?```"), " ")
            text = text.replace(Regex("\\{[^{}]*\"[a-zA-Z0-9_]+\"\\s*:[^{}]*\\}"), " ")
            text = text.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            text = text.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
            text = text.replace(Regex("[*_`~#<>|]+"), " ")
            text = text.replace(Regex("[\\p{So}\\p{Cn}\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), " ")

            // Expand common abbreviations / units / symbols
            text = text.replace(Regex("\\b(\\d+)%"), "$1 percent")
            text = text.replace(Regex("\\$(\\d+)"), "$1 dollars")
            text = text.replace(Regex("₹(\\d+)"), "$1 rupees")
            text = text.replace(Regex("\\bmin\\b", RegexOption.IGNORE_CASE), "minutes")
            text = text.replace(Regex("\\bsec\\b", RegexOption.IGNORE_CASE), "seconds")
            text = text.replace(Regex("\\s+"), " ").trim()

            // Keep confirmation messages concise (<= 2 sentences)
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            return if (sentences.size > 2 && (text.contains("Confirmed") || text.contains("Cancelled") || text.contains("Stopping"))) {
                sentences.take(2).joinToString(" ")
            } else {
                text
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskCacheDir = File(context.cacheDir, "speech_cache").apply { mkdirs() }
    private val memoryPcmCache = ConcurrentHashMap<String, ByteArray>()
    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter(Map::class.java)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isUsingFallbackChip = MutableStateFlow(false)
    val isUsingFallbackChip: StateFlow<Boolean> = _isUsingFallbackChip.asStateFlow()

    private val _fallbackReason = MutableStateFlow<String?>(null)
    val fallbackReason: StateFlow<String?> = _fallbackReason.asStateFlow()

    private val _firstAudioLatencyMs = MutableStateFlow(0L)
    val firstAudioLatencyMs: StateFlow<Long> = _firstAudioLatencyMs.asStateFlow()

    @Volatile
    private var lastNaturalFailureTimeMs: Long = 0L

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    private var activePlaybackJob: Job? = null
    private val sentenceQueue = ConcurrentLinkedQueue<String>()

    fun getAvailableVoices(): List<DeviceVoiceOption> {
        return FRIENDLY_NATURAL_VOICES + deviceTts.availableVoices.value
    }

    fun stop() {
        activePlaybackJob?.cancel()
        sentenceQueue.clear()
        stopActiveAudioTrack()
        deviceTts.stop()
        _isSpeaking.value = false
    }

    private fun stopActiveAudioTrack() {
        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
    }

    fun speakFullReply(
        text: String,
        language: AppLanguage,
        selectedVoiceName: String,
        speed: Float,
        onAudioStarted: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val cleaned = cleanTextForSpeech(text)
        if (cleaned.isBlank()) {
            onComplete()
            return
        }

        stop()
        val sentences = cleaned.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(cleaned) }

        activePlaybackJob = scope.launch {
            playSentenceList(
                sentences = sentences,
                language = language,
                voiceName = selectedVoiceName,
                speed = speed,
                onAudioStarted = onAudioStarted,
                onComplete = onComplete
            )
        }
    }

    fun testVoice(
        voiceName: String,
        gender: String = "MALE",
        onComplete: () -> Unit = {}
    ) {
        val sampleText = "Namaste, main Jarvis hoon. Aapki kya madad kar sakta hoon?"
        stop()
        activePlaybackJob = scope.launch {
            val resolvedVoice = resolveLiveVoiceName(voiceName, gender)
            val settings = settingsRepository.settingsFlow.first()
            val chosenVoiceModel = run {
                val tts = settings.selectedTtsModelId.ifBlank { "gemini-3.8-flash-tts" }
                if (tts.contains("live") || tts.contains("2.0")) "gemini-3.8-flash-tts" else tts
            }
            _isSpeaking.value = true
            val naturalSuccess = trySpeakGeminiAudio(
                sentence = sampleText,
                voiceName = resolvedVoice,
                modelId = chosenVoiceModel,
                onAudioStarted = {
                    _isSpeaking.value = true
                    _isUsingFallbackChip.value = false
                }
            )
            if (naturalSuccess) {
                _isUsingFallbackChip.value = false
                _isSpeaking.value = false
                onComplete()
            } else {
                _isUsingFallbackChip.value = true
                speakDeviceVoice(
                    sentence = sampleText,
                    language = settings.language,
                    voiceName = voiceName,
                    speed = settings.speechSpeed,
                    onAudioStarted = {
                        _isSpeaking.value = true
                    },
                    onFinished = {
                        _isSpeaking.value = false
                        onComplete()
                    }
                )
            }
        }
    }

    fun queueStreamingSentence(
        sentence: String,
        language: AppLanguage,
        selectedVoiceName: String,
        speed: Float,
        onAudioStarted: () -> Unit = {},
        onSentenceFinished: () -> Unit = {}
    ) {
        val cleaned = cleanTextForSpeech(sentence)
        if (cleaned.isBlank()) return

        sentenceQueue.add(cleaned)
        if (activePlaybackJob == null || activePlaybackJob?.isActive == false) {
            activePlaybackJob = scope.launch {
                while (sentenceQueue.isNotEmpty()) {
                    val next = sentenceQueue.poll() ?: break
                    playSingleSentence(
                        sentence = next,
                        language = language,
                        voiceName = selectedVoiceName,
                        speed = speed,
                        onAudioStarted = onAudioStarted,
                        onFinished = onSentenceFinished
                    )
                }
            }
        }
    }

    private suspend fun playSentenceList(
        sentences: List<String>,
        language: AppLanguage,
        voiceName: String,
        speed: Float,
        onAudioStarted: () -> Unit,
        onComplete: () -> Unit
    ) {
        var firstAudioTriggered = false
        for (sentence in sentences) {
            playSingleSentence(
                sentence = sentence,
                language = language,
                voiceName = voiceName,
                speed = speed,
                onAudioStarted = {
                    if (!firstAudioTriggered) {
                        firstAudioTriggered = true
                        onAudioStarted()
                    }
                },
                onFinished = {}
            )
        }
        _isSpeaking.value = false
        onComplete()
    }

    private suspend fun playSingleSentence(
        sentence: String,
        language: AppLanguage,
        voiceName: String,
        speed: Float,
        onAudioStarted: () -> Unit,
        onFinished: () -> Unit
    ) {
        val settings = settingsRepository.settingsFlow.first()
        val preferNatural = settings.voiceEngine == VoiceEngineOption.NATURAL_GOOGLE
        val canUseNatural = preferNatural && (System.currentTimeMillis() - lastNaturalFailureTimeMs > NATURAL_FAILURE_BACKOFF_MS)

        val startTime = SystemClock.elapsedRealtime()

        if (canUseNatural) {
            val chosenVoiceName = resolveLiveVoiceName(
                raw = voiceName.ifBlank { settings.selectedLiveVoiceName }.ifBlank { settings.selectedVoiceName },
                gender = settings.selectedVoiceGender
            )
            val chosenVoiceModel = run {
                val tts = settings.selectedTtsModelId.ifBlank { "gemini-3.8-flash-tts" }
                if (tts.contains("live") || tts.contains("2.0")) "gemini-3.8-flash-tts" else tts
            }

            val naturalSuccess = trySpeakGeminiAudio(
                sentence = sentence,
                voiceName = chosenVoiceName,
                modelId = chosenVoiceModel,
                onAudioStarted = {
                    _isSpeaking.value = true
                    _isUsingFallbackChip.value = false
                    _fallbackReason.value = null
                    val latency = SystemClock.elapsedRealtime() - startTime
                    _firstAudioLatencyMs.value = latency
                    DiagnosticsLogger.logVoiceEvent("TTS_PLAY", "Natural voice ($chosenVoiceName / $chosenVoiceModel) playing", latency)
                    onAudioStarted()
                }
            )

            if (naturalSuccess) {
                _isUsingFallbackChip.value = false
                _fallbackReason.value = null
                onFinished()
                return
            } else {
                lastNaturalFailureTimeMs = System.currentTimeMillis()
                _isUsingFallbackChip.value = true
                DiagnosticsLogger.logVoiceEvent("TTS_FALLBACK", "Natural voice failed; falling back to phone voice for 60s")
            }
        } else if (preferNatural) {
            _isUsingFallbackChip.value = true
        } else {
            _isUsingFallbackChip.value = false
        }

        // Honest Device Voice Fallback
        speakDeviceVoice(sentence, language, voiceName, speed, onAudioStarted, onFinished)
    }

    private fun stripWavHeaderIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        ) {
            for (i in 12 until bytes.size - 8) {
                if (bytes[i] == 'd'.code.toByte() &&
                    bytes[i + 1] == 'a'.code.toByte() &&
                    bytes[i + 2] == 't'.code.toByte() &&
                    bytes[i + 3] == 'a'.code.toByte()
                ) {
                    val dataStart = i + 8
                    if (dataStart < bytes.size) {
                        return bytes.copyOfRange(dataStart, bytes.size)
                    }
                }
            }
            return bytes.copyOfRange(44, bytes.size)
        }
        return bytes
    }

    private suspend fun trySpeakGeminiAudio(
        sentence: String,
        voiceName: String,
        modelId: String = "gemini-3.8-flash-tts",
        onAudioStarted: () -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) return@withContext false

        // Check cache first for instant response (< 1.2s target)
        val cleanModel = if (modelId.contains("live") || modelId.contains("2.0")) "gemini-3.8-flash-tts" else modelId
        val cacheKey = "${cleanModel}_${voiceName}_${sentence.hashCode()}"
        val cachedPcm = memoryPcmCache[cacheKey] ?: runCatching {
            val file = File(diskCacheDir, "$cacheKey.pcm")
            if (file.exists()) file.readBytes() else null
        }.getOrNull()

        if (cachedPcm != null) {
            _isUsingFallbackChip.value = false
            _fallbackReason.value = null
            playPcmAudio(cachedPcm, onAudioStarted)
            return@withContext true
        }

        val promptPayload = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to sentence))
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

        val bodyJson = mapAdapter.toJson(promptPayload)
        val initialModel = cleanModel.removePrefix("models/").trim().ifEmpty { "gemini-3.8-flash-tts" }
        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(initialModel)
        if (!modelsToTry.contains("gemini-3.8-flash-tts")) {
            modelsToTry.add("gemini-3.8-flash-tts")
        }

        for (activeModel in modelsToTry) {
            try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val (success, is404) = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        DiagnosticsLogger.log("TtsManager", "HTTP ${response.code} error on TTS model $activeModel: $errBody")
                        _fallbackReason.value = "HTTP ${response.code} on $activeModel"
                        return@use Pair(false, response.code == 404)
                    }
                    val rawBody = response.body?.string().orEmpty()
                    val parsed = runCatching { mapAdapter.fromJson(rawBody) }.getOrNull() ?: return@use Pair(false, false)
                    val candidates = parsed["candidates"] as? List<*>
                    val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                    val content = firstCandidate?.get("content") as? Map<*, *>
                    val parts = (content?.get("parts") as? List<*>).orEmpty()

                    var rawAudioBytes: ByteArray? = null
                    for (p in parts) {
                        val pMap = p as? Map<*, *> ?: continue
                        val inlineData = pMap["inlineData"] as? Map<*, *>
                        if (inlineData != null) {
                            val dataBase64 = inlineData["data"] as? String
                            if (!dataBase64.isNullOrEmpty()) {
                                rawAudioBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                break
                            }
                        }
                    }

                    if (rawAudioBytes != null && rawAudioBytes.isNotEmpty()) {
                        val pcmBytes = stripWavHeaderIfNeeded(rawAudioBytes)
                        memoryPcmCache[cacheKey] = pcmBytes
                        runCatching { File(diskCacheDir, "$cacheKey.pcm").writeBytes(pcmBytes) }
                        _isUsingFallbackChip.value = false
                        _fallbackReason.value = null
                        playPcmAudio(pcmBytes, onAudioStarted)
                        Pair(true, false)
                    } else {
                        Pair(false, false)
                    }
                }

                if (success) {
                    _isUsingFallbackChip.value = false
                    return@withContext true
                }
                if (!is404) {
                    break
                }
                DiagnosticsLogger.log("TtsManager", "Voice model $activeModel returned 404, falling back...")
            } catch (_: Exception) {
                break
            }
        }
        false
    }

    private suspend fun playPcmAudio(pcmBytes: ByteArray, onAudioStarted: () -> Unit) = withContext(Dispatchers.IO) {
        val sampleRate = 24000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, pcmBytes.size)

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        activeAudioTrack = track
        track.play()
        onAudioStarted()

        val chunkSize = 2048
        var offset = 0
        while (offset < pcmBytes.size && activeAudioTrack == track) {
            val length = minOf(chunkSize, pcmBytes.size - offset)
            track.write(pcmBytes, offset, length)
            offset += length
        }

        // Wait for buffer to finish playing
        val playDurationMs = (pcmBytes.size / (sampleRate * 2.0)) * 1000.0
        delay(playDurationMs.toLong().coerceAtLeast(100L))
        stopActiveAudioTrack()
    }

    private suspend fun speakDeviceVoice(
        sentence: String,
        language: AppLanguage,
        voiceName: String,
        speed: Float,
        onAudioStarted: () -> Unit,
        onFinished: () -> Unit
    ) = withContext(Dispatchers.Main) {
        var completed = false
        deviceTts.speak(
            text = sentence,
            language = language,
            selectedVoiceName = voiceName,
            speed = speed,
            pitch = 1.0f,
            onComplete = {
                completed = true
                onFinished()
            }
        )
        onAudioStarted()
        while (!completed && deviceTts.isSpeaking.value) {
            delay(50)
        }
    }
}
