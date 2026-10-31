package com.example.services.voice

import com.example.di.Inject
import com.example.di.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Free Mode WakeWordEngine implementation.
 * Zero paid Picovoice/Porcupine SDK dependency.
 * Reports `isWakeWordConfigured = false` so the Settings UI displays a clear
 * "Wake word not configured" badge and never opens the microphone in the background.
 */
@Singleton
class FreeWakeWordEngineImpl @Inject constructor() : WakeWordEngine {

    private val _isMonitoring = MutableStateFlow(false)
    override val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.65f)
    override val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    override val isWakeWordConfigured: Boolean = false

    override fun startMonitoring(onWakeWordDetected: () -> Unit) {
        _isMonitoring.value = false
    }

    override fun stopMonitoring() {
        _isMonitoring.value = false
    }

    override fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        _isMonitoring.value = false
    }

    override fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0.1f, 1.0f)
    }

    override fun release() {
        _isMonitoring.value = false
    }
}
