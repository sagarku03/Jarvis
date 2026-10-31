package com.example.services

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import com.example.di.AndroidEntryPoint
import com.example.domain.model.AssistantOrbState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service for hands-free "Hey Jarvis" wake word detection.
 * - Requires RECORD_AUDIO permission and configured Porcupine key + hey_jarvis_android.ppn asset.
 * - Started ONLY from visible user actions (never directly from BOOT_COMPLETED on Android 14/15).
 * - Coordinates with VoiceSessionManager so the wake word engine only holds the microphone while
 *   VoiceSession is IDLE.
 */
@AndroidEntryPoint
class VoiceForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? JarvisApplication
        val container = app?.appContainer

        when (intent?.action) {
            ACTION_STOP_KILL_SWITCH -> {
                container?.killSwitch?.trigger("Notification STOP action")
                container?.voiceSessionManager?.stopAll()
                container?.wakeWordEngine?.stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                val engine = container?.wakeWordEngine
                if (engine != null) {
                    val nextPaused = !engine.isPaused.value
                    engine.setPaused(nextPaused)
                    serviceScope.launch {
                        container.settingsRepository.setWakeWordPaused(nextPaused)
                    }
                    updateNotification(isPaused = nextPaused)
                }
                return START_STICKY
            }
        }

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val wakeConfigured = container?.wakeWordEngine?.isWakeWordConfigured == true
        if (!hasMicPerm || !wakeConfigured) {
            stopSelf()
            return START_NOT_STICKY
        }

        val isPaused = container?.wakeWordEngine?.isPaused?.value ?: false
        val notification = buildNotification(isPaused = isPaused)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Android 14/15 ForegroundServiceStartNotAllowedException guard
            stopSelf()
            return START_NOT_STICKY
        }

        if (container != null && !container.killSwitch.isKilled.value) {
            val onWakeDetected: () -> Unit = {
                container.agentOrchestrator.setOrbState(AssistantOrbState.LISTENING)
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_TRIGGER_VOICE_TURN, true)
                }
                runCatching { startActivity(launchIntent) }
            }
            container.voiceSessionManager.configureWakeWordAutoRearm(
                enabled = !isPaused,
                onDetected = onWakeDetected
            )
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = applicationContext as? JarvisApplication
        val container = app?.appContainer
        serviceScope.launch {
            val settings = container?.settingsRepository?.settingsFlow?.first()
            if (settings != null &&
                settings.wakeWordEnabled &&
                !settings.killSwitchActive &&
                container.wakeWordEngine.isWakeWordConfigured &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                scheduleWatchdogRestart(applicationContext)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val app = applicationContext as? JarvisApplication
        app?.appContainer?.voiceSessionManager?.configureWakeWordAutoRearm(enabled = false)
        app?.appContainer?.wakeWordEngine?.stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(isPaused: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP_KILL_SWITCH
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (isPaused) {
            getString(R.string.fgs_notification_title_paused)
        } else {
            getString(R.string.fgs_notification_title_active)
        }
        val contentText = if (isPaused) {
            getString(R.string.fgs_notification_text_paused)
        } else {
            getString(R.string.fgs_notification_text_active)
        }
        val pauseActionLabel = if (isPaused) {
            getString(R.string.fgs_action_resume)
        } else {
            getString(R.string.fgs_action_pause)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, pauseActionLabel, pauseIntent)
            .addAction(0, getString(R.string.fgs_action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_KILL_SWITCH = "com.example.jarvis.ACTION_STOP_KILL_SWITCH"
        const val ACTION_TOGGLE_PAUSE = "com.example.jarvis.ACTION_TOGGLE_PAUSE"
        const val EXTRA_TRIGGER_VOICE_TURN = "extra_trigger_voice_turn"

        fun startService(context: Context) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            val app = context.applicationContext as? JarvisApplication
            if (app?.appContainer?.wakeWordEngine?.isWakeWordConfigured != true) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VoiceForegroundService::class.java)
                )
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, VoiceForegroundService::class.java))
        }

        fun scheduleWatchdogRestart(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val restartIntent = Intent(context, VoiceForegroundService::class.java)
            val pendingIntent = PendingIntent.getService(
                context,
                991,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 4_000L,
                pendingIntent
            )
        }
    }
}
