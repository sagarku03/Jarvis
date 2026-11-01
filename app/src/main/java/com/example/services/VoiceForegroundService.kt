package com.example.services

import android.Manifest
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground Voice Service with Notification trigger and KillSwitch integration.
 * Enforces the Setup Gate: refuses to run if the Gemini API key is missing or setup is incomplete.
 */
class VoiceForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val container by lazy {
        (application as JarvisApplication).appContainer
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        container.killSwitch.registerCancelCallback {
            container.voiceSessionManager.stopAll()
            container.agentOrchestrator.cancelActiveTurn()
            updateNotification(paused = true, statusText = getString(R.string.fgs_notification_text_killed))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!container.apiKeyStore.exists()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_KILL_SWITCH -> {
                container.killSwitch.trigger("Notification STOP action")
                container.voiceSessionManager.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                val nextPaused = !container.wakeWordEngine.isPaused.value
                container.wakeWordEngine.setPaused(nextPaused)
                updateNotification(
                    paused = nextPaused,
                    statusText = if (nextPaused) {
                        getString(R.string.fgs_notification_text_paused)
                    } else {
                        getString(R.string.fgs_notification_text_active)
                    }
                )
                return START_STICKY
            }
        }

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(
            paused = false,
            statusText = getString(R.string.fgs_notification_text_active)
        )

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
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val settings = container.settingsRepository.settingsFlow.first()
            if (!settings.setupComplete) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            container.voiceSessionManager.startListening(
                language = settings.language,
                onFinalTranscript = { transcript ->
                    if (transcript.isNotBlank()) {
                        serviceScope.launch {
                            val reply = container.agentOrchestrator.handleUserCommand(transcript, isVoice = true)
                            if (!container.killSwitch.isKilled.value) {
                                container.voiceSessionManager.speakReply(
                                    rawText = reply,
                                    language = settings.language,
                                    selectedVoiceName = settings.selectedVoiceName,
                                    speed = settings.speechSpeed
                                )
                            }
                        }
                    }
                },
                onError = {
                    container.voiceSessionManager.transitionToIdle()
                }
            )
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        container.voiceSessionManager.stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(paused: Boolean, statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(paused, statusText))
    }

    private fun buildNotification(paused: Boolean, statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_STOP_KILL_SWITCH
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val talkPending = AssistantController.startVoiceCallPendingIntent(this, 101)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(statusText)
            .setOngoing(!paused)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                getString(R.string.voice_call_talk),
                talkPending
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.fgs_action_stop),
                stopPending
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_TOGGLE_PAUSE = "com.example.jarvis.ACTION_TOGGLE_PAUSE"
        const val ACTION_STOP_KILL_SWITCH = "com.example.jarvis.ACTION_STOP_KILL_SWITCH"

        fun startService(context: Context) {
            val app = context.applicationContext as? JarvisApplication
            if (app != null && !app.appContainer.apiKeyStore.exists()) return
            val intent = Intent(context, VoiceForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
