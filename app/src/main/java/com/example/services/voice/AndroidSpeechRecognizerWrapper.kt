package com.example.services.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.R
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-instance Android SpeechRecognizer wrapper.
 * - Reuses a single SpeechRecognizer on the main thread and destroys it only in shutdown().
 * - Emits live normalized RMS level from onRmsChanged(rmsdB) for orb animations.
 * - Explicitly handles every SpeechRecognizer.ERROR_* code with strings.xml resources.
 * - Retries ERROR_RECOGNIZER_BUSY once after 300ms after cancelling.
 * - Measures real end-of-speech to final-result latency.
 */
@Singleton
class AndroidSpeechRecognizerWrapper @Inject constructor(
    private val context: Context
) : SpeechRecognizerWrapper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _rmsDbLevel = MutableStateFlow(0f)
    override val rmsDbLevel: StateFlow<Float> = _rmsDbLevel.asStateFlow()

    private val _lastErrorCode = MutableStateFlow<Int?>(null)
    override val lastErrorCode: StateFlow<Int?> = _lastErrorCode.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow("None")
    override val lastErrorMessage: StateFlow<String> = _lastErrorMessage.asStateFlow()

    private val _lastSttLatencyMs = MutableStateFlow(0L)
    override val lastSttLatencyMs: StateFlow<Long> = _lastSttLatencyMs.asStateFlow()

    private var speechEndTimestampMs: Long = 0L
    private var listenStartTimestampMs: Long = 0L
    private var retriedBusyInCurrentTurn: Boolean = false

    private var activeLanguage: AppLanguage = AppLanguage.EN_IN
    private var activeOnFinal: ((String) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    private fun getOrCreateRecognizer(): SpeechRecognizer {
        val existing = recognizer
        if (existing != null) return existing
        val created = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        created.setRecognitionListener(recognitionListener)
        recognizer = created
        return created
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _lastErrorCode.value = null
            _lastErrorMessage.value = "Listening"
        }

        override fun onBeginningOfSpeech() {
            _isListening.value = true
            speechEndTimestampMs = 0L
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Map typical SpeechRecognizer RMS range (-2 dB .. +10 dB) into 0f..1f
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsDbLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            speechEndTimestampMs = SystemClock.elapsedRealtime()
            _isListening.value = false
            _rmsDbLevel.value = 0f
        }

        override fun onError(error: Int) {
            _isListening.value = false
            _rmsDbLevel.value = 0f
            _lastErrorCode.value = error

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retriedBusyInCurrentTurn) {
                retriedBusyInCurrentTurn = true
                _lastErrorMessage.value = context.getString(R.string.stt_error_busy)
                runCatching { recognizer?.cancel() }
                mainHandler.postDelayed({
                    startListeningInternal()
                }, 300L)
                return
            }

            // Ignore benign ERROR_CLIENT when we explicitly cancelled listening
            if (error == SpeechRecognizer.ERROR_CLIENT && activeOnFinal == null) {
                return
            }

            val message = mapErrorCodeToMessage(error)
            _lastErrorMessage.value = "$message (code $error)"
            val errorCallback = activeOnError
            clearCallbacks()
            errorCallback?.invoke(message)
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _rmsDbLevel.value = 0f
            val now = SystemClock.elapsedRealtime()
            _lastSttLatencyMs.value = if (speechEndTimestampMs > 0L) {
                (now - speechEndTimestampMs).coerceAtLeast(1L)
            } else if (listenStartTimestampMs > 0L) {
                (now - listenStartTimestampMs).coerceAtLeast(1L)
            } else {
                0L
            }

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            _partialTranscript.value = best
            val finalCallback = activeOnFinal
            val errorCallback = activeOnError
            clearCallbacks()

            if (best.isNotEmpty()) {
                _lastErrorCode.value = null
                _lastErrorMessage.value = "OK"
                finalCallback?.invoke(best)
            } else {
                val msg = context.getString(R.string.stt_error_no_match)
                _lastErrorMessage.value = msg
                errorCallback?.invoke(msg)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) {
                _partialTranscript.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun startListening(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _isListening.value = false
                _rmsDbLevel.value = 0f
                val msg = context.getString(R.string.stt_error_unavailable)
                _lastErrorMessage.value = msg
                onError(msg)
                return@post
            }

            activeLanguage = language
            activeOnFinal = onFinalTranscript
            activeOnError = onError
            retriedBusyInCurrentTurn = false
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        try {
            val sr = getOrCreateRecognizer()
            runCatching { sr.cancel() }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage.speechLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage.speechLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            _partialTranscript.value = ""
            _rmsDbLevel.value = 0f
            speechEndTimestampMs = 0L
            listenStartTimestampMs = SystemClock.elapsedRealtime()
            _isListening.value = true
            sr.startListening(intent)
        } catch (e: Exception) {
            _isListening.value = false
            _rmsDbLevel.value = 0f
            val msg = context.getString(R.string.stt_error_client)
            _lastErrorMessage.value = msg
            val errorCallback = activeOnError
            clearCallbacks()
            errorCallback?.invoke(msg)
        }
    }

    override fun stopListening() {
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
            _isListening.value = false
            _rmsDbLevel.value = 0f
        }
    }

    override fun cancel() {
        mainHandler.post {
            clearCallbacks()
            runCatching { recognizer?.cancel() }
            _isListening.value = false
            _rmsDbLevel.value = 0f
            _partialTranscript.value = ""
        }
    }

    override fun shutdown() {
        mainHandler.post {
            clearCallbacks()
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
            _isListening.value = false
            _rmsDbLevel.value = 0f
        }
    }

    private fun clearCallbacks() {
        activeOnFinal = null
        activeOnError = null
    }

    private fun mapErrorCodeToMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.stt_error_no_match)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.stt_error_speech_timeout)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.stt_error_busy)
        SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.stt_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.stt_error_network_timeout)
        SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.stt_error_server)
        SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.stt_error_audio)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.stt_error_permissions)
        SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.stt_error_client)
        else -> context.getString(R.string.stt_error_no_match)
    }
}
