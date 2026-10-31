package com.example.domain.safety

import com.example.di.Inject
import com.example.di.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

data class KillSwitchEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String
)

/**
 * Global emergency kill-switch that halts all microphone capture, TTS output,
 * UI automation gestures, and in-flight agent loops immediately.
 */
@Singleton
class KillSwitch @Inject constructor() {

    private val _isKilled = MutableStateFlow(false)
    val isKilled: StateFlow<Boolean> = _isKilled.asStateFlow()

    val isHalted: StateFlow<Boolean>
        get() = isKilled

    private val _lastReason = MutableStateFlow<String?>(null)
    val lastReason: StateFlow<String?> = _lastReason.asStateFlow()

    private val _killEvents = MutableSharedFlow<KillSwitchEvent>(extraBufferCapacity = 8)
    val killEvents: SharedFlow<KillSwitchEvent> = _killEvents.asSharedFlow()

    private val cancelCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun trigger(reason: String = "User activated emergency KillSwitch") {
        _lastReason.value = reason
        _isKilled.value = true
        cancelCallbacks.forEach { callback ->
            runCatching { callback.invoke() }
        }
        _killEvents.tryEmit(KillSwitchEvent(reason = reason))
    }

    fun triggerKillSwitch(reason: String = "User activated emergency KillSwitch") {
        trigger(reason)
    }

    fun reset() {
        _lastReason.value = null
        _isKilled.value = false
    }

    fun resetAfterKill() {
        reset()
    }

    fun registerCancelCallback(callback: () -> Unit) {
        cancelCallbacks.add(callback)
    }
}
