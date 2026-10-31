package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Stage 3: Opt-in Boot Completed Receiver.
 * Starts VoiceForegroundService after device reboot ONLY if the user explicitly enabled
 * autoStartOnBoot and wakeWordEnabled in Settings.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? JarvisApplication ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // Phase 4: Reschedule all enabled TIME_DAILY routines after reboot or app update
                val routines = app.container.routineRepository.getAllRoutinesSnapshot()
                routines.filter { it.enabled && it.triggerType == com.example.domain.model.RoutineTriggerType.TIME_DAILY }
                    .forEach { routine ->
                        RoutineTriggerReceiver.scheduleDailyRoutineAlarm(
                            context = context.applicationContext,
                            routineId = routine.id,
                            timeHHmm = routine.triggerValue
                        )
                    }

                val settings = app.container.settingsRepository.getSnapshot()
                if (settings.autoStartOnBoot && settings.wakeWordEnabled && app.container.wakeWordDetector.isWakeWordConfigured) {
                    val serviceIntent = Intent(context, VoiceForegroundService::class.java).apply {
                        this.action = VoiceForegroundService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
