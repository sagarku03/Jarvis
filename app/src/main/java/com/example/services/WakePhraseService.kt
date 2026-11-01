package com.example.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.model.AssistantOrbState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service for hands-free "Hey Jarvis" wake phrase detection.
 * Never auto-starts on boot. Stops listening while speaking or thinking.
 */
class WakePhraseService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isScreenInteractive = true
    private var autoStopJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    checkScreenAndToggleListening()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    checkScreenAndToggleListening()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? JarvisApplication
        val container = app?.appContainer
        if (container == null || !container.apiKeyStore.exists()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_WAKE_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
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

        // Monitor settings: screen-on only, 30m auto-stop, and orb state
        serviceScope.launch {
            val settings = container.settingsRepository.settingsFlow.first()
            if (settings.wakePhraseStopAfter30m) {
                autoStopJob?.cancel()
                autoStopJob = launch {
                    delay(30 * 60 * 1000L)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            // Never listen while Jarvis is speaking or thinking
            container.agentOrchestrator.orbState.collectLatest { orbState ->
                if (orbState == AssistantOrbState.SPEAKING || orbState == AssistantOrbState.THINKING) {
                    stopRecognition()
                } else {
                    checkScreenAndToggleListening()
                }
            }
        }

        initSpeechRecognizer()
        startRecognition()

        return START_STICKY
    }

    private fun checkScreenAndToggleListening() {
        val app = application as? JarvisApplication ?: return
        serviceScope.launch {
            val settings = app.appContainer.settingsRepository.settingsFlow.first()
            if (settings.wakePhraseScreenOnOnly && !isScreenInteractive) {
                stopRecognition()
            } else {
                startRecognition()
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        runCatching {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        // Recover gracefully after pause
                        serviceScope.launch {
                            delay(1000L)
                            startRecognition()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val detected = matches.any { matchesWakePhrase(it) }
                            if (detected) {
                                onWakePhraseDetected()
                                return
                            }
                        }
                        serviceScope.launch {
                            delay(300L)
                            startRecognition()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!partials.isNullOrEmpty()) {
                            val detected = partials.any { matchesWakePhrase(it) }
                            if (detected) {
                                stopRecognition()
                                onWakePhraseDetected()
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startRecognition() {
        if (isListening) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching {
            recognizer.startListening(intent)
            isListening = true
        }
    }

    private fun stopRecognition() {
        if (!isListening) return
        isListening = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }
    }

    private fun onWakePhraseDetected() {
        AssistantController.startVoiceCall(this)
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        stopRecognition()
        runCatching {
            speechRecognizer?.destroy()
            speechRecognizer = null
            unregisterReceiver(screenReceiver)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openAppPending = AssistantController.startVoiceCallPendingIntent(this)

        val stopIntent = Intent(this, WakePhraseService::class.java).apply {
            action = ACTION_STOP_WAKE_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            802,
            stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.wake_phrase_title))
            .setContentText(getString(R.string.wake_phrase_service_active))
            .setOngoing(true)
            .setContentIntent(openAppPending)
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
                getString(R.string.wake_phrase_title),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_wake_phrase_channel"
        const val NOTIFICATION_ID = 4202
        const val ACTION_STOP_WAKE_SERVICE = "com.example.jarvis.ACTION_STOP_WAKE_SERVICE"

        private val WAKE_PHRASES = listOf(
            "hey jarvis",
            "ok jarvis",
            "okay jarvis",
            "hi jarvis",
            "jarvis",
            "arre jarvis",
            "sun jarvis",
            "jarvis suno"
        )

        fun matchesWakePhrase(spokenText: String): Boolean {
            val clean = spokenText.trim().lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]"), "")
            if (clean.isBlank()) return false

            for (phrase in WAKE_PHRASES) {
                if (clean == phrase || clean.startsWith("$phrase ") || clean.endsWith(" $phrase") || clean.contains(" $phrase ")) {
                    return true
                }
                // Fuzzy tolerance: distance <= 2 for "jarvis" or phrase
                if (levenshteinDistance(clean, phrase) <= 2) {
                    return true
                }
            }
            return false
        }

        private fun levenshteinDistance(s: String, t: String): Int {
            val m = s.length
            val n = t.length
            val d = Array(m + 1) { IntArray(n + 1) }
            for (i in 0..m) d[i][0] = i
            for (j in 0..n) d[0][j] = j
            for (j in 1..n) {
                for (i in 1..m) {
                    val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                }
            }
            return d[m][n]
        }

        fun startService(context: Context) {
            val app = context.applicationContext as? JarvisApplication
            if (app != null && !app.appContainer.apiKeyStore.exists()) return
            val intent = Intent(context, WakePhraseService::class.java)
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
            val intent = Intent(context, WakePhraseService::class.java)
            context.stopService(intent)
        }
    }
}
