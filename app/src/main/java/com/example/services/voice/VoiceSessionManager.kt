package com.example.services.voice

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Single owner of the microphone and speaker across the application.
 *
 * State machine:
 *   IDLE -> LISTENING -> THINKING -> SPEAKING -> (IDLE | LISTENING if follow-up)
 */
@Singleton
class VoiceSessionManager @Inject constructor(
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val audioRecorder: PcmAudioRecorder? = null,
    private val wakeWordEngine: WakeWordEngine,
    private val ttsManager: TtsManager? = null
) {
    companion object {
        const val SPEAKER_TAIL_DELAY_MS = 500L
        const val ECHO_SIMILARITY_THRESHOLD = 0.70

        private val TOOL_NAME_REGEX = Regex(
            "\\b(call_contact|send_sms|send_whatsapp_or_telegram|open_app_and_find|open_app|" +
                "read_screen|tap_element|type_text|scroll_screen|wait_for_element|set_alarm|" +
                "set_timer|adjust_volume|toggle_flashlight|play_media|navigate_maps|web_search|" +
                "open_url|notification_action|system_navigation|save_memory|forget_memory|" +
                "search_memory|search_history)\\b",
            RegexOption.IGNORE_CASE
        )
        private val CODE_FENCE_REGEX = Regex("```[\\s\\S]*?```")
        private val INLINE_JSON_REGEX = Regex("\\{[^{}]*\"[a-zA-Z0-9_]+\"\\s*:[^{}]*\\}")
        private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]+\\)")
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val MARKDOWN_SYMBOLS_REGEX = Regex("[*_`~#>]+")
        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Cn}\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        fun sanitizeTextForTts(raw: String): String {
            if (raw.isBlank()) return ""
            var cleaned = raw
            cleaned = CODE_FENCE_REGEX.replace(cleaned, " ")
            cleaned = INLINE_JSON_REGEX.replace(cleaned, " ")
            cleaned = MARKDOWN_LINK_REGEX.replace(cleaned, "$1")
            cleaned = URL_REGEX.replace(cleaned, " ")
            cleaned = TOOL_NAME_REGEX.replace(cleaned, { match ->
                match.value.replace('_', ' ')
            })
            cleaned = MARKDOWN_SYMBOLS_REGEX.replace(cleaned, " ")
            cleaned = EMOJI_REGEX.replace(cleaned, " ")
            cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
            return cleaned
        }

        fun isEchoOfAssistantReply(candidateTranscript: String, lastAssistantReply: String): Boolean {
            val normCandidate = normalizeForComparison(candidateTranscript)
            val normReply = normalizeForComparison(lastAssistantReply)
            if (normCandidate.isEmpty() || normReply.isEmpty()) return false

            if (normReply.contains(normCandidate) && normCandidate.length >= 6) {
                val coverage = normCandidate.length.toDouble() / normReply.length.toDouble()
                if (coverage >= ECHO_SIMILARITY_THRESHOLD) return true
            }

            val tokenSim = tokenSimilarity(normCandidate, normReply)
            if (tokenSim >= ECHO_SIMILARITY_THRESHOLD) return true

            val charSim = normalizedLevenshteinSimilarity(normCandidate, normReply)
            return charSim >= ECHO_SIMILARITY_THRESHOLD
        }

        internal fun normalizeForComparison(text: String): String {
            return text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        internal fun tokenSimilarity(a: String, b: String): Double {
            val tokensA = a.split(' ').filter { it.isNotBlank() }
            val tokensB = b.split(' ').filter { it.isNotBlank() }
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
            val setA = tokensA.toSet()
            val setB = tokensB.toSet()
            val intersection = setA.intersect(setB).size.toDouble()
            val union = setA.union(setB).size.toDouble()
            val jaccard = if (union > 0) intersection / union else 0.0
            val candidateContainment = intersection / setA.size.toDouble()
            return max(jaccard, if (tokensA.size >= 3) candidateContainment else jaccard)
        }

        internal fun normalizedLevenshteinSimilarity(a: String, b: String): Double {
            val maxLen = max(a.length, b.length)
            if (maxLen == 0) return 1.0
            val dist = levenshteinDistance(a, b)
            return 1.0 - (dist.toDouble() / maxLen.toDouble())
        }

        private fun levenshteinDistance(lhs: String, rhs: String): Int {
            val m = lhs.length
            val n = rhs.length
            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)
            for (i in 1..m) {
                curr[0] = i
                for (j in 1..n) {
                    val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                    curr[j] = minOf(
                        curr[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[n]
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tailTransitionJob: Job? = null

    private val _state = MutableStateFlow(VoiceSessionState.IDLE)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()
    val isUsingFallbackChip: StateFlow<Boolean> = ttsManager?.isUsingFallbackChip ?: MutableStateFlow(false)

    @Volatile
    private var lastSpokenAssistantText: String = ""

    @Volatile
    private var wakeWordCallback: (() -> Unit)? = null

    @Volatile
    private var wakeWordShouldStayArmed: Boolean = false

    fun configureWakeWordAutoRearm(enabled: Boolean, onDetected: (() -> Unit)? = null) {
        wakeWordShouldStayArmed = enabled
        if (onDetected != null) {
            wakeWordCallback = onDetected
        }
        syncWakeWordWithSessionState()
    }

    fun startListening(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tailTransitionJob?.cancel()
        ttsEngine.stop()
        audioRecorder?.stopRecording()
        wakeWordEngine.stopMonitoring()

        _state.value = VoiceSessionState.LISTENING

        speechRecognizer.startListening(
            language = language,
            onFinalTranscript = { rawTranscript ->
                val trimmed = rawTranscript.trim()
                if (trimmed.isEmpty()) {
                    transitionToIdle()
                    return@startListening
                }
                if (isEchoOfAssistantReply(trimmed, lastSpokenAssistantText)) {
                    transitionToIdle()
                    return@startListening
                }
                enterThinking()
                onFinalTranscript(trimmed)
            },
            onError = { errorMsg ->
                transitionToIdle()
                onError(errorMsg)
            }
        )
    }

    fun enterThinking() {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()
        _state.value = VoiceSessionState.THINKING
    }

    fun speakReply(
        rawText: String,
        language: AppLanguage,
        selectedVoiceName: String = "",
        speed: Float = 1.0f,
        followUpListening: Boolean = false,
        onFollowUpReady: () -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()

        val sanitized = sanitizeTextForTts(rawText)
        if (sanitized.isBlank()) {
            transitionToIdle()
            onFinished()
            return
        }

        lastSpokenAssistantText = sanitized
        _state.value = VoiceSessionState.SPEAKING

        if (ttsManager != null) {
            ttsManager.speakFullReply(
                text = sanitized,
                language = language,
                selectedVoiceName = selectedVoiceName,
                speed = speed,
                onAudioStarted = { _state.value = VoiceSessionState.SPEAKING },
                onComplete = {
                    tailTransitionJob = scope.launch {
                        delay(SPEAKER_TAIL_DELAY_MS)
                        if (_state.value == VoiceSessionState.SPEAKING) {
                            if (followUpListening) {
                                onFollowUpReady()
                            } else {
                                transitionToIdle()
                            }
                        }
                        onFinished()
                    }
                }
            )
        } else {
            ttsEngine.speak(
                text = sanitized,
                language = language,
                selectedVoiceName = selectedVoiceName,
                speed = speed,
                pitch = 1.0f,
                onComplete = {
                    tailTransitionJob = scope.launch {
                        delay(SPEAKER_TAIL_DELAY_MS)
                        if (_state.value == VoiceSessionState.SPEAKING) {
                            if (followUpListening) {
                                onFollowUpReady()
                            } else {
                                transitionToIdle()
                            }
                        }
                        onFinished()
                    }
                }
            )
        }
    }

    fun queueStreamingSentence(
        sentence: String,
        language: AppLanguage,
        selectedVoiceName: String = "",
        speed: Float = 1.0f,
        onAudioStarted: () -> Unit = {}
    ) {
        closeMicrophoneImmediately()
        val sanitized = sanitizeTextForTts(sentence)
        if (sanitized.isBlank()) return
        lastSpokenAssistantText = sanitized

        if (ttsManager != null) {
            ttsManager.queueStreamingSentence(
                sentence = sanitized,
                language = language,
                selectedVoiceName = selectedVoiceName,
                speed = speed,
                onAudioStarted = {
                    _state.value = VoiceSessionState.SPEAKING
                    onAudioStarted()
                }
            )
        } else {
            _state.value = VoiceSessionState.SPEAKING
            onAudioStarted()
            ttsEngine.speak(
                text = sanitized,
                language = language,
                selectedVoiceName = selectedVoiceName,
                speed = speed,
                pitch = 1.0f
            )
        }
    }

    fun bargeInAndListen(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tailTransitionJob?.cancel()
        ttsManager?.stop()
        ttsEngine.stop()
        startListening(language, onFinalTranscript, onError)
    }

    fun stopAll() {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()
        ttsManager?.stop()
        ttsEngine.stop()
        _state.value = VoiceSessionState.IDLE
        syncWakeWordWithSessionState()
    }

    fun transitionToIdle() {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()
        _state.value = VoiceSessionState.IDLE
        syncWakeWordWithSessionState()
    }

    private fun closeMicrophoneImmediately() {
        speechRecognizer.stopListening()
        speechRecognizer.cancel()
        audioRecorder?.stopRecording()
        wakeWordEngine.stopMonitoring()
    }

    private fun syncWakeWordWithSessionState() {
        if (_state.value == VoiceSessionState.IDLE &&
            wakeWordShouldStayArmed &&
            wakeWordEngine.isWakeWordConfigured &&
            !wakeWordEngine.isPaused.value
        ) {
            val cb = wakeWordCallback ?: return
            wakeWordEngine.startMonitoring(cb)
        } else {
            wakeWordEngine.stopMonitoring()
        }
    }
}
