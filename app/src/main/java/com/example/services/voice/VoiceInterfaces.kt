package com.example.services.voice

import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for 16kHz mono PCM microphone capture used ONLY by on-device wake word engine
 * while VoiceSession is IDLE. Never used concurrently with SpeechRecognizer.
 */
interface PcmAudioRecorder {
    val isRecording: StateFlow<Boolean>
    val rmsEnergyLevel: StateFlow<Float>

    fun startRecording(
        onPcmFrame: (ShortArray) -> Unit = {},
        onSilenceEndpointDetected: () -> Unit = {}
    ): Boolean

    fun stopRecording()
}

/**
 * Contract for Speech-to-Text (STT) with live partial transcripts, RMS level from recognizer,
 * and explicit error code reporting.
 */
interface SpeechRecognizerWrapper {
    val isListening: StateFlow<Boolean>
    val partialTranscript: StateFlow<String>
    val rmsDbLevel: StateFlow<Float>
    val lastErrorCode: StateFlow<Int?>
    val lastErrorMessage: StateFlow<String>
    val lastSttLatencyMs: StateFlow<Long>

    fun startListening(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    )

    fun stopListening()
    fun cancel()
    fun shutdown()
}

/**
 * Contract for Text-to-Speech (TTS) supporting sentence-chunked neural backend playback
 * with offline high-quality Android TTS fallback.
 */
interface TtsEngineWrapper {
    val isSpeaking: StateFlow<Boolean>
    val firstAudioLatencyMs: StateFlow<Long>
    val isUsingOfflineFallback: StateFlow<Boolean>

    fun speak(
        text: String,
        language: AppLanguage,
        voiceProfile: VoiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        onComplete: () -> Unit = {}
    )

    fun stop()
    fun shutdown()
}

/**
 * Contract for on-device wake word detection ("Hey Jarvis").
 * Active ONLY when VoiceSession is IDLE and both Porcupine access key and .ppn model are present.
 */
interface WakeWordEngine {
    val isWakeWordConfigured: Boolean
    val isMonitoring: StateFlow<Boolean>
    val isPaused: StateFlow<Boolean>
    val sensitivity: StateFlow<Float>

    fun startMonitoring(onWakeWordDetected: () -> Unit)
    fun stopMonitoring()
    fun setPaused(paused: Boolean)
    fun setSensitivity(value: Float)
    fun release()
}
