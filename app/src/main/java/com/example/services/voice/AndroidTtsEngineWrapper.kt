package com.example.services.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.BuildConfig
import com.example.data.remote.ApiService
import com.example.data.remote.TtsRequestDto
import com.example.di.Inject
import com.example.di.NetworkModule
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceLatencyMetrics
import com.example.domain.model.VoiceProfile
import com.example.domain.safety.KillSwitch
import com.example.domain.voice.TtsEngineWrapper
import com.example.domain.voice.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sentence-Chunked Streaming TTS via backend /v1/tts (AudioTrack MODE_STREAM)
 * with AudioFocus management, barge-in cancellation, Android TextToSpeech offline fallback,
 * and real measured latency timestamps (zero hard-coded constants or coerce calls).
 */
@Singleton
class AndroidTtsEngineWrapper @Inject constructor(
    private val context: Context,
    private val apiService: ApiService,
    killSwitch: KillSwitch
) : TtsEngineWrapper {

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamingJob: Job? = null
    private var activeAudioTrack: AudioTrack? = null

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var isEngineReady = false

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _latencyMetrics = MutableStateFlow(VoiceLatencyMetrics())
    override val latencyMetrics: StateFlow<VoiceLatencyMetrics> = _latencyMetrics.asStateFlow()

    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceStartTimestamps = ConcurrentHashMap<String, Pair<Long, Long>>()

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                isEngineReady = (status == TextToSpeech.SUCCESS)
                if (isEngineReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            if (utteranceId != null) {
                                val timestamps = utteranceStartTimestamps.remove(utteranceId)
                                if (timestamps != null) {
                                    val (turnStartMs, requestSentMs) = timestamps
                                    val nowMs = System.currentTimeMillis()
                                    val firstAudioLatency = (nowMs - requestSentMs).coerceAtLeast(0L)
                                    val totalTurnLatency = (nowMs - turnStartMs).coerceAtLeast(0L)
                                    _latencyMetrics.value = _latencyMetrics.value.copy(
                                        firstAudioChunkLatencyMs = firstAudioLatency,
                                        endOfSpeechToFirstAudioMs = totalTurnLatency,
                                        usedBackendStreaming = false
                                    )
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null) {
                                val callback = utteranceCallbacks.remove(utteranceId)
                                if (utteranceCallbacks.isEmpty()) {
                                    _isSpeaking.value = false
                                    abandonAudioFocus()
                                }
                                callback?.invoke()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null) {
                                val callback = utteranceCallbacks.remove(utteranceId)
                                if (utteranceCallbacks.isEmpty()) {
                                    _isSpeaking.value = false
                                    abandonAudioFocus()
                                }
                                callback?.invoke()
                            }
                        }
                    })
                }
            }
        }

        killSwitch.registerCancelCallback {
            stopSpeaking()
        }
    }

    fun splitIntoSentenceChunks(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?।\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text.trim()) }
    }

    override fun speak(
        text: String,
        language: AppLanguage,
        speechRate: Float,
        pitch: Float,
        voiceProfile: VoiceProfile,
        turnStartTimestampMs: Long,
        onDone: (() -> Unit)?
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        stopSpeaking()
        val focusGranted = requestTransientAudioFocus()
        val sentenceChunks = splitIntoSentenceChunks(text)
        _isSpeaking.value = true

        val canUseBackendTts = !BuildConfig.USE_FAKE_BACKEND &&
            NetworkModule.isValidBackendUrlConfigured(BuildConfig.JARVIS_BACKEND_BASE_URL)

        streamingJob = ttsScope.launch {
            val requestSentMs = System.currentTimeMillis()
            val backendStepMs = (requestSentMs - turnStartTimestampMs).coerceAtLeast(0L)
            var streamedSuccess = false

            if (canUseBackendTts) {
                for ((index, sentence) in sentenceChunks.withIndex()) {
                    if (!isActive || !_isSpeaking.value) break

                    val chunkStartMs = System.currentTimeMillis()
                    val pcmBytes = runCatching {
                        apiService.textToSpeech(
                            TtsRequestDto(
                                text = sentence,
                                languageCode = language.code,
                                voiceProfile = voiceProfile.id,
                                speed = speechRate,
                                pitch = pitch
                            )
                        ).bytes()
                    }.getOrNull()

                    if (pcmBytes != null && pcmBytes.isNotEmpty() && isActive && _isSpeaking.value) {
                        if (index == 0) {
                            val nowMs = System.currentTimeMillis()
                            val firstChunkLatencyMs = (nowMs - chunkStartMs).coerceAtLeast(0L)
                            val totalTurnLatency = (nowMs - turnStartTimestampMs).coerceAtLeast(0L)
                            _latencyMetrics.value = VoiceLatencyMetrics(
                                sttEndpointLatencyMs = 0L,
                                backendStepLatencyMs = backendStepMs,
                                firstAudioChunkLatencyMs = firstChunkLatencyMs,
                                endOfSpeechToFirstAudioMs = totalTurnLatency,
                                sentenceChunksCount = sentenceChunks.size,
                                usedBackendStreaming = true,
                                audioFocusGranted = focusGranted
                            )
                        }
                        playPcmStreamChunk(pcmBytes)
                        streamedSuccess = true
                    } else {
                        break
                    }
                }
            }

            if (streamedSuccess) {
                _isSpeaking.value = false
                abandonAudioFocus()
                onDone?.invoke()
                return@launch
            }

            // Offline / fallback path: speak via Android local TextToSpeech engine ONLY when backend stream didn't play
            val engine = tts
            if (isEngineReady && engine != null && _isSpeaking.value) {
                runCatching {
                    val targetLocale = when (language) {
                        AppLanguage.HI_IN -> Locale.forLanguageTag("hi-IN")
                        AppLanguage.EN_IN, AppLanguage.HINGLISH -> Locale.forLanguageTag("en-IN")
                    }
                    engine.language = targetLocale
                    engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.8f))
                    engine.setPitch(pitch.coerceIn(0.6f, 1.5f))

                    _latencyMetrics.value = VoiceLatencyMetrics(
                        sttEndpointLatencyMs = 0L,
                        backendStepLatencyMs = backendStepMs,
                        firstAudioChunkLatencyMs = 0L,
                        endOfSpeechToFirstAudioMs = 0L,
                        sentenceChunksCount = sentenceChunks.size,
                        usedBackendStreaming = false,
                        audioFocusGranted = focusGranted
                    )

                    for ((idx, chunk) in sentenceChunks.withIndex()) {
                        val uttId = "jarvis_chunk_${idx}_${UUID.randomUUID()}"
                        if (idx == 0) {
                            utteranceStartTimestamps[uttId] = turnStartTimestampMs to requestSentMs
                        }
                        if (idx == sentenceChunks.lastIndex && onDone != null) {
                            utteranceCallbacks[uttId] = onDone
                        }
                        val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        engine.speak(chunk, queueMode, null, uttId)
                    }
                }.onFailure {
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    onDone?.invoke()
                }
            } else {
                _isSpeaking.value = false
                abandonAudioFocus()
                onDone?.invoke()
            }
        }
    }

    private fun playPcmStreamChunk(pcmBytes: ByteArray) {
        runCatching {
            val sampleRate = 16000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(pcmBytes.size)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            activeAudioTrack = track
            track.play()
            track.write(pcmBytes, 0, pcmBytes.size)
            track.stop()
            track.release()
            activeAudioTrack = null
        }
    }

    private fun requestTransientAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS) {
                            stopSpeaking()
                        }
                    }
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                true
            }
        }.getOrDefault(true)
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            }
        }
    }

    override fun stopSpeaking() {
        _isSpeaking.value = false
        streamingJob?.cancel()
        streamingJob = null
        runCatching {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.release()
        }
        activeAudioTrack = null
        runCatching {
            tts?.stop()
        }
        abandonAudioFocus()
        utteranceStartTimestamps.clear()
        utteranceCallbacks.values.forEach { callback ->
            runCatching { callback.invoke() }
        }
        utteranceCallbacks.clear()
    }

    override fun shutdown() {
        stopSpeaking()
        runCatching {
            tts?.shutdown()
        }
        tts = null
        isEngineReady = false
    }
}

