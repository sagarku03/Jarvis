package com.example

import android.content.Context
import android.content.ContextWrapper
import com.example.data.local.FieldCipher
import com.example.data.remote.AgentMessageDto
import com.example.data.remote.AgentStepRequestDto
import com.example.data.remote.AgentStepResponseDto
import com.example.data.remote.ApiService
import com.example.data.remote.TtsRequestDto
import com.example.data.repository.AgentBackendRepositoryImpl
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.contacts.ContactsResolver
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.MemoryFact
import com.example.domain.model.MessageRole
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.ScreenContextSnapshot
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
import com.example.services.voice.FreeWakeWordEngineImpl
import com.example.services.voice.PcmAudioRecorder
import com.example.services.voice.SpeechRecognizerWrapper
import com.example.services.voice.TtsEngineWrapper
import com.example.services.voice.VoiceSessionManager
import com.example.services.voice.WakeWordEngine
import com.example.tools.CallContactTool
import com.example.tools.ReadScreenTool
import com.example.tools.TapElementTool
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

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
        override val isUsingOfflineFallback = MutableStateFlow(true)

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

    // 1. 5-turn voice session without self-triggering
    @Test
    fun voiceSession_fiveTurnConversation_neverSelfTriggers() = runBlocking {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val wake = FreeWakeWordEngineImpl()
        val manager = VoiceSessionManager(stt, tts, null, wake)

        assertFalse("FreeWakeWordEngineImpl must report not configured", wake.isWakeWordConfigured)

        val userTurns = listOf(
            "Turn on the flashlight",
            "Set an alarm for 7 AM",
            "Set a timer for 5 minutes",
            "What is my schedule today",
            "Open Settings"
        )
        val assistantReplies = listOf(
            "Flashlight turned on.",
            "Setting your alarm for 7 AM.",
            "Starting a 5 minute timer.",
            "You have two meetings scheduled today.",
            "Opening Settings now."
        )

        var deliveredTranscriptCount = 0

        for (i in 0 until 5) {
            manager.startListening(
                language = AppLanguage.EN_IN,
                onFinalTranscript = { deliveredTranscriptCount++ },
                onError = {}
            )
            assertEquals(VoiceSessionState.LISTENING, manager.state.value)
            assertTrue(stt.isListening.value)
            assertFalse(tts.isSpeaking.value)

            // User speaks turn i
            stt.lastOnFinal?.invoke(userTurns[i])
            assertEquals(VoiceSessionState.THINKING, manager.state.value)
            assertFalse("Mic must close during THINKING", stt.isListening.value)

            // Assistant speaks reply i
            manager.speakReply(
                rawText = assistantReplies[i],
                language = AppLanguage.EN_IN,
                voiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
                speed = 1.0f
            )
            assertEquals(VoiceSessionState.SPEAKING, manager.state.value)
            assertFalse("Mic must NEVER open while SPEAKING", stt.isListening.value)

            tts.finishSpeakingNow()
            delay(VoiceSessionManager.SPEAKER_TAIL_DELAY_MS + 40L)
            assertEquals(VoiceSessionState.IDLE, manager.state.value)

            // Simulate acoustic echo of the assistant's own reply if mic were opened right after
            manager.startListening(
                language = AppLanguage.EN_IN,
                onFinalTranscript = { deliveredTranscriptCount++ },
                onError = {}
            )
            stt.lastOnFinal?.invoke(assistantReplies[i])
            assertEquals(
                "Self-echo on turn ${i + 1} must be ignored and never increment transcript count",
                i + 1,
                deliveredTranscriptCount
            )
            assertEquals(VoiceSessionState.IDLE, manager.state.value)
        }

        assertEquals(5, deliveredTranscriptCount)
    }

    // 2. Airplane mode / offline test: clean message, no crash
    @Test
    fun offlineMode_returnsCleanMessageAndNeverCrashes() = runBlocking {
        val offlineContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }
        val failingNetworkApi = object : ApiService {
            override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
                throw java.net.UnknownHostException("Unable to resolve host: Airplane mode active")
            }

            override suspend fun synthesizeSpeech(request: TtsRequestDto): ResponseBody {
                throw java.net.UnknownHostException("Offline")
            }
        }

        val repo = AgentBackendRepositoryImpl(offlineContext, failingNetworkApi)

        // Non-local query while offline returns a clean message and never crashes
        val generalDecision = repo.runAgentStep(
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "Explain quantum computing")),
            tools = emptyList(),
            memories = emptyList(),
            screenContext = null,
            language = AppLanguage.EN_IN
        )
        assertNotNull(generalDecision.assistantText)
        assertTrue(generalDecision.toolCalls.isEmpty())

        // Offline local utility command still routes to local tool cleanly
        val alarmDecision = repo.runAgentStep(
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "Set an alarm for 6:30")),
            tools = emptyList(),
            memories = emptyList(),
            screenContext = null,
            language = AppLanguage.EN_IN
        )
        assertEquals(1, alarmDecision.toolCalls.size)
        assertEquals("set_alarm", alarmDecision.toolCalls.first().name)
    }

    // 3. Worker 429 test -> app shows "Daily free limit reached" + wire schema contract test
    @Test
    fun workerHttp429_returnsDailyFreeLimitReached_andMatchesWireContract() = runBlocking {
        val context = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }
        val rateLimitedApi = object : ApiService {
            override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
                val errorJson = """{"error":"DAILY_FREE_LIMIT_REACHED","message":"Daily free limit reached (200 requests/day)."}"""
                val errorResponse = Response.error<AgentStepResponseDto>(
                    429,
                    errorJson.toResponseBody("application/json".toMediaType())
                )
                throw HttpException(errorResponse)
            }

            override suspend fun synthesizeSpeech(request: TtsRequestDto): ResponseBody {
                throw IllegalStateException("Not used")
            }
        }

        val repo = AgentBackendRepositoryImpl(context, rateLimitedApi)
        val decision = repo.runAgentStep(
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "What is the weather?")),
            tools = emptyList(),
            memories = emptyList(),
            screenContext = null,
            language = AppLanguage.EN_IN
        )

        assertNotNull(decision.assistantText)
        assertTrue(
            "Expected 'Daily free limit reached' message but got: ${decision.assistantText}",
            decision.assistantText!!.contains("Daily free limit reached", ignoreCase = true)
        )
        assertTrue(decision.toolCalls.isEmpty())

        // Verify shared JSON wire schema between Android Moshi DTOs and Cloudflare Worker contract
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val reqAdapter = moshi.adapter(AgentStepRequestDto::class.java)
        val resAdapter = moshi.adapter(AgentStepResponseDto::class.java)

        val sampleReq = AgentStepRequestDto(
            messages = listOf(
                AgentMessageDto(role = "USER", content = "Turn on flashlight"),
                AgentMessageDto(role = "TOOL", content = "Flashlight ON", toolName = "toggle_flashlight")
            ),
            tools = emptyList(),
            screenContext = null,
            languageCode = "en-IN"
        )
        val serializedReq = reqAdapter.toJson(sampleReq)
        assertTrue(serializedReq.contains("\"role\":\"TOOL\""))
        assertTrue(serializedReq.contains("\"toolName\":\"toggle_flashlight\""))

        val workerResponseJson = """
            {
              "assistantText": "Flashlight is now on.",
              "toolCalls": [
                {
                  "id": "call_1",
                  "name": "toggle_flashlight",
                  "args": { "enabled": "true" }
                }
              ]
            }
        """.trimIndent()
        val parsedRes = resAdapter.fromJson(workerResponseJson)
        assertNotNull(parsedRes)
        assertEquals("Flashlight is now on.", parsedRes?.assistantText)
        assertEquals(1, parsedRes?.toolCalls?.size)
        assertEquals("toggle_flashlight", parsedRes?.toolCalls?.first()?.name)
        assertEquals("true", parsedRes?.toolCalls?.first()?.args?.get("enabled"))
    }

    // 4. "Call Mom" with 2 "Mom" contacts -> asks which one, requires confirmation before dialing
    @Test
    fun callMom_withTwoMatchingContacts_requiresDisambiguationAndConfirmation() = runBlocking {
        val memoryWithTwoMoms = object : MemoryRepository {
            private val facts = listOf(
                MemoryFact(id = 1L, category = "CONTACT", key = "Mom Mobile", value = "+919876543210"),
                MemoryFact(id = 2L, category = "CONTACT", key = "Mom Home", value = "+919876500001")
            )
            override val memoriesFlow: Flow<List<MemoryFact>> = MutableStateFlow(facts)
            override fun searchMemories(query: String): Flow<List<MemoryFact>> = MutableStateFlow(facts)
            override suspend fun getAllMemoriesSnapshot(): List<MemoryFact> = facts
            override suspend fun upsertMemory(category: String, key: String, value: String) {}
            override suspend fun deleteMemory(id: Long) {}
            override suspend fun clearAllMemories() {}
        }

        val dummyContext = ContextWrapper(null)
        val resolver = ContactsResolver(dummyContext, memoryWithTwoMoms)
        val candidates = resolver.resolveCandidates("Mom")
        assertEquals("Should resolve both 'Mom' contacts", 2, candidates.size)

        // Direct execution without disambiguated phone_number must refuse to guess and ask which one
        val callTool = CallContactTool(dummyContext, resolver)
        val ambiguousResult = callTool.execute(mapOf("contact_name" to "Mom"))
        assertFalse("CallContactTool must not dial when multiple contacts match without disambiguation", ambiguousResult.success)
        assertTrue(ambiguousResult.outputEn.contains("Multiple contacts match 'Mom'"))
        assertTrue(ambiguousResult.outputEn.contains("Mom Mobile"))
        assertTrue(ambiguousResult.outputEn.contains("Mom Home"))

        // SafetyGate evaluation must require HIGH-risk confirmation and surface both candidates
        val gate = SafetyGateImpl(resolver)
        val callSpec = ToolCallSpec(id = "call_mom_1", name = "call_contact", args = mapOf("contact_name" to "Mom"))
        val eval = gate.evaluate(callSpec, callTool, ConfirmationLevel.STANDARD, null)
        assertFalse(eval.allowedImmediately)
        assertTrue(eval.requiresConfirmation)

        val approvalDeferred = async {
            gate.requestUserApproval(callSpec, callTool, eval.reasonEn)
        }
        delay(50)
        val pending = gate.pendingConfirmation.value
        assertNotNull("SafetyGate must expose pending confirmation with both Mom candidates", pending)
        assertEquals(2, pending?.candidateContacts?.size)
        assertTrue(pending?.summaryEn?.contains("Multiple contacts match") == true)

        // User selects the second Mom contact and approves
        gate.resolveUserApproval(approved = true, selectedPhoneOverride = "+919876500001")
        val outcome = approvalDeferred.await()
        assertTrue(outcome.approved)
        assertEquals("+919876500001", outcome.selectedPhoneOverride)
    }

    // 5. KillSwitch stops listening, speaking, and any running tool within 1s
    @Test
    fun killSwitch_stopsListeningSpeakingAndRunningToolUnderOneSecond() = runBlocking {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val wake = FakeWakeWordEngine()
        val voiceManager = VoiceSessionManager(stt, tts, null, wake)
        val killSwitch = KillSwitch()

        var voiceKilled = false
        killSwitch.registerCancelCallback {
            voiceKilled = true
            voiceManager.stopAll()
        }

        // Start speaking
        voiceManager.speakReply(
            rawText = "Executing a multi-step routine now.",
            language = AppLanguage.EN_IN,
            voiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
            speed = 1.0f
        )
        assertTrue(tts.isSpeaking.value)

        // Start a slow running tool inside AgentOrchestrator
        val slowTool = object : Tool {
            override val name = "wait_for_element"
            override val description = "Slow tool"
            override val jsonSchema = "{}"
            override val riskLevel = RiskLevel.LOW
            override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
                delay(5_000L)
                return ToolExecutionResult(name, true, "Finished")
            }
        }

        val backendRepo = object : AgentBackendRepository {
            override suspend fun runAgentStep(
                messages: List<ChatMessage>,
                tools: List<ToolDescriptor>,
                memories: List<MemoryFact>,
                screenContext: ScreenContextSnapshot?,
                language: AppLanguage
            ): AgentStepDecision {
                return AgentStepDecision(
                    assistantText = null,
                    toolCalls = listOf(ToolCallSpec("t1", "wait_for_element", emptyMap()))
                )
            }
        }

        val orchestrator = createTestOrchestrator(
            backendRepository = backendRepo,
            tools = setOf(slowTool),
            killSwitch = killSwitch
        )
        killSwitch.registerCancelCallback {
            orchestrator.cancelActiveTurn()
        }

        val startMs = System.currentTimeMillis()
        val turnJob = async {
            runCatching { orchestrator.handleUserCommand("Wait for page") }
        }
        delay(60L)

        // Trigger emergency KillSwitch
        killSwitch.trigger("Unit test emergency stop")
        turnJob.await()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("KillSwitch must stop everything in under 1000ms (took ${elapsedMs}ms)", elapsedMs < 1_000L)
        assertTrue(voiceKilled)
        assertFalse("Mic must be stopped", stt.isListening.value)
        assertFalse("TTS must be stopped", tts.isSpeaking.value)
        assertEquals(VoiceSessionState.IDLE, voiceManager.state.value)
    }

    // 6. FieldCipher AES-GCM round-trip and fail-closed verification
    @Test
    fun fieldCipher_encryptsAndFailsClosedOnTamperedData() {
        val secretPlaintext = "Mom's phone number is +91 98765 43210"
        val encrypted = FieldCipher.encrypt(secretPlaintext)

        assertTrue(encrypted.startsWith("ENCv1:"))
        assertNotEquals(secretPlaintext, encrypted)
        assertFalse(encrypted.contains("98765"))

        val decrypted = FieldCipher.decrypt(encrypted)
        assertEquals(secretPlaintext, decrypted)

        // Reject unencrypted plaintext (fail closed)
        try {
            FieldCipher.decrypt("Plaintext without ENCv1 prefix")
            fail("FieldCipher.decrypt must fail closed on unencrypted input")
        } catch (_: IllegalStateException) {
            // Expected fail-closed behavior
        }

        // Reject tampered ciphertext (fail closed on GCM tag mismatch)
        val tampered = encrypted.dropLast(4) + "AAAA"
        try {
            FieldCipher.decrypt(tampered)
            fail("FieldCipher.decrypt must fail closed when GCM authentication tag fails")
        } catch (_: IllegalStateException) {
            // Expected fail-closed behavior
        }
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

        val lowDecision = gate.evaluate(
            toolCall = ToolCallSpec("1", "toggle_flashlight", mapOf("enabled" to "true")),
            tool = lowTool,
            confirmationLevel = ConfirmationLevel.STANDARD,
            screenContext = null
        )
        assertTrue(lowDecision.allowedImmediately)
        assertFalse(lowDecision.requiresConfirmation)

        val highDecision = gate.evaluate(
            toolCall = ToolCallSpec("2", "call_contact", mapOf("contact_name" to "Mom")),
            tool = highTool,
            confirmationLevel = ConfirmationLevel.STANDARD,
            screenContext = null
        )
        assertFalse(highDecision.allowedImmediately)
        assertTrue(highDecision.requiresConfirmation)

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
        tools: Set<Tool>,
        killSwitch: KillSwitch = KillSwitch()
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
            killSwitch = killSwitch
        )
    }
}
