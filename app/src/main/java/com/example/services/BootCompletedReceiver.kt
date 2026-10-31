package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.JarvisApplication
import com.example.domain.model.RoutineTriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores scheduled daily routines after device reboot when setup is complete and API key exists.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val app = context.applicationContext as? JarvisApplication ?: return
        val container = app.appContainer
        if (!container.apiKeyStore.exists()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = container.settingsRepository.settingsFlow.first()
                if (!settings.setupComplete || settings.killSwitchActive) return@launch

                val enabledRoutines = container.routineRepository.routinesFlow.first()
                    .filter { it.enabled }

                enabledRoutines
                    .filter { it.triggerType == RoutineTriggerType.TIME_DAILY }
                    .forEach { routine ->
                        RoutineTriggerReceiver.scheduleDailyRoutineAlarm(
                            context = context,
                            routineId = routine.id,
                            timeHhMm = routine.triggerValue
                        )
                    }

                val hasHardwareRoutines = enabledRoutines.any {
                    it.triggerType == RoutineTriggerType.HEADSET_CONNECTED ||
                        it.triggerType == RoutineTriggerType.CHARGING_STARTED
                }
                RoutineTriggerReceiver.syncDynamicReceivers(context, hasHardwareRoutines)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_RESUME_WAKE_WORD_FROM_BOOT = "extra_resume_wake_word_from_boot"
    }
}
