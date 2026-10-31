package com.example.domain.voice

import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceLatencyMetrics
import com.example.domain.model.VoiceProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Stage 2: 16kHz mono PCM audio recorder with Silence Detection (Endpointing)
 * and adaptive Noise Gate.
 * Privacy invariant: implementations MUST NEVER write raw audio bytes to disk or logs.
 */
interface AudioRecorder {
    val isRecording: StateFlow<Boolean>
    val rmsDbLevel: StateFlow<Float>
    val noiseFloorDb: StateFlow<Float>
    val silenceEndpointTriggered: StateFlow<Boolean>

    fun startRecording(
        enableSilenceEndpointing: Boolean = true,
        silenceDurationMs: Long = 1100L,
        onSilenceEndpoint: (() -> Unit)? = null,
        onPcmFrame: (ShortArray) -> Unit
    ): Boolean

    fun stopRecording()
}

interface SpeechRecognizerWrapper {
    val isListening: StateFlow<Boolean>
    val partialTranscript: StateFlow<String>
    val finalTranscript: StateFlow<String>
    val errorState: StateFlow<String?>
    val lastSttLatencyMs: StateFlow<Long>

    fun startListening(
        language: AppLanguage,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    )

    fun stopListening()
    fun clearTranscript()
}

/**
 * Stage 2: Sentence-chunked streaming neural TTS via /v1/tts (AudioTrack low-latency playback)
 * with automatic Android TextToSpeech offline fallback and AudioFocus management.
 */
interface TtsEngineWrapper {
    val isSpeaking: StateFlow<Boolean>
    val latencyMetrics: StateFlow<VoiceLatencyMetrics>

    fun speak(
        text: String,
        language: AppLanguage,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        voiceProfile: VoiceProfile = VoiceProfile.JARVIS_ALPHA,
        turnStartTimestampMs: Long = System.currentTimeMillis(),
        onDone: (() -> Unit)? = null
    )

    fun stopSpeaking()
    fun shutdown()
}

/**
 * Stage 3: On-device Wake-Word Engine interface ("Hey Jarvis" Porcupine implementation).
 * Respects sensitivity (0.1f..1.0f) and paused state (never records when paused).
 */
interface WakeWordDetector {
    val isMonitoring: StateFlow<Boolean>
    val isPaused: StateFlow<Boolean>
    val sensitivity: StateFlow<Float>
    val keywordLabel: String
    val isWakeWordConfigured: Boolean
        get() = true
    val statusMessage: String
        get() = "Ready"

    fun startMonitoring(onWakeWordDetected: () -> Unit)
    fun pauseMonitoring()
    fun resumeMonitoring(onWakeWordDetected: () -> Unit)
    fun setSensitivity(value: Float)
    fun stopMonitoring()
}
