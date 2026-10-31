package com.example.services.voice

import com.example.domain.model.AppLanguage
import com.example.domain.model.DeviceVoiceOption
import kotlinx.coroutines.flow.StateFlow

interface PcmAudioRecorder {
    val isRecording: StateFlow<Boolean>
    val rmsEnergyLevel: StateFlow<Float>

    fun startRecording(
        onPcmFrame: (ShortArray) -> Unit = {},
        onSilenceEndpointDetected: () -> Unit = {}
    ): Boolean

    fun stopRecording()
}

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
 * On-device Android `TextToSpeech` contract.
 * Exposes real installed device voices from `TextToSpeech.getVoices()`.
 */
interface TtsEngineWrapper {
    val isSpeaking: StateFlow<Boolean>
    val firstAudioLatencyMs: StateFlow<Long>
    val isUsingOfflineFallback: StateFlow<Boolean>
    val availableVoices: StateFlow<List<DeviceVoiceOption>>

    fun refreshAvailableVoices(language: AppLanguage = AppLanguage.EN_IN)

    fun speak(
        text: String,
        language: AppLanguage,
        selectedVoiceName: String = "",
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        onComplete: () -> Unit = {}
    )

    fun stop()
    fun shutdown()
}

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
