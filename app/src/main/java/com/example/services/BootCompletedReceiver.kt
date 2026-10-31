package com.example.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.model.RoutineTriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles BOOT_COMPLETED:
 * 1. Android 14/15 prohibits starting a `microphone` ForegroundService directly from BOOT_COMPLETED.
 *    Instead, when boot auto-start and wake word are enabled, posts a notification that launches
 *    MainActivity so the user can resume voice monitoring with a single tap.
 * 2. Reschedules all enabled TIME_DAILY routine alarms.
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
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = container.settingsRepository.settingsFlow.first()
                if (settings.bootAutoStartEnabled &&
                    settings.wakeWordEnabled &&
                    !settings.killSwitchActive &&
                    container.wakeWordEngine.isWakeWordConfigured
                ) {
                    postBootResumeNotification(context)
                }

                // Reschedule all daily time routines on reboot
                val routines = container.routineRepository.routinesFlow.first()
                routines.filter { it.enabled && it.triggerType == RoutineTriggerType.TIME_DAILY }
                    .forEach { routine ->
                        RoutineTriggerReceiver.scheduleDailyRoutineAlarm(
                            context = context,
                            routineId = routine.id,
                            timeHhMm = routine.triggerValue
                        )
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postBootResumeNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                context.getString(R.string.boot_resume_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_RESUME_WAKE_WORD_FROM_BOOT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            BOOT_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.boot_resume_title))
            .setContentText(context.getString(R.string.boot_resume_text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching { manager.notify(BOOT_NOTIFICATION_ID, notification) }
    }

    companion object {
        const val BOOT_CHANNEL_ID = "jarvis_boot_resume_channel"
        const val BOOT_NOTIFICATION_ID = 1002
        const val EXTRA_RESUME_WAKE_WORD_FROM_BOOT = "extra_resume_wake_word_from_boot"
    }
}