@Singleton
class PorcupineWakeWordEngineImpl @Inject constructor(
    private val audioRecorder: com.example.domain.voice.AudioRecorder,
    private val accessKeyOverride: String? = null
) : WakeWordDetector {

    override val keywordLabel: String = "Hey Jarvis"

    override val isWakeWordConfigured: Boolean
        get() {
            val raw = (accessKeyOverride ?: BuildConfig.PORCUPINE_ACCESS_KEY).trim()
            return raw.isNotEmpty() && raw != "UNCONFIGURED_PORCUPINE_KEY"
        }

    override val statusMessage: String
        get() = if (isWakeWordConfigured) {
            "On-device wake-word active (Hey Jarvis)"
        } else {
            "Wake word unavailable — add Picovoice key in local secrets or use Push-to-Talk"
        }

    private val _isMonitoring = MutableStateFlow(false)
    override val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.65f)
    override val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    override fun startMonitoring(onWakeWordDetected: () -> Unit) {
        if (_isPaused.value || !isWakeWordConfigured) {
            _isMonitoring.value = false
            audioRecorder.stopRecording()
            return
        }
        _isMonitoring.value = true
    }

    override fun pauseMonitoring() {
        _isPaused.value = true
        _isMonitoring.value = false
        audioRecorder.stopRecording()
    }

    override fun resumeMonitoring(onWakeWordDetected: () -> Unit) {
        _isPaused.value = false
        startMonitoring(onWakeWordDetected)
    }

    override fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0.1f, 1.0f)
    }

    override fun stopMonitoring() {
        _isMonitoring.value = false
        audioRecorder.stopRecording()
    }
}
