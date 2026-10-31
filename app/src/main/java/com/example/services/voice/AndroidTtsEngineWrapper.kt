package com.example.services.voice

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.BuildConfig
import com.example.data.remote.ApiService
import com.example.data.remote.TtsRequestDto
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Natural Voice TTS engine with:
 * 1. Sentence-level streaming via backend `/v1/tts` when `BuildConfig.AI_VOICE` is true and backend URL is configured.
 * 2. Disk cache for short frequent phrases ("Okay", "Done", etc.) to achieve instant playback.
 * 3. Exclusive assistant audio focus (`USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`, `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`).
 * 4. Automatic fallback to the highest-quality local `en-IN`/`en-US` Android TTS voice (`QUALITY_HIGH`, offline)
 *    with `isUsingOfflineFallback` exposed to the UI so an "Offline voice" chip is shown.
 */
@Singleton
class AndroidTtsEngineWrapper @Inject constructor(
    private val context: Context,
    private val apiService: ApiService
) : TtsEngineWrapper, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var activeMediaPlayer: MediaPlayer? = null
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _firstAudioLatencyMs = MutableStateFlow(0L)
    override val firstAudioLatencyMs: StateFlow<Long> = _firstAudioLatencyMs.asStateFlow()

    private val _isUsingOfflineFallback = MutableStateFlow(false)
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
        streamingJob = scope.launch {
            var firstChunkStarted = false
            val canTryNeuralBackend = BuildConfig.AI_VOICE &&
                BuildConfig.JARVIS_BACKEND_BASE_URL.startsWith("http")

            val streamedSuccess = if (canTryNeuralBackend) {
                streamChunksViaBackend(
                    sentences = sentences,
                    language = language,
                    voiceProfile = voiceProfile,
                    speed = speed,
                    onFirstChunkReady = {
                        if (!firstChunkStarted) {
                            firstChunkStarted = true
                            _firstAudioLatencyMs.value =
                                (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
                            _isSpeaking.value = true
                            _isUsingOfflineFallback.value = false
                        }
                    }
                )
            } else {
                false
            }

            if (streamedSuccess) {
                _isSpeaking.value = false
                abandonAudioFocus()
                val cb = pendingCompletionCallback
                pendingCompletionCallback = null
                cb?.invoke()
            } else if (isActive) {
                // Fallback to highest-quality local Android TTS voice
                _isUsingOfflineFallback.value = true
                _firstAudioLatencyMs.value =
                    (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
                speakWithAndroidTtsFallback(
                    sentences = sentences,
                    language = language,
                    voiceProfile = voiceProfile,
                    speed = speed
                )
            }
        }
    }

    private suspend fun streamChunksViaBackend(
        sentences: List<String>,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float,
        onFirstChunkReady: () -> Unit
    ): Boolean {
        for (sentence in sentences) {
            if (! kotlinx.coroutines.currentCoroutineContext().isActive) return false
            val cachedFile = getShortPhraseCacheFile(sentence, voiceProfile.id, speed)
            val audioBytes: ByteArray? = if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 64L) {
                runCatching { cachedFile.readBytes() }.getOrNull()
            } else {
                val fetched = try {
                    val responseBody = apiService.synthesizeSpeech(
                        TtsRequestDto(
                            text = sentence,
                            languageCode = language.speechLocale,
                            voiceId = voiceProfile.id,
                            speed = speed.coerceIn(0.8f, 1.2f),
                            pitch = 1.0f
                        )
                    )
                    val bytes = responseBody.bytes()
                    if (bytes.size > 64 && cachedFile != null) {
                        runCatching { cachedFile.writeBytes(bytes) }
                    }
                    bytes
                } catch (_: Exception) {
                    null
                }
                fetched
            }

            if (audioBytes == null || audioBytes.size <= 64) {
                return false
            }

            onFirstChunkReady()
            val played = playAudioBytesBlocking(audioBytes)
            if (!played) return false
        }
        return true
    }

    private fun getShortPhraseCacheFile(sentence: String, voiceId: String, speed: Float): File? {
        // Cache short confirmation phrases (<= 48 chars) on disk
        if (sentence.length > 48) return null
        val key = "${voiceId}_${speed}_${sentence.lowercase().trim()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val dir = File(context.cacheDir, "tts_phrase_cache").apply { mkdirs() }
        return File(dir, "phrase_$digest.mp3")
    }

    private suspend fun playAudioBytesBlocking(bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val tempFile = File.createTempFile("jarvis_tts_", ".mp3", context.cacheDir)
                FileOutputStream(tempFile).use { it.write(bytes) }

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener { mp ->
                        mp.release()
                        tempFile.delete()
                        if (cont.isActive) cont.resume(true)
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        tempFile.delete()
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    prepare()
                    start()
                }
                activeMediaPlayer = player
                cont.invokeOnCancellation {
                    runCatching {
                        player.stop()
                        player.release()
                        tempFile.delete()
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
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
                .maxByOrNull { it.quality }
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
        streamingJob?.cancel()
        streamingJob = null
        pendingCompletionCallback = null
        runCatching {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
        }
        activeMediaPlayer = null
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

/**
 * On-device wake word engine ("Hey Jarvis") powered by Picovoice Porcupine.
 * Strictly requires BOTH:
 * 1. Non-blank `BuildConfig.PORCUPINE_ACCESS_KEY`
 * 2. Bundled `hey_jarvis_android.ppn` in `assets/` (or Porcupine built-in `JARVIS` keyword if `.ppn` is present).
 * Never fakes wake-word detection when unconfigured.
 */
@Singleton
class PorcupineWakeWordEngineImpl @Inject constructor(
    private val context: Context,
    private val pcmAudioRecorder: PcmAudioRecorder
) : WakeWordEngine {

    companion object {
        const val CUSTOM_KEYWORD_ASSET = "hey_jarvis_android.ppn"
    }

    private val _isMonitoring = MutableStateFlow(false)
    override val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.65f)
    override val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private var wakeCallback: (() -> Unit)? = null
    private var porcupine: Porcupine? = null
    private var frameAccumulator = ShortArray(0)

    override val isWakeWordConfigured: Boolean
        get() {
            val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY.trim()
            if (accessKey.isEmpty() || accessKey.startsWith("YOUR_")) return false
            val hasCustomPpn = runCatching {
                context.assets.list("")?.contains(CUSTOM_KEYWORD_ASSET) == true
            }.getOrDefault(false)
            return hasCustomPpn
        }

    override fun startMonitoring(onWakeWordDetected: () -> Unit) {
        wakeCallback = onWakeWordDetected
        if (_isPaused.value || !isWakeWordConfigured) {
            _isMonitoring.value = false
            return
        }

        val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY.trim()
        try {
            if (porcupine == null) {
                porcupine = Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(CUSTOM_KEYWORD_ASSET)
                    .setSensitivity(_sensitivity.value.coerceIn(0.1f, 1.0f))
                    .build(context.applicationContext)
            }
        } catch (_: Throwable) {
            porcupine = null
            _isMonitoring.value = false
            return
        }

        val engine = porcupine ?: return
        val requiredFrameLength = engine.frameLength
        frameAccumulator = ShortArray(0)

        val started = pcmAudioRecorder.startRecording(
            onPcmFrame = { pcmChunk ->
                if (_isPaused.value || !_isMonitoring.value) return@startRecording
                processPorcupineFrames(engine, requiredFrameLength, pcmChunk)
            }
        )
        _isMonitoring.value = started
    }

    private fun processPorcupineFrames(
        engine: Porcupine,
        frameLength: Int,
        incoming: ShortArray
    ) {
        val combined = ShortArray(frameAccumulator.size + incoming.size)
        System.arraycopy(frameAccumulator, 0, combined, 0, frameAccumulator.size)
        System.arraycopy(incoming, 0, combined, frameAccumulator.size, incoming.size)

        var offset = 0
        while (offset + frameLength <= combined.size) {
            val slice = combined.copyOfRange(offset, offset + frameLength)
            offset += frameLength
            try {
                val keywordIndex = engine.process(slice)
                if (keywordIndex >= 0) {
                    // Release mic BEFORE starting STT
                    stopMonitoring()
                    wakeCallback?.invoke()
                    return
                }
            } catch (_: Throwable) {
                return
            }
        }

        frameAccumulator = if (offset < combined.size) {
            combined.copyOfRange(offset, combined.size)
        } else {
            ShortArray(0)
        }
    }

    override fun stopMonitoring() {
        pcmAudioRecorder.stopRecording()
        frameAccumulator = ShortArray(0)
        _isMonitoring.value = false
    }

    override fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        if (paused) {
            pcmAudioRecorder.stopRecording()
            _isMonitoring.value = false
        } else if (wakeCallback != null && isWakeWordConfigured) {
            startMonitoring(wakeCallback!!)
        }
    }

    override fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(0.1f, 1.0f)
        _sensitivity.value = clamped
        if (porcupine != null) {
            runCatching { porcupine?.delete() }
            porcupine = null
            if (_isMonitoring.value && !_isPaused.value && wakeCallback != null) {
                startMonitoring(wakeCallback!!)
            }
        }
    }

    override fun release() {
        stopMonitoring()
        runCatching { porcupine?.delete() }
        porcupine = null
        wakeCallback = null
    }
}
