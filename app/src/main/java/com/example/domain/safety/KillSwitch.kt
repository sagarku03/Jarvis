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

interface KillSwitch {
    val isHalted: StateFlow<Boolean>
    val killEvents: SharedFlow<KillSwitchEvent>

    fun triggerKillSwitch(reason: String = "User activated emergency KillSwitch")
    fun resetAfterKill()
    fun registerCancelCallback(callback: () -> Unit)
}

@Singleton
class KillSwitchImpl @Inject constructor() : KillSwitch {
    private val _isHalted = MutableStateFlow(false)
    override val isHalted: StateFlow<Boolean> = _isHalted.asStateFlow()

    private val _killEvents = MutableSharedFlow<KillSwitchEvent>(extraBufferCapacity = 8)
    override val killEvents: SharedFlow<KillSwitchEvent> = _killEvents.asSharedFlow()

    private val cancelCallbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun triggerKillSwitch(reason: String) {
        _isHalted.value = true
        cancelCallbacks.forEach { callback ->
            runCatching { callback.invoke() }
        }
        _killEvents.tryEmit(KillSwitchEvent(reason = reason))
    }

    override fun resetAfterKill() {
        _isHalted.value = false
    }

    override fun registerCancelCallback(callback: () -> Unit) {
        cancelCallbacks.add(callback)
    }
}
