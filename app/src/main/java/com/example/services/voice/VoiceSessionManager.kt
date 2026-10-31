package com.example.services.voice

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.VoiceProfile
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
 *
 * Invariants:
 * 1. Half-duplex: Microphone is NEVER open while state == THINKING or SPEAKING.
 * 2. Before TTS starts: stopListening() + cancel() recognizer and release any AudioRecord / wake-word mic.
 * 3. After TTS ends: wait [SPEAKER_TAIL_DELAY_MS] (500ms) before reopening the microphone.
 * 4. Barge-in: tapping the orb while SPEAKING immediately stops TTS and transitions to LISTENING.
 * 5. Self-echo filter: ignores any recognition result >= 70% similar to the last assistant reply.
 */
@Singleton
class VoiceSessionManager @Inject constructor(
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val audioRecorder: PcmAudioRecorder? = null,
    private val wakeWordEngine: WakeWordEngine
) {
    companion object {
        const val SPEAKER_TAIL_DELAY_MS = 500L
        const val ECHO_SIMILARITY_THRESHOLD = 0.70

        private val TOOL_NAME_REGEX = Regex(
            "\\b(call_contact|send_sms|send_whatsapp_or_telegram|open_app_and_find|open_app|" +
                "read_screen|tap_element|type_text|scroll_screen|wait_for_element|set_alarm|" +
                "set_timer|adjust_volume|toggle_flashlight|play_media|navigate_maps|web_search|" +
                "open_url|notification_action|system_navigation)\\b",
            RegexOption.IGNORE_CASE
        )
        private val CODE_FENCE_REGEX = Regex("```[\\s\\S]*?```")
        private val INLINE_JSON_REGEX = Regex("\\{[^{}]*\"[a-zA-Z0-9_]+\"\\s*:[^{}]*\\}")
        private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]+\\)")
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val MARKDOWN_SYMBOLS_REGEX = Regex("[*_`~#>]+")
        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Cn}\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        /**
         * Strips markdown, URLs, JSON blocks, emojis, and raw tool names before speaking.
         */
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

        /**
         * Returns true if [candidateTranscript] is >= 70% similar to [lastAssistantReply],
         * preventing the microphone from feeding speaker echo back into the agent loop.
         */
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
            // Also check containment of candidate tokens inside assistant reply tokens
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

    /**
     * Starts a half-duplex listening turn. Stops any active TTS or wake-word mic first.
     */
    fun startListening(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tailTransitionJob?.cancel()
        // Release speaker & wake word mic before opening SpeechRecognizer
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
                    // Self-echo detected: ignore silently and return to IDLE
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

    /**
     * Transitions into THINKING and guarantees the microphone is closed.
     */
    fun enterThinking() {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()
        _state.value = VoiceSessionState.THINKING
    }

    /**
     * Speaks [rawText] after closing the microphone. Waits [SPEAKER_TAIL_DELAY_MS] after
     * playback completes before either opening follow-up listening or returning to IDLE.
     */
    fun speakReply(
        rawText: String,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float,
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

        ttsEngine.speak(
            text = sanitized,
            language = language,
            voiceProfile = voiceProfile,
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

    /**
     * Barge-in handler: if currently SPEAKING, stops TTS immediately and starts listening.
     */
    fun bargeInAndListen(
        language: AppLanguage,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tailTransitionJob?.cancel()
        ttsEngine.stop()
        startListening(language, onFinalTranscript, onError)
    }

    /**
     * Immediately stops all mic capture and speaker output and resets state to IDLE.
     */
    fun stopAll() {
        tailTransitionJob?.cancel()
        closeMicrophoneImmediately()
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
