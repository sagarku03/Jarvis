package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.JarvisApplication
import com.example.domain.model.RoutineTriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Stage 6: Routine Trigger BroadcastReceiver for scheduled Time alarms, Charging connected,
 * and Headset connected events. Respects Quiet Hours before speaking proactive routines.
 */
class RoutineTriggerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_ROUTINE = "com.example.services.ACTION_TRIGGER_ROUTINE"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"

        fun scheduleDailyRoutineAlarm(context: Context, routineId: Long, timeHHmm: String) {
            runCatching {
                val parts = timeHHmm.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
                val intent = Intent(context, RoutineTriggerReceiver::class.java).apply {
                    action = ACTION_TRIGGER_ROUTINE
                    putExtra(EXTRA_ROUTINE_ID, routineId)
                }
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    routineId.toInt(),
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val canExact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pending
                    )
                } else {
                    alarmManager.setWindow(
                        android.app.AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        60_000L,
                        pending
                    )
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? JarvisApplication ?: return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val settings = app.container.settingsRepository.getSnapshot()
                if (isCurrentlyInQuietHours(settings.quietHoursStart, settings.quietHoursEnd)) {
                    return@launch
                }

                val allRoutines = app.container.routineRepository.getAllRoutinesSnapshot()
                    .filter { it.enabled }

                when (action) {
                    ACTION_TRIGGER_ROUTINE -> {
                        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
                        val target = allRoutines.firstOrNull { it.id == routineId } ?: return@launch
                        app.container.routineRepository.markRoutineExecuted(target.id)
                        app.container.agentOrchestrator.processUserUtterance(target.actionsPrompt)
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        if (!settings.proactiveSuggestionsEnabled) return@launch
                        allRoutines.filter { it.triggerType == RoutineTriggerType.CHARGING_CONNECTED }
                            .forEach { routine ->
                                app.container.routineRepository.markRoutineExecuted(routine.id)
                                app.container.agentOrchestrator.processUserUtterance(routine.actionsPrompt)
                            }
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        if (!settings.proactiveSuggestionsEnabled) return@launch
                        val state = intent.getIntExtra("state", 0)
                        if (state == 1) {
                            allRoutines.filter { it.triggerType == RoutineTriggerType.HEADSET_CONNECTED }
                                .forEach { routine ->
                                    app.container.routineRepository.markRoutineExecuted(routine.id)
                                    app.container.agentOrchestrator.processUserUtterance(routine.actionsPrompt)
                                }
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentlyInQuietHours(startStr: String, endStr: String): Boolean {
        return runCatching {
            val cal = Calendar.getInstance()
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val startParts = startStr.split(":")
            val endParts = endStr.split(":")
            val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
            val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()

            if (startMinutes <= endMinutes) {
                nowMinutes in startMinutes..endMinutes
            } else {
                nowMinutes >= startMinutes || nowMinutes <= endMinutes
            }
        }.getOrDefault(false)
    }
}
