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
import com.example.di.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stage 3: VoiceForegroundService with Porcupine ("Hey Jarvis") Wake-Word Engine,
 * Android 14/15 FOREGROUND_SERVICE_TYPE_MICROPHONE compliance, Pause/Resume toggle
 * (never records when paused), and a Service Watchdog that keeps wake-word monitoring healthy.
 */
@AndroidEntryPoint
class VoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.services.ACTION_START_VOICE"
        const val ACTION_PAUSE = "com.example.services.ACTION_PAUSE_VOICE"
        const val ACTION_RESUME = "com.example.services.ACTION_RESUME_VOICE"
        const val ACTION_STOP = "com.example.services.ACTION_STOP_VOICE"
        const val ACTION_KILL_SWITCH = "com.example.services.ACTION_KILL_SWITCH"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isMicActiveIndicator = MutableStateFlow(false)
        val isMicActiveIndicator: StateFlow<Boolean> = _isMicActiveIndicator.asStateFlow()
    }

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as? JarvisApplication)?.container

        when (intent?.action) {
            ACTION_KILL_SWITCH -> {
                container?.killSwitch?.triggerKillSwitch("Triggered from VoiceForegroundService Notification")
                container?.wakeWordDetector?.stopMonitoring()
                _isRunning.value = false
                _isMicActiveIndicator.value = false
                watchdogJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                container?.wakeWordDetector?.pauseMonitoring()
                _isMicActiveIndicator.value = false
                updateNotification(isPaused = true)
                return START_STICKY
            }
            ACTION_RESUME -> {
                container?.wakeWordDetector?.resumeMonitoring {
                    container.agentOrchestrator.setListeningState(true)
                }
                _isMicActiveIndicator.value = true
                updateNotification(isPaused = false)
                return START_STICKY
            }
            ACTION_STOP -> {
                container?.wakeWordDetector?.stopMonitoring()
                _isRunning.value = false
                _isMicActiveIndicator.value = false
                watchdogJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Android 14/15 check: ensure RECORD_AUDIO is granted before starting microphone FGS
                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                createNotificationChannel()
                val notification = buildNotification(isPaused = !hasMic)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMic) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    _isRunning.value = true
                    if (hasMic) {
                        container?.wakeWordDetector?.startMonitoring {
                            container.agentOrchestrator.setListeningState(true)
                        }
                        _isMicActiveIndicator.value = !(container?.wakeWordDetector?.isPaused?.value ?: false)
                        startWatchdog()
                    }
                }.onFailure {
                    _isRunning.value = false
                    _isMicActiveIndicator.value = false
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            while (isActive && _isRunning.value) {
                delay(15_000L)
                val container = (application as? JarvisApplication)?.container ?: continue
                val detector = container.wakeWordDetector
                if (!detector.isPaused.value && !detector.isMonitoring.value) {
                    detector.startMonitoring {
                        container.agentOrchestrator.setListeningState(true)
                    }
                    _isMicActiveIndicator.value = true
                }
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        _isMicActiveIndicator.value = false
        watchdogJob?.cancel()
        super.onDestroy()
    }

    private fun updateNotification(isPaused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active 'Hey Jarvis' wake-word monitor & microphone indicator"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val togglePending = PendingIntent.getService(
            this,
            2,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val killIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_KILL_SWITCH
        }
        val killPending = PendingIntent.getService(
            this,
            1,
            killIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (isPaused) {
            "PAUSED (Mic Off) • Tap Resume to enable 'Hey Jarvis'"
        } else {
            getString(R.string.voice_service_notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.voice_service_notification_title))
            .setContentText(subtitle)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Resume Mic" else "Pause Mic",
                togglePending
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.btn_kill_switch),
                killPending
            )
            .build()
    }
}
