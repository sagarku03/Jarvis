package com.example.services.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * On-device Android `TextToSpeech` engine wrapper:
 * 1. Selects the best installed `en-IN` / English voice (`Voice.QUALITY_VERY_HIGH` / `QUALITY_HIGH`, offline preferred).
 * 2. Exposes `isUsingOfflineFallback` (`true` for Device voice, `false` for Cloud voice) so the UI shows a clear voice chip.
 * 3. Requests exclusive assistant audio focus (`USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`, `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`).
 */
@Singleton
class AndroidTtsEngineWrapper @Inject constructor(
    private val context: Context
) : TtsEngineWrapper, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _firstAudioLatencyMs = MutableStateFlow(0L)
    override val firstAudioLatencyMs: StateFlow<Long> = _firstAudioLatencyMs.asStateFlow()

    private val _isUsingOfflineFallback = MutableStateFlow(true)
    override val isUsingOfflineFallback: StateFlow<Boolean> = _isUsingOfflineFallback.asStateFlow()

    private var pendingCompletionCallback: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {
            isInitialized = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            selectBestOfflineVoice(AppLanguage.EN_IN)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    val cb = pendingCompletionCallback
                    pendingCompletionCallback = null
                    cb?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    val cb = pendingCompletionCallback
                    pendingCompletionCallback = null
                    cb?.invoke()
                }
            })
        }
    }

    override fun speak(
        text: String,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float,
        pitch: Float,
        onComplete: () -> Unit
    ) {
        val sanitized = VoiceSessionManager.sanitizeTextForTts(text)
        if (sanitized.isBlank()) {
            onComplete()
            return
        }

        stop()
        pendingCompletionCallback = onComplete
        val startTime = SystemClock.elapsedRealtime()
        requestAudioFocus()

        val sentences = splitIntoSentenceChunks(sanitized)
        _isUsingOfflineFallback.value = true
        _firstAudioLatencyMs.value = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
        speakWithAndroidTtsFallback(
            sentences = sentences,
            language = language,
            voiceProfile = voiceProfile,
            speed = speed
        )
    }

    private fun selectBestOfflineVoice(language: AppLanguage) {
        val engine = tts ?: return
        val preferredTags = listOf(language.speechLocale, "en-IN", "en-US", "en-GB")
        val availableVoices = runCatching { engine.voices }.getOrNull().orEmpty()

        val bestVoice: Voice? = preferredTags.firstNotNullOfOrNull { tag ->
            val targetLocale = Locale.forLanguageTag(tag)
            availableVoices
                .filter { v ->
                    v.locale.language.equals(targetLocale.language, ignoreCase = true) &&
                        (targetLocale.country.isEmpty() || v.locale.country.equals(targetLocale.country, ignoreCase = true)) &&
                        !v.isNetworkConnectionRequired &&
                        v.quality >= Voice.QUALITY_HIGH
                }
                .maxWithOrNull(compareBy<Voice> { it.quality >= Voice.QUALITY_VERY_HIGH }.thenBy { it.quality })
        } ?: availableVoices
            .filter { v -> v.locale.language.equals("en", ignoreCase = true) && !v.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }

        if (bestVoice != null) {
            runCatching { engine.voice = bestVoice }
        } else {
            runCatching { engine.language = Locale.forLanguageTag(language.speechLocale) }
        }
    }

    private fun speakWithAndroidTtsFallback(
        sentences: List<String>,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float
    ) {
        val engine = tts
        if (!isInitialized || engine == null) {
            _isSpeaking.value = false
            abandonAudioFocus()
            val cb = pendingCompletionCallback
            pendingCompletionCallback = null
            cb?.invoke()
            return
        }

        selectBestOfflineVoice(language)
        val clampedRate = (speed * voiceProfile.defaultRate).coerceIn(0.8f, 1.2f)
        engine.setPitch(1.0f)
        engine.setSpeechRate(clampedRate)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        _isSpeaking.value = true
        sentences.forEachIndexed { index, sentence ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = if (index == sentences.lastIndex) {
                "jarvis_final_${UUID.randomUUID()}"
            } else {
                "jarvis_chunk_${UUID.randomUUID()}"
            }
            engine.speak(sentence, queueMode, params, utteranceId)
        }
    }

    private fun splitIntoSentenceChunks(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            stop()
                        }
                    }
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        pendingCompletionCallback = null
        tts?.stop()
        _isSpeaking.value = false
        abandonAudioFocus()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
