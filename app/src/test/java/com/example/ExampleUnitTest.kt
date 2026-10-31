package com.example

import com.example.data.remote.FakeBackend
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.MemoryFact
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ScreenNodeSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.ToolExecutionResult
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceProfile
import com.example.domain.model.VoiceSessionState
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGate
import com.example.domain.safety.SafetyGateImpl
import com.example.domain.tool.Tool
import com.example.domain.tool.ToolDescriptor
import com.example.domain.tool.ToolRegistry
import com.example.services.JarvisAccessibilityService
import com.example.services.JarvisNotificationListener
import com.example.services.voice.PcmAudioRecorder
import com.example.services.voice.SpeechRecognizerWrapper
import com.example.services.voice.TtsEngineWrapper
import com.example.services.voice.VoiceSessionManager
import com.example.services.voice.WakeWordEngine
import com.example.tools.ReadScreenTool
import com.example.tools.TapElementTool
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    // --- Fakes for VoiceSessionManager Tests ---

    private class FakeSpeechRecognizer : SpeechRecognizerWrapper {
        override val isListening = MutableStateFlow(false)
        override val partialTranscript = MutableStateFlow("")
        override val rmsDbLevel = MutableStateFlow(0f)
        override val lastErrorCode = MutableStateFlow<Int?>(null)
        override val lastErrorMessage = MutableStateFlow("None")
        override val lastSttLatencyMs = MutableStateFlow(85L)

        var lastOnFinal: ((String) -> Unit)? = null
        var lastOnError: ((String) -> Unit)? = null

        override fun startListening(
            language: AppLanguage,
            onFinalTranscript: (String) -> Unit,
            onError: (String) -> Unit
        ) {
            isListening.value = true
            lastOnFinal = onFinalTranscript
            lastOnError = onError
        }

        override fun stopListening() {
            isListening.value = false
        }

        override fun cancel() {
            isListening.value = false
            lastOnFinal = null
            lastOnError = null
        }

        override fun shutdown() {
            cancel()
        }
    }

    private class FakeTtsEngine : TtsEngineWrapper {
        override val isSpeaking = MutableStateFlow(false)
        override val firstAudioLatencyMs = MutableStateFlow(120L)
        override val isUsingOfflineFallback = MutableStateFlow(false)

        var lastSpokenText: String? = null
        var pendingOnComplete: (() -> Unit)? = null

        override fun speak(
            text: String,
            language: AppLanguage,
            voiceProfile: VoiceProfile,
            speed: Float,
            pitch: Float,
            onComplete: () -> Unit
        ) {
            lastSpokenText = text
            isSpeaking.value = true
            pendingOnComplete = onComplete
        }

        fun finishSpeakingNow() {
            isSpeaking.value = false
            val cb = pendingOnComplete
            pendingOnComplete = null
            cb?.invoke()
        }

        override fun stop() {
            isSpeaking.value = false
            pendingOnComplete = null
        }

        override fun shutdown() {
            stop()
        }
    }

    private class FakePcmAudioRecorder : PcmAudioRecorder {
        override val isRecording = MutableStateFlow(false)
        override val rmsEnergyLevel = MutableStateFlow(0f)

        override fun startRecording(
            onPcmFrame: (ShortArray) -> Unit,
            onSilenceEndpointDetected: () -> Unit
        ): Boolean {
            isRecording.value = true
            return true
        }

        override fun stopRecording() {
            isRecording.value = false
        }
    }

    private class FakeWakeWordEngine(
        override val isWakeWordConfigured: Boolean = true
    ) : WakeWordEngine {
        override val isMonitoring = MutableStateFlow(false)
        override val isPaused = MutableStateFlow(false)
        override val sensitivity = MutableStateFlow(0.65f)

        override fun startMonitoring(onWakeWordDetected: () -> Unit) {
            if (isWakeWordConfigured && !isPaused.value) {
                isMonitoring.value = true
            }
        }

        override fun stopMonitoring() {
            isMonitoring.value = false
        }

        override fun setPaused(paused: Boolean) {
            isPaused.value = paused
            if (paused) isMonitoring.value = false
        }

        override fun setSensitivity(value: Float) {
            sensitivity.value = value
        }

        override fun release() {
            stopMonitoring()
        }
    }

    @Test
    fun voiceSession_enforcesHalfDuplexAndNeverOpensMicWhileSpeaking() = runBlocking {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val recorder = FakePcmAudioRecorder()
        val wake = FakeWakeWordEngine(isWakeWordConfigured = true)
        val manager = VoiceSessionManager(stt, tts, recorder, wake)

        manager.configureWakeWordAutoRearm(enabled = true) { }
        assertEquals(VoiceSessionState.IDLE, manager.state.value)
        assertTrue("Wake word should monitor while IDLE", wake.isMonitoring.value)

        // Start listening: wake word must stop before SpeechRecognizer starts
        var capturedTranscript = ""
        manager.startListening(
            language = AppLanguage.EN_IN,
            onFinalTranscript = { capturedTranscript = it },
            onError = { }
        )
        assertEquals(VoiceSessionState.LISTENING, manager.state.value)
        assertTrue(stt.isListening.value)
        assertFalse("PcmAudioRecorder must NEVER run during push-to-talk", recorder.isRecording.value)
        assertFalse("Wake word must NEVER run while LISTENING", wake.isMonitoring.value)

        // Deliver final transcript -> state transitions to THINKING and closes mic
        stt.lastOnFinal?.invoke("Open YouTube")
        assertEquals("Open YouTube", capturedTranscript)
        assertEquals(VoiceSessionState.THINKING, manager.state.value)
        assertFalse("Mic must be closed in THINKING", stt.isListening.value)

        // Speak reply -> state is SPEAKING, mic and wake word remain closed
        manager.speakReply(
            rawText = "Opening **YouTube** now.",
            language = AppLanguage.EN_IN,
            voiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
            speed = 1.0f
        )
        assertEquals(VoiceSessionState.SPEAKING, manager.state.value)
        assertFalse("Mic must NEVER be open during SPEAKING", stt.isListening.value)
        assertFalse("Wake word must NEVER be open during SPEAKING", wake.isMonitoring.value)
        assertEquals("Opening YouTube now.", tts.lastSpokenText)

        // Complete TTS and wait for 500ms speaker tail delay
        tts.finishSpeakingNow()
        delay(VoiceSessionManager.SPEAKER_TAIL_DELAY_MS + 100L)
        assertEquals(VoiceSessionState.IDLE, manager.state.value)
        assertTrue("Wake word should re-arm after speaker tail delay", wake.isMonitoring.value)
    }

    @Test
    fun voiceSession_bargeInStopsTtsAndStartsListeningImmediately() {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val recorder = FakePcmAudioRecorder()
        val wake = FakeWakeWordEngine()
        val manager = VoiceSessionManager(stt, tts, recorder, wake)

        manager.speakReply(
            rawText = "Here is a long explanation about your schedule today.",
            language = AppLanguage.EN_IN,
            voiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
            speed = 1.0f
        )
        assertEquals(VoiceSessionState.SPEAKING, manager.state.value)
        assertTrue(tts.isSpeaking.value)

        // User taps orb to barge in
        manager.bargeInAndListen(
            language = AppLanguage.EN_IN,
            onFinalTranscript = {},
            onError = {}
        )
        assertFalse("TTS must stop immediately on barge-in", tts.isSpeaking.value)
        assertEquals(VoiceSessionState.LISTENING, manager.state.value)
        assertTrue("SpeechRecognizer must open on barge-in", stt.isListening.value)
    }

    @Test
    fun voiceSession_selfEchoFilter_ignoresRecognitionMatchingAssistantReply() {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val recorder = FakePcmAudioRecorder()
        val wake = FakeWakeWordEngine()
        val manager = VoiceSessionManager(stt, tts, recorder, wake)

        // Assistant speaks a phrase
        manager.speakReply(
            rawText = "Hi, I'm Jarvis. How can I help?",
            language = AppLanguage.EN_IN,
            voiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
            speed = 1.0f
        )

        // Verify >= 70% similarity detection
        assertTrue(
            VoiceSessionManager.isEchoOfAssistantReply(
                candidateTranscript = "Hi I am Jarvis how can I help",
                lastAssistantReply = "Hi, I'm Jarvis. How can I help?"
            )
        )
        assertFalse(
            VoiceSessionManager.isEchoOfAssistantReply(
                candidateTranscript = "Set an alarm for 7 AM tomorrow",
                lastAssistantReply = "Hi, I'm Jarvis. How can I help?"
            )
        )

        // If mic picks up echo of last reply, onFinalTranscript must NOT fire
        var triggeredAgent = false
        manager.startListening(
            language = AppLanguage.EN_IN,
            onFinalTranscript = { triggeredAgent = true },
            onError = {}
        )
        stt.lastOnFinal?.invoke("Hi I'm Jarvis how can I help")
        assertFalse("Self-echo must be ignored and never sent to agent", triggeredAgent)
        assertEquals(VoiceSessionState.IDLE, manager.state.value)
    }

    @Test
    fun ttsSanitizer_stripsMarkdownUrlsJsonEmojisAndToolNames() {
        val raw = """
            **Done!** Calling `call_contact` for [Rahul](https://example.com/user) 🚀
            ```json
            {"phone": "+919876543210"}
            ```
            Visit https://google.com for details.
        """.trimIndent()

        val cleaned = VoiceSessionManager.sanitizeTextForTts(raw)
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
        assertFalse(cleaned.contains("call_contact"))
        assertFalse(cleaned.contains("{"))
        assertFalse(cleaned.contains("🚀"))
        assertTrue(cleaned.contains("Done!"))
        assertTrue(cleaned.contains("call contact"))
        assertTrue(cleaned.contains("Rahul"))
    }

    @Test
    fun safetyGate_blocksSensitiveApps_andEnforcesRiskMatrix() = runBlocking {
        val gate = SafetyGateImpl(contactsResolver = null)

        val lowTool = object : Tool {
            override val name = "toggle_flashlight"
            override val description = "Flashlight"
            override val jsonSchema = "{}"
            override val riskLevel = RiskLevel.LOW
            override suspend fun execute(args: Map<String, String>) =
                ToolExecutionResult(name, true, "OK")
        }

        val highTool = object : Tool {
            override val name = "call_contact"
            override val description = "Call"
            override val jsonSchema = "{}"
            override val riskLevel = RiskLevel.HIGH
            override suspend fun execute(args: Map<String, String>) =
                ToolExecutionResult(name, true, "OK")
        }

        // 1. Standard mode allows LOW risk immediately
        val lowDecision = gate.evaluate(
            toolCall = ToolCallSpec("1", "toggle_flashlight", mapOf("enabled" to "true")),
            tool = lowTool,
            confirmationLevel = ConfirmationLevel.STANDARD,
            screenContext = null
        )
        assertTrue(lowDecision.allowedImmediately)
        assertFalse(lowDecision.requiresConfirmation)

        // 2. Standard mode requires confirmation for HIGH risk
        val highDecision = gate.evaluate(
            toolCall = ToolCallSpec("2", "call_contact", mapOf("contact_name" to "Mom")),
            tool = highTool,
            confirmationLevel = ConfirmationLevel.STANDARD,
            screenContext = null
        )
        assertFalse(highDecision.allowedImmediately)
        assertTrue(highDecision.requiresConfirmation)

        // 3. Banking / UPI screen blocks automation tools
        val uiTapTool = TapElementTool()
        val gpayScreen = ScreenContextSnapshot(
            packageName = "com.google.android.apps.nbu.paisa.user",
            summaryText = "Google Pay UPI Pin"
        )
        val blockedDecision = gate.evaluate(
            toolCall = ToolCallSpec("3", "tap_element", mapOf("target_text" to "Pay")),
            tool = uiTapTool,
            confirmationLevel = ConfirmationLevel.STANDARD,
            screenContext = gpayScreen
        )
        assertFalse(blockedDecision.allowedImmediately)
        assertFalse(blockedDecision.requiresConfirmation)
    }

    @Test
    fun agentOrchestrator_neverRetriesNonIdempotentTools() = runBlocking {
        assertTrue(AgentOrchestrator.isNonIdempotentTool("call_contact"))
        assertTrue(AgentOrchestrator.isNonIdempotentTool("send_sms"))
        assertTrue(AgentOrchestrator.isNonIdempotentTool("send_whatsapp_or_telegram"))
        assertTrue(AgentOrchestrator.isNonIdempotentTool("tap_element"))
        assertTrue(AgentOrchestrator.isNonIdempotentTool("type_text"))
        assertFalse(AgentOrchestrator.isNonIdempotentTool("toggle_flashlight"))

        var callExecutionCount = 0
        val failingCallTool = object : Tool {
            override val name = "call_contact"
            override val description = "Call contact"
            override val jsonSchema = "{}"
            override val riskLevel = RiskLevel.LOW // LOW here to test retry loop directly
            override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
                callExecutionCount++
                return ToolExecutionResult(name, false, "Dialer failed")
            }
        }

        val fakeBackendRepo = object : AgentBackendRepository {
            var step = 0
            override suspend fun runAgentStep(
                messages: List<ChatMessage>,
                tools: List<ToolDescriptor>,
                memories: List<MemoryFact>,
                screenContext: ScreenContextSnapshot?,
                language: AppLanguage
            ): AgentStepDecision {
                step++
                return if (step == 1) {
                    AgentStepDecision(
                        assistantText = "Calling now",
                        toolCalls = listOf(ToolCallSpec("c1", "call_contact", mapOf("contact_name" to "Mom")))
                    )
                } else {
                    AgentStepDecision(assistantText = "Call failed.", toolCalls = emptyList())
                }
            }
        }

        val orchestrator = createTestOrchestrator(
            backendRepository = fakeBackendRepo,
            tools = setOf(failingCallTool)
        )

        orchestrator.handleUserCommand("Call Mom")
        assertEquals("Non-idempotent call_contact must execute only once", 1, callExecutionCount)
    }

    @Test
    fun notificationOtpRedactor_redactsOtpDigitsCleanly() {
        val (redactedText, wasRedacted) = JarvisNotificationListener.redactOtpContent(
            rawTitle = "HDFC Bank Alert",
            rawText = "Your OTP for transaction is 849201. Do not share."
        )
        assertTrue(wasRedacted)
        assertFalse(redactedText.contains("849201"))
        assertTrue(redactedText.contains("[REDACTED-CODE]"))
    }

    @Test
    fun accessibilityTools_returnCleanEnglishFallbackWhenServiceDisconnected() = runBlocking {
        val readScreenResult = ReadScreenTool().execute(emptyMap())
        assertFalse(readScreenResult.success)
        assertTrue(readScreenResult.outputEn.contains(JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN))
    }

    private fun createTestOrchestrator(
        backendRepository: AgentBackendRepository,
        tools: Set<Tool>
    ): AgentOrchestrator {
        val chatRepo = object : ChatRepository {
            private val list = mutableListOf<ChatMessage>()
            override val messagesFlow: Flow<List<ChatMessage>> = MutableStateFlow(emptyList())
            override suspend fun getRecentContextMessages(limit: Int): List<ChatMessage> = list.takeLast(limit)
            override suspend fun appendMessage(message: ChatMessage) { list.add(message) }
            override suspend fun clearAllMessages() { list.clear() }
        }
        val memoryRepo = object : MemoryRepository {
            override val memoriesFlow: Flow<List<MemoryFact>> = MutableStateFlow(emptyList())
            override fun searchMemories(query: String): Flow<List<MemoryFact>> = MutableStateFlow(emptyList())
            override suspend fun getAllMemoriesSnapshot(): List<MemoryFact> = emptyList()
            override suspend fun upsertMemory(category: String, key: String, value: String) {}
            override suspend fun deleteMemory(id: Long) {}
            override suspend fun clearAllMemories() {}
        }
        val logRepo = object : ActivityLogRepository {
            override val logsFlow: Flow<List<ActivityLogEntry>> = MutableStateFlow(emptyList())
            override suspend fun recordLog(entry: ActivityLogEntry) {}
            override suspend fun clearAllLogs() {}
        }
        val routineRepo = object : RoutineRepository {
            override val routinesFlow: Flow<List<RoutineItem>> = MutableStateFlow(emptyList())
            override suspend fun upsertRoutine(routine: RoutineItem): Long = 1L
            override suspend fun toggleRoutine(id: Long, enabled: Boolean) {}
            override suspend fun deleteRoutine(id: Long) {}
            override suspend fun markRoutineRun(id: Long, timestampMs: Long) {}
            override suspend fun findMatchingVoiceRoutine(utterance: String): RoutineItem? = null
        }
        val settingsRepo = object : SettingsRepository {
            override val settingsFlow: Flow<UserSettings> = MutableStateFlow(UserSettings())
            override suspend fun setLanguage(language: AppLanguage) {}
            override suspend fun setVoiceProfile(voiceProfile: VoiceProfile) {}
            override suspend fun setSpeechSpeed(speed: Float) {}
            override suspend fun setSpeechPitch(pitch: Float) {}
            override suspend fun setWakeWordSensitivity(sensitivity: Float) {}
            override suspend fun setWakeWordEnabled(enabled: Boolean) {}
            override suspend fun setWakeWordPaused(paused: Boolean) {}
            override suspend fun setBootAutoStartEnabled(enabled: Boolean) {}
            override suspend fun setFloatingOrbEnabled(enabled: Boolean) {}
            override suspend fun setConfirmationLevel(level: ConfirmationLevel) {}
            override suspend fun setKillSwitchActive(active: Boolean) {}
        }

        return AgentOrchestrator(
            agentBackendRepository = backendRepository,
            chatRepository = chatRepo,
            memoryRepository = memoryRepo,
            activityLogRepository = logRepo,
            routineRepository = routineRepo,
            settingsRepository = settingsRepo,
            toolRegistry = ToolRegistry(tools),
            safetyGate = SafetyGateImpl(null),
            killSwitch = KillSwitch()
        )
    }
}
