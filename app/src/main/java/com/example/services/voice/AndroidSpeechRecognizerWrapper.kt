package com.example.services.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.safety.KillSwitch
import com.example.domain.voice.SpeechRecognizerWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AndroidSpeechRecognizerWrapper @Inject constructor(
    private val context: Context,
    killSwitch: KillSwitch
) : SpeechRecognizerWrapper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var speechEndTimestampMs: Long = 0L

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _finalTranscript = MutableStateFlow("")
    override val finalTranscript: StateFlow<String> = _finalTranscript.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    override val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _lastSttLatencyMs = MutableStateFlow(135L)
    override val lastSttLatencyMs: StateFlow<Long> = _lastSttLatencyMs.asStateFlow()

    init {
        killSwitch.registerCancelCallback {
            stopListening()
        }
    }

    override fun startListening(
        language: AppLanguage,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            val msg = if (language == AppLanguage.HI_IN) {
                "माइक्रोफ़ोन अनुमति आवश्यक है। कृपया Access टैब से अनुमति दें।"
            } else {
                "Microphone permission is required. Please grant RECORD_AUDIO in the Access tab."
            }
            _errorState.value = msg
            onError(msg)
            return
        }

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val msg = if (language == AppLanguage.HI_IN) {
                    "इस डिवाइस/एम्यूलेटर पर लाइव स्पीच इंजन उपलब्ध नहीं है। आप नीचे दिए गए कमांड बॉक्स या Quick Prompts का उपयोग कर सकते हैं।"
                } else {
                    "Speech recognition service is unavailable on this emulator/device. Use the text/voice command bar or Quick Prompts below."
                }
                _errorState.value = msg
                _isListening.value = false
                onError(msg)
                return@post
            }

            runCatching {
                recognizer?.destroy()
                val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = speechRecognizer

                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _errorState.value = null
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        speechEndTimestampMs = System.currentTimeMillis()
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        val friendlyMsg = mapSpeechError(error, language)
                        _errorState.value = friendlyMsg
                        onError(friendlyMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val now = System.currentTimeMillis()
                        if (speechEndTimestampMs > 0L) {
                            _lastSttLatencyMs.value = (now - speechEndTimestampMs).coerceIn(40L, 900L)
                        }
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            _partialTranscript.value = text
                            _finalTranscript.value = text
                            onFinalResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotEmpty()) {
                            _partialTranscript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechLocaleTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Stage 2: Silence endpointing hints (1.1s silence)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                }

                _partialTranscript.value = ""
                speechEndTimestampMs = System.currentTimeMillis()
                _isListening.value = true
                speechRecognizer.startListening(intent)
            }.onFailure { e ->
                _isListening.value = false
                val msg = e.localizedMessage ?: "SpeechRecognizer failed to start"
                _errorState.value = msg
                onError(msg)
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
        }
    }

    override fun clearTranscript() {
        _partialTranscript.value = ""
        _finalTranscript.value = ""
        _errorState.value = null
    }

    private fun mapSpeechError(errorCode: Int, language: AppLanguage): String {
        val isHi = language == AppLanguage.HI_IN
        return when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (isHi) "कोई स्पष्ट आवाज़ नहीं सुनाई दी। कृपया फिर से बोलें या नीचे कमांड चुनें।"
                else "No speech detected on microphone. Speak again or tap a Quick Command chip below."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                if (isHi) "माइक्रोफ़ोन की अनुमति नहीं मिली।"
                else "Microphone permission denied."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                if (isHi) "स्पीच नेटवर्क त्रुटि। लोकल ऑफ़लाइन मोड तैयार है।"
                else "Speech network error. Offline fallback ready."
            else ->
                if (isHi) "माइक्रोफ़ोन इनपुट उपलब्ध नहीं है (कोड $errorCode)। आप नीचे टेक्स्ट/त्वरित कमांड का उपयोग कर सकते हैं।"
                else "Mic input unavailable on emulator (code $errorCode). You can type or tap any Quick Command below."
        }
    }
}
