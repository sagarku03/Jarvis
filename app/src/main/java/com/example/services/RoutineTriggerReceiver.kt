package com.example.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.domain.model.RoutineTriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Executes multi-action Routines when triggered by:
 * 1. Scheduled Daily Time Alarm (`ACTION_DAILY_ROUTINE_ALARM`) via `setExactAndAllowWhileIdle` — and reschedules for the next day.
 * 2. Headset / Bluetooth Connected (`Intent.ACTION_HEADSET_PLUG` or `BluetoothDevice.ACTION_ACL_CONNECTED`)
 * 3. Charging Started (`Intent.ACTION_POWER_CONNECTED`)
 *
 * Enforces the Setup Gate: never runs routines if the Gemini API key is missing or setup is incomplete.
 */
class RoutineTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? JarvisApplication ?: return
        val container = app.appContainer

        if (container.killSwitch.isKilled.value) return
        if (!container.apiKeyStore.exists()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = container.settingsRepository.settingsFlow.first()
                if (!settings.setupComplete || settings.killSwitchActive) return@launch

                val routines = container.routineRepository.routinesFlow.first()
                    .filter { it.enabled }

                when (action) {
                    ACTION_DAILY_ROUTINE_ALARM -> {
                        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
                        val matched = routines.firstOrNull { it.id == routineId }
                        if (matched != null) {
                            if (matched.triggerType == RoutineTriggerType.TIME_DAILY) {
                                scheduleDailyRoutineAlarm(
                                    context = context,
                                    routineId = matched.id,
                                    timeHhMm = matched.triggerValue
                                )
                            }
                            container.routineRepository.markRoutineRun(matched.id, System.currentTimeMillis())
                            for (cmd in matched.actionsJson) {
                                if (container.killSwitch.isKilled.value) break
                                container.agentOrchestrator.handleUserCommand(cmd)
                            }
                        }
                    }

                    Intent.ACTION_HEADSET_PLUG,
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val isPlugged = if (action == Intent.ACTION_HEADSET_PLUG) {
                            intent.getIntExtra("state", 0) == 1
                        } else {
                            true
                        }
                        if (isPlugged) {
                            routines.filter { it.triggerType == RoutineTriggerType.HEADSET_CONNECTED }
                                .forEach { routine ->
                                    container.routineRepository.markRoutineRun(routine.id, System.currentTimeMillis())
                                    for (cmd in routine.actionsJson) {
                                        if (container.killSwitch.isKilled.value) break
                                        container.agentOrchestrator.handleUserCommand(cmd)
                                    }
                                }
                        }
                    }

                    Intent.ACTION_POWER_CONNECTED -> {
                        routines.filter { it.triggerType == RoutineTriggerType.CHARGING_STARTED }
                            .forEach { routine ->
                                container.routineRepository.markRoutineRun(routine.id, System.currentTimeMillis())
                                for (cmd in routine.actionsJson) {
                                    if (container.killSwitch.isKilled.value) break
                                    container.agentOrchestrator.handleUserCommand(cmd)
                                }
                            }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY_ROUTINE_ALARM = "com.example.jarvis.ACTION_DAILY_ROUTINE_ALARM"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"

        @Volatile
        private var dynamicReceiver: RoutineTriggerReceiver? = null

        @Synchronized
        fun syncDynamicReceivers(context: Context, hasEnabledHardwareRoutines: Boolean) {
            val appCtx = context.applicationContext
            if (hasEnabledHardwareRoutines && dynamicReceiver == null) {
                val receiver = RoutineTriggerReceiver()
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_HEADSET_PLUG)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                }
                runCatching {
                    ContextCompat.registerReceiver(
                        appCtx,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    dynamicReceiver = receiver
                }
            } else if (!hasEnabledHardwareRoutines && dynamicReceiver != null) {
                runCatching {
                    appCtx.unregisterReceiver(dynamicReceiver)
                }
                dynamicReceiver = null
            }
        }

        fun scheduleDailyRoutineAlarm(
            context: Context,
            routineId: Long,
            timeHhMm: String
        ) {
            if (routineId <= 0L) return
            val parts = timeHhMm.trim().split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val alarmIntent = Intent(context, RoutineTriggerReceiver::class.java).apply {
                action = ACTION_DAILY_ROUTINE_ALARM
                putExtra(EXTRA_ROUTINE_ID, routineId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                routineId.toInt(),
                alarmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            runCatching {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            }
        }
    }
}
