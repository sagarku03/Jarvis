package com.example

import android.content.ContextWrapper
import com.example.data.local.ApiKeyStore
import com.example.data.local.FieldCipher
import com.example.data.remote.GeminiClient
import com.example.data.remote.GeminiTurnResult
import com.example.data.remote.KeyVerificationResult
import com.example.data.repository.AgentBackendRepositoryImpl
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.agent.MemoryLearner
import com.example.domain.contacts.ContactsResolver
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConversationItem
import com.example.domain.model.ConversationSummaryItem
import com.example.domain.model.DeviceVoiceOption
import com.example.domain.model.GeminiVoice
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemoryFact
import com.example.domain.model.MessageRole
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.ToolExecutionResult
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceSessionState
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGateImpl
import com.example.domain.tool.Tool
import com.example.domain.tool.ToolDescriptor
import com.example.domain.tool.ToolRegistry
import com.example.services.JarvisAccessibilityService
import com.example.services.JarvisNotificationListener
import com.example.services.voice.SpeechRecognizerWrapper
import com.example.services.voice.TtsEngineWrapper
import com.example.services.voice.VoiceSessionManager
import com.example.services.voice.WakeWordEngine
import com.example.tools.CallContactTool
import com.example.tools.MemorySafetyFilter
import com.example.tools.ReadScreenTool
import com.example.tools.TapElementTool
import com.example.domain.agent.LocalIntentRouter
import com.example.domain.agent.LocalRouteResult
import com.example.services.voice.TtsManager
import com.example.ui.viewmodel.SettingsViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExampleUnitTest {

    private class FakeWakeWordEngine : WakeWordEngine {
        override val isWakeWordConfigured: Boolean = false
        override val isMonitoring = MutableStateFlow(false)
        override val isPaused = MutableStateFlow(false)
        override val sensitivity = MutableStateFlow(0.5f)
        override fun startMonitoring(onWakeWordDetected: () -> Unit) {}
        override fun stopMonitoring() {}
        override fun setPaused(paused: Boolean) {}
        override fun setSensitivity(value: Float) {}
        override fun release() {}
    }

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
        override val availableVoices = MutableStateFlow<List<DeviceVoiceOption>>(emptyList())

        var lastSpokenText: String? = null
        var pendingOnComplete: (() -> Unit)? = null

        override fun refreshAvailableVoices(language: AppLanguage) {}

        override fun speak(
            text: String,
            language: AppLanguage,
            selectedVoiceName: String,
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

    // 1. GeminiClient with MockWebServer: verifies x-goog-api-key header, key NEVER in URL, functionCall parsed, functionResponse sent, 429 and 403 mapped cleanly
    @Test
    fun geminiClient_mockWebServer_verifiesHeaderUrlFunctionCallAndErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val apiKeyStore = ApiKeyStore(ContextWrapper(null))
            val testApiKey = "AIzaSyTestSecretKey9876"
            apiKeyStore.save(testApiKey)

            val client = GeminiClient(
                okHttpClient = OkHttpClient(),
                moshi = moshi,
                apiKeyStore = apiKeyStore,
                baseUrlProvider = { baseUrl }
            )

            // A) Verify GET /v1beta/models
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "models": [
                            {
                              "name": "models/gemini-3.8-flash",
                              "displayName": "Gemini 3.8 Flash",
                              "supportedGenerationMethods": ["generateContent"]
                            },
                            {
                              "name": "models/text-embedding-004",
                              "displayName": "Embedding",
                              "supportedGenerationMethods": ["embedContent"]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )
            val verifyRes = client.verifyKeyAndListModels(testApiKey)
            assertTrue(verifyRes is KeyVerificationResult.Valid)
            val models = (verifyRes as KeyVerificationResult.Valid).availableModels
            assertEquals(listOf("gemini-3.8-flash"), models)

            val recordedModelsReq = server.takeRequest()
            assertEquals("GET", recordedModelsReq.method)
            assertEquals("/v1beta/models", recordedModelsReq.path)
            assertEquals(testApiKey, recordedModelsReq.getHeader("x-goog-api-key"))
            assertFalse(
                "API key must NEVER appear in the request URL",
                recordedModelsReq.path.orEmpty().contains(testApiKey)
            )

            // B) Verify POST generateContent with functionCall & functionResponse
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "candidates": [
                            {
                              "content": {
                                "role": "model",
                                "parts": [
                                  {
                                    "functionCall": {
                                      "name": "set_timer",
                                      "args": { "seconds": "120", "label": "Tea" }
                                    }
                                  }
                                ]
                              },
                              "finishReason": "STOP"
                            }
                          ],
                          "usageMetadata": {
                            "promptTokenCount": 42,
                            "candidatesTokenCount": 15,
                            "totalTokenCount": 57
                          }
                        }
                        """.trimIndent()
                    )
            )

            val historyWithToolOutput = listOf(
                ChatMessage(id = 1, role = MessageRole.USER, content = "Set a 2 minute timer for tea"),
                ChatMessage(id = 2, role = MessageRole.TOOL, content = "Timer started", toolName = "set_timer")
            )
            val turnRes = client.generateContent(
                modelId = "gemini-3.8-flash",
                systemInstruction = "You are JARVIS.",
                messages = historyWithToolOutput,
                tools = listOf(
                    ToolDescriptor(
                        name = "set_timer",
                        description = "Sets a countdown timer",
                        jsonSchema = """{"type":"object","properties":{"seconds":{"type":"integer"},"label":{"type":"string"}}}""",
                        riskLevel = RiskLevel.LOW.name
                    )
                )
            )
            assertTrue(turnRes is GeminiTurnResult.Success)
            val success = turnRes as GeminiTurnResult.Success
            assertEquals(1, success.toolCalls.size)
            assertEquals("set_timer", success.toolCalls.first().name)
            assertEquals("120", success.toolCalls.first().args["seconds"])
            assertEquals(42, success.promptTokens)
            assertEquals(15, success.candidateTokens)

            val recordedGenReq = server.takeRequest()
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", recordedGenReq.path)
            assertEquals(testApiKey, recordedGenReq.getHeader("x-goog-api-key"))
            assertFalse(recordedGenReq.path.orEmpty().contains(testApiKey))
            val sentBody = recordedGenReq.body.readUtf8()
            assertTrue("Sent payload must include functionResponse for TOOL history", sentBody.contains("\"functionResponse\""))
            assertTrue("Sent payload must include functionDeclarations", sentBody.contains("\"functionDeclarations\""))

            // C) Verify 403 maps to KeyRejected
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Invalid key"}}"""))
            val rejected = client.generateContent(
                modelId = "gemini-3.8-flash",
                systemInstruction = "System",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hi")),
                tools = emptyList()
            )
            assertTrue(rejected is GeminiTurnResult.KeyRejected)

            // D) Verify 429 maps to RateLimited after retries
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            val quota = client.generateContent(
                modelId = "gemini-3.8-flash",
                systemInstruction = "System",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hi")),
                tools = emptyList()
            )
            assertTrue(quota is GeminiTurnResult.RateLimited)
        } finally {
            server.shutdown()
        }
    }

    // 2. Memory Sensitive-Data Filter & Context Budget Test
    @Test
    fun memorySafetyFilter_rejectsOtpsPasswordsCardsAndNormalizesKeys() {
        assertEquals("favorite_ipl_team", MemorySafetyFilter.normalizeKey("Favorite IPL Team!"))

        // Safe facts allowed
        assertNull(MemorySafetyFilter.validateFact("favorite_ipl_team", "Chennai Super Kings"))
        assertNull(MemorySafetyFilter.validateFact("home_city", "Pune"))

        // OTP rejected
        assertNotNull(MemorySafetyFilter.validateFact("bank_otp", "Your OTP is 849201"))
        assertNotNull(MemorySafetyFilter.validateFact("verification_code", "482910"))

        // Password / PIN rejected
        assertNotNull(MemorySafetyFilter.validateFact("wifi_password", "hunter2"))
        assertNotNull(MemorySafetyFilter.validateFact("upi_pin", "1234"))

        // 16-digit card number rejected
        assertNotNull(MemorySafetyFilter.validateFact("my_card", "4532 0151 1283 0366"))

        // Context budget test: buildSystemInstruction stays <= 10,000 chars (~2,500 tokens)
        val manyFacts = (1..120).map { idx ->
            MemoryFact(
                id = idx.toLong(),
                category = MemoryCategory.NOTE.name,
                key = "fact_key_$idx",
                value = "This is a detailed memory fact value number $idx stored for recall.",
                pinned = idx <= 10
            )
        }
        val summaries = (1..10).map { idx ->
            ConversationSummaryItem(
                id = idx.toLong(),
                conversationId = 1L,
                fromMessageId = (idx - 1) * 30L + 1L,
                toMessageId = idx * 30L,
                text = "Summary block $idx covering user preferences and past actions.",
                createdAtMs = idx * 1000L
            )
        }
        val sysInstruction = MemoryLearner.buildSystemInstruction(
            pinnedFacts = manyFacts.filter { it.pinned },
            relevantFacts = manyFacts.take(25),
            recentSummaries = summaries,
            screenContext = ScreenContextSnapshot("com.whatsapp", "Chat with Mom")
        )
        assertTrue(
            "System instruction must stay within 10,000 chars (~2,500 tokens), was ${sysInstruction.length}",
            sysInstruction.length <= MemoryLearner.MAX_SYSTEM_PROMPT_CHARS
        )
        assertTrue(sysInstruction.contains("fact_key_1"))
    }

    // 3. Offline parser: asks for missing alarm time / timer duration instead of hardcoded 7:00 or 5 min
    @Test
    fun offlineCommandParser_asksForMissingAlarmOrTimerInsteadOfHardcodedDefaults() {
        val alarmWithoutTime = AgentBackendRepositoryImpl.parseOfflineCommand("Set an alarm")
        assertNotNull(alarmWithoutTime)
        assertTrue("Must not call set_alarm with a fake default time", alarmWithoutTime!!.toolCalls.isEmpty())
        assertEquals("What time should I set the alarm for?", alarmWithoutTime.assistantText)

        val alarmWithTime = AgentBackendRepositoryImpl.parseOfflineCommand("Set an alarm for 6:45")
        assertNotNull(alarmWithTime)
        assertEquals(1, alarmWithTime!!.toolCalls.size)
        assertEquals("6", alarmWithTime.toolCalls.first().args["hour"])
        assertEquals("45", alarmWithTime.toolCalls.first().args["minute"])

        val timerWithoutDuration = AgentBackendRepositoryImpl.parseOfflineCommand("Set a timer")
        assertNotNull(timerWithoutDuration)
        assertTrue("Must not call set_timer with a fake 5-minute default", timerWithoutDuration!!.toolCalls.isEmpty())
        assertEquals("How long should I set the timer for?", timerWithoutDuration.assistantText)

        val timerWithDuration = AgentBackendRepositoryImpl.parseOfflineCommand("Set a timer for 10 minutes")
        assertNotNull(timerWithDuration)
        assertEquals(1, timerWithDuration!!.toolCalls.size)
        assertEquals("600", timerWithDuration.toolCalls.first().args["seconds"])
    }

    // 4. 5-turn voice session without self-triggering
    @Test
    fun voiceSession_fiveTurnConversation_neverSelfTriggers() = runBlocking {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val manager = VoiceSessionManager(stt, tts, null, FakeWakeWordEngine())

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

            stt.lastOnFinal?.invoke(userTurns[i])
            assertEquals(VoiceSessionState.THINKING, manager.state.value)
            assertFalse("Mic must close during THINKING", stt.isListening.value)

            manager.speakReply(
                rawText = assistantReplies[i],
                language = AppLanguage.EN_IN,
                selectedVoiceName = "",
                speed = 1.0f
            )
            assertEquals(VoiceSessionState.SPEAKING, manager.state.value)
            assertFalse("Mic must NEVER open while SPEAKING", stt.isListening.value)

            tts.finishSpeakingNow()
            delay(VoiceSessionManager.SPEAKER_TAIL_DELAY_MS + 40L)
            assertEquals(VoiceSessionState.IDLE, manager.state.value)

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

    // 5. "Call Mom" with 2 "Mom" contacts -> asks which one, requires confirmation before dialing
    @Test
    fun callMom_withTwoMatchingContacts_requiresDisambiguationAndConfirmation() = runBlocking {
        val memoryWithTwoMoms = FakeMemoryRepository(
            initialFacts = listOf(
                MemoryFact(id = 1L, category = "CONTACT", key = "Mom Mobile", value = "+919876543210"),
                MemoryFact(id = 2L, category = "CONTACT", key = "Mom Home", value = "+919876500001")
            )
        )

        val dummyContext = ContextWrapper(null)
        val resolver = ContactsResolver(dummyContext, memoryWithTwoMoms)
        val candidates = resolver.resolveCandidates("Mom")
        assertEquals("Should resolve both 'Mom' contacts", 2, candidates.size)

        val callTool = CallContactTool(dummyContext, resolver)
        val ambiguousResult = callTool.execute(mapOf("contact_name" to "Mom"))
        assertFalse(ambiguousResult.success)
        assertTrue(ambiguousResult.outputEn.contains("Multiple contacts match 'Mom'"))

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
        assertNotNull(pending)
        assertEquals(2, pending?.candidateContacts?.size)

        gate.resolveUserApproval(approved = true, selectedPhoneOverride = "+919876500001")
        val outcome = approvalDeferred.await()
        assertTrue(outcome.approved)
        assertEquals("+919876500001", outcome.selectedPhoneOverride)
    }

    // 6. KillSwitch stops listening, speaking, and any running tool within 1s, and persists [Cancelled]
    @Test
    fun killSwitch_stopsListeningSpeakingAndRunningToolUnderOneSecond_andPersistsCancelled() = runBlocking {
        val stt = FakeSpeechRecognizer()
        val tts = FakeTtsEngine()
        val voiceManager = VoiceSessionManager(stt, tts, null, FakeWakeWordEngine())
        val killSwitch = KillSwitch()

        var voiceKilled = false
        killSwitch.registerCancelCallback {
            voiceKilled = true
            voiceManager.stopAll()
        }

        voiceManager.speakReply(
            rawText = "Executing a multi-step routine now.",
            language = AppLanguage.EN_IN,
            selectedVoiceName = "",
            speed = 1.0f
        )
        assertTrue(tts.isSpeaking.value)

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
                language: AppLanguage,
                summaries: List<ConversationSummaryItem>,
                onTextChunk: ((String) -> Unit)?,
                isVoiceInput: Boolean
            ): AgentStepDecision {
                return AgentStepDecision(
                    assistantText = null,
                    toolCalls = listOf(ToolCallSpec("t1", "wait_for_element", emptyMap()))
                )
            }
        }

        val chatRepo = FakeChatRepository()
        val orchestrator = createTestOrchestrator(
            backendRepository = backendRepo,
            chatRepository = chatRepo,
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

        killSwitch.trigger("Unit test emergency stop")
        turnJob.await()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("KillSwitch must stop everything in under 1000ms (took ${elapsedMs}ms)", elapsedMs < 1_000L)
        assertTrue(voiceKilled)
        assertFalse(stt.isListening.value)
        assertFalse(tts.isSpeaking.value)
        assertEquals(VoiceSessionState.IDLE, voiceManager.state.value)
        assertTrue(
            "User message must be persisted immediately before Gemini call",
            chatRepo.savedMessages.any { it.role == MessageRole.USER && it.content == "Wait for page" }
        )
    }

    // 7. FieldCipher AES-GCM round-trip and fail-closed verification (for v3->v4 migration)
    @Test
    fun fieldCipher_encryptsAndFailsClosedOnTamperedData() {
        val secretPlaintext = "Mom's phone number is +91 98765 43210"
        val encrypted = FieldCipher.encrypt(secretPlaintext)

        assertTrue(encrypted.startsWith("ENCv1:"))
        assertNotEquals(secretPlaintext, encrypted)
        assertFalse(encrypted.contains("98765"))

        val decrypted = FieldCipher.decrypt(encrypted)
        assertEquals(secretPlaintext, decrypted)

        try {
            FieldCipher.decrypt("Plaintext without ENCv1 prefix")
            fail("FieldCipher.decrypt must fail closed on unencrypted input")
        } catch (_: IllegalStateException) {
            // Expected
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

    @Test
    fun localIntentRouter_flashlightAndVolumeDirectMatches() {
        val onRoute = com.example.domain.agent.LocalIntentRouter.route("turn on flashlight") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(onRoute)
        assertEquals("toggle_flashlight", onRoute?.toolName)
        assertEquals("true", onRoute?.args?.get("enabled"))

        val offRoute = com.example.domain.agent.LocalIntentRouter.route("torch band kar do") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(offRoute)
        assertEquals("toggle_flashlight", offRoute?.toolName)
        assertEquals("false", offRoute?.args?.get("enabled"))

        val volRoute = com.example.domain.agent.LocalIntentRouter.route("set volume to 80 percent") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(volRoute)
        assertEquals("adjust_volume", volRoute?.toolName)
        assertEquals("80", volRoute?.args?.get("level_percent"))
    }

    @Test
    fun localIntentRouter_timeAndYouTubeFastPath() {
        val timeRoute = com.example.domain.agent.LocalIntentRouter.route("what time is it") as? com.example.domain.agent.LocalRouteResult.DirectReply
        assertNotNull(timeRoute)
        assertTrue(timeRoute?.reply?.contains("It's") == true)

        val ytRoute = com.example.domain.agent.LocalIntentRouter.route("play jazz on youtube") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(ytRoute)
        assertEquals("play_media", ytRoute?.toolName)
        assertEquals("jazz", ytRoute?.args?.get("query"))
    }

    @Test
    fun memorySafetyFilter_blocksAppMetaAndKeyPhrases() {
        assertNotNull(MemorySafetyFilter.validateFact("my api key", "AIzaSyD..."))
        assertNotNull(MemorySafetyFilter.validateFact("gemini_key", "secret"))
        assertNotNull(MemorySafetyFilter.validateFact("note", "key rejected by server"))
        assertNotNull(MemorySafetyFilter.validateFact("error", "401 unauthorized"))
        // Valid normal user preference should pass
        assertNull(MemorySafetyFilter.validateFact("favorite coffee", "oat latte"))
    }

    private class FakeChatRepository : ChatRepository {
        val savedMessages = mutableListOf<ChatMessage>()
        override val messagesFlow: Flow<List<ChatMessage>> = MutableStateFlow(emptyList())
        override val conversationsFlow: Flow<List<ConversationItem>> = MutableStateFlow(emptyList())
        override val summariesFlow: Flow<List<ConversationSummaryItem>> = MutableStateFlow(emptyList())
        override suspend fun getOrCreateActiveConversationId(nowMs: Long): Long = 1L
        override suspend fun startNewConversation(title: String, nowMs: Long): Long = 2L
        override suspend fun getRecentContextMessages(limit: Int): List<ChatMessage> = savedMessages.takeLast(limit)
        override suspend fun getMessagesForConversation(conversationId: Long): List<ChatMessage> = savedMessages
        override suspend fun searchMessages(query: String): List<ChatMessage> = emptyList()
        override suspend fun getRecentSummaries(limit: Int): List<ConversationSummaryItem> = emptyList()
        override suspend fun saveSummary(summary: ConversationSummaryItem) {}
        override suspend fun appendMessage(message: ChatMessage): Long {
            savedMessages.add(message)
            return savedMessages.size.toLong()
        }
        override suspend fun deleteConversation(conversationId: Long) {
            savedMessages.clear()
        }
        override suspend fun clearAllMessages() { savedMessages.clear() }
    }

    private class FakeMemoryRepository(
        initialFacts: List<MemoryFact> = emptyList()
    ) : MemoryRepository {
        private val facts = initialFacts.toMutableList()
        override val memoriesFlow: Flow<List<MemoryFact>> = MutableStateFlow(facts)
        override val learnedTodayFlow: Flow<List<MemoryFact>> = MutableStateFlow(emptyList())
        override fun searchMemories(query: String): Flow<List<MemoryFact>> = MutableStateFlow(facts)
        override suspend fun searchTopFactsForContext(query: String, limit: Int): List<MemoryFact> = facts.take(limit)
        override suspend fun getAllMemoriesSnapshot(): List<MemoryFact> = facts.toList()
        override suspend fun upsertMemory(
            category: String,
            key: String,
            value: String,
            source: String,
            confidence: Float,
            importance: Int,
            pinned: Boolean
        ): Long = 1L
        override suspend fun updateMemory(
            id: Long,
            category: String,
            key: String,
            value: String,
            pinned: Boolean,
            importance: Int
        ) {}
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun deleteMemory(id: Long) {}
        override suspend fun deleteMemoriesByKeyOrQuery(query: String): Int = 0
        override suspend fun clearAllMemories() { facts.clear() }
        override suspend fun exportAllAsJson(): String = "{}"
    }

    private class FakeSettingsRepository(
        initialSettings: UserSettings = UserSettings()
    ) : SettingsRepository {
        val flow = MutableStateFlow(initialSettings)
        override val settingsFlow: Flow<UserSettings> = flow

        override suspend fun setSetupComplete(complete: Boolean) {
            flow.value = flow.value.copy(setupComplete = complete)
        }
        override suspend fun setSelectedModelId(modelId: String) {
            flow.value = flow.value.copy(selectedModelId = modelId)
        }
        override suspend fun setSelectedTextModelId(modelId: String) {
            flow.value = flow.value.copy(selectedTextModelId = modelId)
        }
        override suspend fun setSelectedVoiceModelId(modelId: String) {
            flow.value = flow.value.copy(selectedVoiceModelId = modelId)
        }
        override suspend fun setSelectedVoiceGender(gender: String) {
            flow.value = flow.value.copy(selectedVoiceGender = gender)
        }
        override suspend fun setSelectedLiveVoiceName(voiceName: String) {
            flow.value = flow.value.copy(selectedLiveVoiceName = voiceName)
        }
        override suspend fun setAvailableModels(models: List<String>) {
            flow.value = flow.value.copy(availableModels = models)
        }
        override suspend fun setLanguage(language: AppLanguage) {
            flow.value = flow.value.copy(language = language)
        }
        override suspend fun setSelectedVoiceName(voiceName: String) {
            flow.value = flow.value.copy(selectedVoiceName = voiceName)
        }
        override suspend fun setSpeechSpeed(speed: Float) {
            flow.value = flow.value.copy(speechSpeed = speed)
        }
        override suspend fun setSpeechPitch(pitch: Float) {
            flow.value = flow.value.copy(speechPitch = pitch)
        }
        override suspend fun setAutoLearnEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(autoLearnEnabled = enabled)
        }
        override suspend fun setAskBeforeSavingFact(ask: Boolean) {
            flow.value = flow.value.copy(askBeforeSavingFact = ask)
        }
        override suspend fun recordGeminiUsage(tokensUsed: Int, nowMs: Long) {}
        override suspend fun setFloatingOrbEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(floatingOrbEnabled = enabled)
        }
        override suspend fun setConfirmationLevel(level: ConfirmationLevel) {
            flow.value = flow.value.copy(confirmationLevel = level)
        }
        override suspend fun setKillSwitchActive(active: Boolean) {
            flow.value = flow.value.copy(killSwitchActive = active)
        }
        override suspend fun setVoiceEngine(voiceEngine: com.example.domain.model.VoiceEngineOption) {
            flow.value = flow.value.copy(voiceEngine = voiceEngine)
        }
        override suspend fun setWakePhraseEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(wakePhraseEnabled = enabled)
        }
        override suspend fun setWakePhraseScreenOnOnly(enabled: Boolean) {
            flow.value = flow.value.copy(wakePhraseScreenOnOnly = enabled)
        }
        override suspend fun setWakePhraseStopAfter30m(enabled: Boolean) {
            flow.value = flow.value.copy(wakePhraseStopAfter30m = enabled)
        }
        override suspend fun setSelectedTtsModelId(modelId: String) {
            flow.value = flow.value.copy(selectedTtsModelId = modelId)
        }
        override suspend fun setRealtimeLiveMode(enabled: Boolean) {
            flow.value = flow.value.copy(isRealtimeLiveMode = enabled)
        }
        override suspend fun setVoiceTimings(firstTokenMs: Long, firstWordMs: Long) {}
    }

    private fun createTestOrchestrator(
        backendRepository: AgentBackendRepository,
        chatRepository: ChatRepository = FakeChatRepository(),
        tools: Set<Tool>,
        killSwitch: KillSwitch = KillSwitch(),
        settingsRepository: SettingsRepository = FakeSettingsRepository()
    ): AgentOrchestrator {
        val memoryRepo = FakeMemoryRepository()
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

        return AgentOrchestrator(
            agentBackendRepository = backendRepository,
            chatRepository = chatRepository,
            memoryRepository = memoryRepo,
            activityLogRepository = logRepo,
            routineRepository = routineRepo,
            settingsRepository = settingsRepository,
            toolRegistry = ToolRegistry(tools),
            safetyGate = SafetyGateImpl(null),
            killSwitch = killSwitch
        )
    }

    @Test
    fun localIntentRouter_verifies40PlusPhrases() {
        val testPhrases = listOf(
            // Time
            "what time is it" to "DirectReply",
            "what's the time" to "DirectReply",
            "time kya hua" to "DirectReply",
            "kitne baje" to "DirectReply",
            // Battery
            "battery percentage" to "DirectReply",
            "battery kitni hai" to "DirectReply",
            // Flashlight
            "turn on flashlight" to "toggle_flashlight",
            "torch on" to "toggle_flashlight",
            "chalu karo torch" to "toggle_flashlight",
            "turn off flashlight" to "toggle_flashlight",
            "torch band karo" to "toggle_flashlight",
            // Volume
            "volume up" to "adjust_volume",
            "sound increase" to "adjust_volume",
            "awaz tez karo" to "adjust_volume",
            "volume down" to "adjust_volume",
            "volume kam karo" to "adjust_volume",
            "mute" to "adjust_volume",
            "awaz band" to "adjust_volume",
            "set volume to 80%" to "adjust_volume",
            // Navigation system
            "go back" to "system_navigation",
            "back" to "system_navigation",
            "piche jao" to "system_navigation",
            "go home" to "system_navigation",
            "home screen" to "system_navigation",
            "recents" to "system_navigation",
            "recent apps" to "system_navigation",
            // Quick Settings
            "wifi settings" to "open_app",
            "open wifi" to "open_app",
            "bluetooth settings" to "open_app",
            "open bluetooth" to "open_app",
            "brightness" to "open_app",
            "display settings" to "open_app",
            "do not disturb" to "open_app",
            "dnd" to "open_app",
            // Timers & Alarms
            "set a timer for 10 minutes" to "set_timer",
            "set timer for 30 seconds" to "set_timer",
            "set alarm for 7 am" to "set_alarm",
            "wake me up at 6:30 am" to "set_alarm",
            // Play Store
            "install WhatsApp" to "open_url",
            "download Spotify" to "open_url",
            // YouTube
            "play jazz on youtube" to "play_media",
            "youtube pe lo-fi chalao" to "play_media",
            // Web Search
            "google weather in delhi" to "web_search",
            "search for latest news" to "web_search",
            // Calls & Messages
            "call Rahul" to "call_contact",
            "phone karo Mummy" to "call_contact",
            "message Priya I will be late" to "send_whatsapp_or_telegram",
            // Open App with Aliases
            "open yt" to "open_app",
            "open wa" to "open_app",
            "open insta" to "open_app",
            "open gpay" to "open_app"
        )

        for ((phrase, expectedTarget) in testPhrases) {
            val result = com.example.domain.agent.LocalIntentRouter.route(phrase, null)
            assertNotNull("Phrase failed to route: '$phrase'", result)
            when (result) {
                is com.example.domain.agent.LocalRouteResult.ToolAction -> {
                    assertEquals(
                        "Phrase '$phrase' routed to unexpected tool",
                        expectedTarget,
                        result.toolName
                    )
                }
                is com.example.domain.agent.LocalRouteResult.DirectReply -> {
                    assertEquals("Phrase '$phrase' expected DirectReply", expectedTarget, "DirectReply")
                }
                is com.example.domain.agent.LocalRouteResult.Stop -> {
                    assertEquals(expectedTarget, "Stop")
                }
                null -> fail("Result should not be null for phrase: $phrase")
            }
        }
    }

    @Test
    fun wakePhraseService_matchesWakePhrasesWithFuzzyTolerance() {
        val validPhrases = listOf(
            "hey jarvis",
            "ok jarvis",
            "okay jarvis",
            "hi jarvis",
            "jarvis",
            "arre jarvis",
            "sun jarvis",
            "jarvis suno",
            "hey jarvis what's the weather",
            "jarvis please help"
        )
        for (phrase in validPhrases) {
            assertTrue(
                "Expected phrase '$phrase' to match wake phrase",
                com.example.services.WakePhraseService.matchesWakePhrase(phrase)
            )
        }

        assertFalse(
            "Random phrase should not match wake phrase",
            com.example.services.WakePhraseService.matchesWakePhrase("hello world today")
        )
    }

    @Test
    fun localIntentRouter_appAliasesResolvedCorrectly() {
        assertEquals("YouTube", com.example.domain.agent.LocalIntentRouter.resolveAppName("yt", null))
        assertEquals("WhatsApp", com.example.domain.agent.LocalIntentRouter.resolveAppName("wa", null))
        assertEquals("Instagram", com.example.domain.agent.LocalIntentRouter.resolveAppName("insta", null))
        assertEquals("Google Pay", com.example.domain.agent.LocalIntentRouter.resolveAppName("gpay", null))
        assertEquals("Telegram", com.example.domain.agent.LocalIntentRouter.resolveAppName("tg", null))
    }

    @Test
    fun localIntentRouter_hinglishPostfixAppAndYoutubeMatches() {
        val ytOpen = com.example.domain.agent.LocalIntentRouter.route("YouTube open karo") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(ytOpen)
        assertEquals("open_app", ytOpen?.toolName)
        assertEquals("YouTube", ytOpen?.args?.get("app_name"))

        val waOpen = com.example.domain.agent.LocalIntentRouter.route("WhatsApp kholo") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(waOpen)
        assertEquals("open_app", waOpen?.toolName)
        assertEquals("WhatsApp", waOpen?.args?.get("app_name"))

        val instaOpen = com.example.domain.agent.LocalIntentRouter.route("Instagram chalu karo") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(instaOpen)
        assertEquals("open_app", instaOpen?.toolName)
        assertEquals("Instagram", instaOpen?.args?.get("app_name"))

        val ytPlay1 = com.example.domain.agent.LocalIntentRouter.route("arijit singh ke gaane chalao youtube pe") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(ytPlay1)
        assertEquals("play_media", ytPlay1?.toolName)
        assertEquals("arijit singh ke gaane", ytPlay1?.args?.get("query"))

        val ytPlay2 = com.example.domain.agent.LocalIntentRouter.route("youtube pe lofi songs play karo") as? com.example.domain.agent.LocalRouteResult.ToolAction
        assertNotNull(ytPlay2)
        assertEquals("play_media", ytPlay2?.toolName)
        assertEquals("lofi songs", ytPlay2?.args?.get("query"))
    }

    @Test
    fun dualModelArchitecture_defaultsAndFriendlyNames() {
        assertEquals("gemini-3.8-flash", GeminiClient.DEFAULT_TEXT_MODEL)
        assertEquals("gemini-3.8-live", GeminiClient.DEFAULT_VOICE_MODEL)

        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("gemini-3.8-live"))
        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("models/gemini-3.8-live"))
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("gemini-3.8-flash"))
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("models/gemini-3.8-flash"))

        val settings = UserSettings()
        assertEquals("gemini-3.8-flash", settings.selectedModelId)
        assertEquals("gemini-3.8-flash", settings.selectedTextModelId)
        assertEquals("gemini-3.8-live", settings.selectedVoiceModelId)
        assertEquals("MALE", settings.selectedVoiceGender)
        assertEquals("Puck", settings.selectedLiveVoiceName)
    }

    @Test
    fun dualModelArchitecture_fallbackChainGracefullyResolves() {
        // Fallback chain: 3.8-live -> 3.8-flash
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("gemini-3.8-live"))
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("gemini-3.8-flash"))
    }

    @Test
    fun geminiVoice_supportsMaleAndFemaleVoices() {
        // Male: Puck (Dynamic), Charon (Deep), Fenrir (Authoritative)
        assertEquals(GeminiVoice.PUCK, GeminiVoice.fromVoiceName("Puck"))
        assertEquals("MALE", GeminiVoice.PUCK.gender)
        assertEquals("Dynamic", GeminiVoice.PUCK.description)

        assertEquals(GeminiVoice.CHARON, GeminiVoice.fromVoiceName("Charon"))
        assertEquals("MALE", GeminiVoice.CHARON.gender)
        assertEquals("Deep", GeminiVoice.CHARON.description)

        assertEquals(GeminiVoice.FENRIR, GeminiVoice.fromVoiceName("Fenrir"))
        assertEquals("MALE", GeminiVoice.FENRIR.gender)
        assertEquals("Authoritative", GeminiVoice.FENRIR.description)

        // Female: Aoede (Warm), Kore (Calm), Leda (Clear)
        assertEquals(GeminiVoice.AOEDE, GeminiVoice.fromVoiceName("Aoede"))
        assertEquals("FEMALE", GeminiVoice.AOEDE.gender)
        assertEquals("Warm", GeminiVoice.AOEDE.description)

        assertEquals(GeminiVoice.KORE, GeminiVoice.fromVoiceName("Kore"))
        assertEquals("FEMALE", GeminiVoice.KORE.gender)
        assertEquals("Calm", GeminiVoice.KORE.description)

        assertEquals(GeminiVoice.LEDA, GeminiVoice.fromVoiceName("Leda"))
        assertEquals("FEMALE", GeminiVoice.LEDA.gender)
        assertEquals("Clear", GeminiVoice.LEDA.description)

        val maleVoices = GeminiVoice.getVoicesForGender("MALE")
        assertEquals(3, maleVoices.size)
        assertTrue(maleVoices.any { it.voiceName == "Puck" })
        assertTrue(maleVoices.any { it.voiceName == "Charon" })
        assertTrue(maleVoices.any { it.voiceName == "Fenrir" })

        val femaleVoices = GeminiVoice.getVoicesForGender("FEMALE")
        assertEquals(3, femaleVoices.size)
        assertTrue(femaleVoices.any { it.voiceName == "Aoede" })
        assertTrue(femaleVoices.any { it.voiceName == "Kore" })
        assertTrue(femaleVoices.any { it.voiceName == "Leda" })
    }

    @Test
    fun ttsManager_resolvesLiveVoiceNameAccurately() {
        assertEquals("Puck", com.example.services.voice.TtsManager.resolveLiveVoiceName("Puck", "MALE"))
        assertEquals("Charon", com.example.services.voice.TtsManager.resolveLiveVoiceName("Charon", "MALE"))
        assertEquals("Fenrir", com.example.services.voice.TtsManager.resolveLiveVoiceName("Fenrir", "MALE"))
        assertEquals("Aoede", com.example.services.voice.TtsManager.resolveLiveVoiceName("Aoede", "FEMALE"))
        assertEquals("Kore", com.example.services.voice.TtsManager.resolveLiveVoiceName("Kore", "FEMALE"))
        assertEquals("Leda", com.example.services.voice.TtsManager.resolveLiveVoiceName("Leda", "FEMALE"))

        // Legacy / fallback checks
        assertEquals("Puck", com.example.services.voice.TtsManager.resolveLiveVoiceName("", "MALE"))
        assertEquals("Aoede", com.example.services.voice.TtsManager.resolveLiveVoiceName("", "FEMALE"))
    }

    @Test
    fun volumeTool_verifiesAdjustVolumeNameParametersAndBilingualOutput() = runBlocking {
        val dummyContext = ContextWrapper(null)
        val volTool = com.example.tools.VolumeTool(dummyContext)
        assertEquals("adjust_volume", volTool.name)
        assertTrue(volTool.jsonSchema.contains("direction"))
        assertTrue(volTool.jsonSchema.contains("level_percent"))

        // Null audio manager in plain context should return graceful bilingual failure without throwing
        val res = volTool.execute(mapOf("direction" to "up"))
        assertFalse(res.success)
        assertTrue(res.outputEn.isNotBlank())
        assertTrue(res.outputHi.isNotBlank())
    }

    @Test
    fun allTools_returnNonEmptyBilingualEnglishAndHindiOutputs() = runBlocking {
        val dummyContext = ContextWrapper(null)
        val memoryRepo = FakeMemoryRepository()
        val resolver = ContactsResolver(dummyContext, memoryRepo)

        // 1. CallContactTool
        val callRes = com.example.tools.CallContactTool(dummyContext, resolver).execute(mapOf("contact_name" to "NonExistent"))
        assertFalse(callRes.success)
        assertTrue(callRes.outputEn.isNotEmpty())
        assertTrue(callRes.outputHi.isNotEmpty())

        // 2. SendSmsTool
        val smsRes = com.example.tools.SendSmsTool(dummyContext, resolver).execute(mapOf("contact_name" to "NonExistent", "message" to "Hello"))
        assertFalse(smsRes.success)
        assertTrue(smsRes.outputEn.isNotEmpty())
        assertTrue(smsRes.outputHi.isNotEmpty())

        // 3. WhatsAppTelegramTool
        val waRes = com.example.tools.WhatsAppTelegramTool(dummyContext, resolver).execute(mapOf("contact_name" to "NonExistent", "message" to "Hello"))
        assertFalse(waRes.success)
        assertTrue(waRes.outputEn.isNotEmpty())
        assertTrue(waRes.outputHi.isNotEmpty())

        // 4. OpenAppTool
        val appRes = com.example.tools.OpenAppTool(dummyContext).execute(emptyMap())
        assertFalse(appRes.success)
        assertTrue(appRes.outputEn.isNotEmpty())
        assertTrue(appRes.outputHi.isNotEmpty())

        // 5. OpenUrlTool
        val urlRes = com.example.tools.OpenUrlTool(dummyContext).execute(emptyMap())
        assertFalse(urlRes.success)
        assertTrue(urlRes.outputEn.isNotEmpty())
        assertTrue(urlRes.outputHi.isNotEmpty())

        // 6. PlayMediaTool
        val mediaRes = com.example.tools.PlayMediaTool(dummyContext).execute(emptyMap())
        assertFalse(mediaRes.success)
        assertTrue(mediaRes.outputEn.isNotEmpty())
        assertTrue(mediaRes.outputHi.isNotEmpty())

        // 7. WebSearchTool
        val searchRes = com.example.tools.WebSearchTool(dummyContext).execute(emptyMap())
        assertFalse(searchRes.success)
        assertTrue(searchRes.outputEn.isNotEmpty())
        assertTrue(searchRes.outputHi.isNotEmpty())

        // 8. NavigateTool
        val navRes = com.example.tools.NavigateTool(dummyContext).execute(emptyMap())
        assertFalse(navRes.success)
        assertTrue(navRes.outputEn.isNotEmpty())
        assertTrue(navRes.outputHi.isNotEmpty())

        // 9. SetAlarmTool
        val alarmRes = com.example.tools.SetAlarmTool(dummyContext).execute(emptyMap())
        assertFalse(alarmRes.success)
        assertTrue(alarmRes.outputEn.isNotEmpty())
        assertTrue(alarmRes.outputHi.isNotEmpty())

        // 10. SetTimerTool
        val timerRes = com.example.tools.SetTimerTool(dummyContext).execute(emptyMap())
        assertFalse(timerRes.success)
        assertTrue(timerRes.outputEn.isNotEmpty())
        assertTrue(timerRes.outputHi.isNotEmpty())

        // 11. ReadScreenTool
        val readRes = com.example.tools.ReadScreenTool().execute(emptyMap())
        assertFalse(readRes.success)
        assertTrue(readRes.outputEn.isNotEmpty())
        assertTrue(readRes.outputHi.isNotEmpty())

        // 12. TapElementTool
        val tapRes = com.example.tools.TapElementTool().execute(mapOf("target_text" to "Button"))
        assertFalse(tapRes.success)
        assertTrue(tapRes.outputEn.isNotEmpty())
        assertTrue(tapRes.outputHi.isNotEmpty())

        // 13. TypeTextTool
        val typeRes = com.example.tools.TypeTextTool().execute(emptyMap())
        assertFalse(typeRes.success)
        assertTrue(typeRes.outputEn.isNotEmpty())
        assertTrue(typeRes.outputHi.isNotEmpty())

        // 14. ScrollTool
        val scrollRes = com.example.tools.ScrollTool().execute(emptyMap())
        assertFalse(scrollRes.success)
        assertTrue(scrollRes.outputEn.isNotEmpty())
        assertTrue(scrollRes.outputHi.isNotEmpty())

        // 15. WaitForElementTool
        val waitRes = com.example.tools.WaitForElementTool().execute(emptyMap())
        assertFalse(waitRes.success)
        assertTrue(waitRes.outputEn.isNotEmpty())
        assertTrue(waitRes.outputHi.isNotEmpty())

        // 16. SystemNavigationTool
        val navSysRes = com.example.tools.SystemNavigationTool().execute(mapOf("action" to "back"))
        assertFalse(navSysRes.success)
        assertTrue(navSysRes.outputEn.isNotEmpty())
        assertTrue(navSysRes.outputHi.isNotEmpty())

        // 17. NotificationActionTool
        val notifRes = com.example.tools.NotificationActionTool(dummyContext).execute(mapOf("action" to "list"))
        assertTrue(notifRes.outputEn.isNotEmpty())
        assertTrue(notifRes.outputHi.isNotEmpty())

        // 18. SaveMemoryTool
        val saveMemRes = com.example.tools.SaveMemoryTool(memoryRepo).execute(emptyMap())
        assertFalse(saveMemRes.success)
        assertTrue(saveMemRes.outputEn.isNotEmpty())
        assertTrue(saveMemRes.outputHi.isNotEmpty())

        // 19. ForgetMemoryTool
        val forgetMemRes = com.example.tools.ForgetMemoryTool(memoryRepo).execute(emptyMap())
        assertFalse(forgetMemRes.success)
        assertTrue(forgetMemRes.outputEn.isNotEmpty())
        assertTrue(forgetMemRes.outputHi.isNotEmpty())

        // 20. SearchMemoryTool
        val searchMemRes = com.example.tools.SearchMemoryTool(memoryRepo).execute(mapOf("query" to "Coffee"))
        assertTrue(searchMemRes.outputEn.isNotEmpty())
        assertTrue(searchMemRes.outputHi.isNotEmpty())

        // 21. SearchHistoryTool
        val searchHistRes = com.example.tools.SearchHistoryTool(FakeChatRepository()).execute(emptyMap())
        assertFalse(searchHistRes.success)
        assertTrue(searchHistRes.outputEn.isNotEmpty())
        assertTrue(searchHistRes.outputHi.isNotEmpty())
    }

    @Test
    fun toolRegistry_resolvesAdjustVolumeAndSetVolumeAlias() {
        val dummyContext = ContextWrapper(null)
        val registry = ToolRegistry(setOf(com.example.tools.VolumeTool(dummyContext)))
        val adjustVol = registry.getTool("adjust_volume")
        assertNotNull(adjustVol)
        assertEquals("adjust_volume", adjustVol?.name)

        val setVol = registry.getTool("set_volume")
        assertNotNull("set_volume must resolve to VolumeTool alias", setVol)
        assertEquals("adjust_volume", setVol?.name)

        val foundAdjust = registry.findTool("adjust_volume", fullControlOverride = true)
        assertNotNull(foundAdjust)
        val foundSet = registry.findTool("set_volume", fullControlOverride = true)
        assertNotNull(foundSet)
    }

    @Test
    fun geminiClient_testConnection_verifiesUrlAndPayload() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val apiKeyStore = ApiKeyStore(ContextWrapper(null))
            val testApiKey = "AIzaSyTestSecretKey9876"
            apiKeyStore.save(testApiKey)

            val client = GeminiClient(
                okHttpClient = OkHttpClient(),
                moshi = moshi,
                apiKeyStore = apiKeyStore,
                baseUrlProvider = { baseUrl }
            )

            // Success case: returns 200 with candidates
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"candidates": [{"content": {"role": "model", "parts": [{"text": "OK"}]}}]}""")
            )
            val res = client.testConnection(testApiKey)
            assertTrue("testConnection should return success on HTTP 200", res.isSuccess)
            assertEquals("Working", res.getOrNull())

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", req.path)
            assertEquals(testApiKey, req.getHeader("x-goog-api-key"))
            assertTrue(req.getHeader("Content-Type")?.startsWith("application/json") == true)
            assertFalse(req.path.orEmpty().contains(testApiKey))
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"role\":\"user\"") || body.contains("\"role\": \"user\""))
            assertTrue(body.contains("\"text\":\"Say OK\"") || body.contains("\"text\": \"Say OK\""))

            // Failure case: 400 bad request
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error": {"code": 400, "message": "API key not valid", "status": "INVALID_ARGUMENT"}}""")
            )
            val failRes = client.testConnection(testApiKey)
            assertTrue("testConnection should fail on HTTP 400", failRes.isFailure)
            assertTrue(failRes.exceptionOrNull()?.message?.contains("API key not valid") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun geminiClient_fallbackChain_resolvesFrom38FlashAndLive() {
        // Fallback chain: 3.8-live and 3.8-flash resolve cleanly
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("models/gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("gemini-3.8-live"))
        assertEquals("gemini-3.8-flash", GeminiClient.getNextFallbackModel("models/gemini-3.8-live"))

        // Default model selection prioritizes 3.8-flash
        assertEquals("gemini-3.8-flash", GeminiClient.selectDefaultModel(listOf("gemini-3.8-flash", "gemini-3.8-live")))
        assertEquals("gemini-3.8-flash", GeminiClient.selectDefaultModel(listOf("gemini-3.8-flash", "gemini-1.5-pro")))

        // Friendly names
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("gemini-3.8-flash"))
        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("gemini-3.8-live"))
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("models/gemini-3.8-flash"))
        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("models/gemini-3.8-live"))
    }

    @Test
    fun geminiClient_sanitizeOpenApiSchema_cleansSchemaCorrectly() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val apiKeyStore = ApiKeyStore(ContextWrapper(null))
        val client = GeminiClient(
            okHttpClient = OkHttpClient(),
            moshi = moshi,
            apiKeyStore = apiKeyStore
        )

        // 1. Null schema returns default OBJECT
        val nullResult = client.sanitizeOpenApiSchema(null)
        assertEquals("OBJECT", nullResult["type"])
        assertEquals(emptyMap<String, Any?>(), nullResult["properties"])

        // 2. Normal schema with extra disallowed keys stripped
        val rawSchema = mapOf(
            "type" to "object",
            "title" to "DisallowedTitle",
            "\$schema" to "http://json-schema.org/draft-07/schema#",
            "additionalProperties" to false,
            "description" to "A sample tool input",
            "required" to listOf("query", "count"),
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "Search query text",
                    "invalidField" to 123
                ),
                "count" to mapOf(
                    "type" to "integer",
                    "description" to "Item count"
                ),
                "mode" to mapOf(
                    "type" to "string",
                    "enum" to listOf("fast", "precise")
                )
            )
        )

        val sanitized = client.sanitizeOpenApiSchema(rawSchema)
        assertEquals("OBJECT", sanitized["type"])
        assertEquals("A sample tool input", sanitized["description"])
        assertEquals(listOf("query", "count"), sanitized["required"])
        assertNull(sanitized["title"])
        assertNull(sanitized["\$schema"])
        assertNull(sanitized["additionalProperties"])

        @Suppress("UNCHECKED_CAST")
        val props = sanitized["properties"] as Map<String, Any?>
        assertNotNull(props["query"])
        assertNotNull(props["count"])
        assertNotNull(props["mode"])

        @Suppress("UNCHECKED_CAST")
        val queryProp = props["query"] as Map<String, Any?>
        assertEquals("STRING", queryProp["type"])
        assertEquals("Search query text", queryProp["description"])
        assertNull(queryProp["invalidField"])

        @Suppress("UNCHECKED_CAST")
        val countProp = props["count"] as Map<String, Any?>
        assertEquals("INTEGER", countProp["type"])

        @Suppress("UNCHECKED_CAST")
        val modeProp = props["mode"] as Map<String, Any?>
        assertEquals("STRING", modeProp["type"])
        assertEquals(listOf("fast", "precise"), modeProp["enum"])
    }

    @Test
    fun geminiClient_parseSuccessResponse_handlesTextToolsAndBlocks() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val apiKeyStore = ApiKeyStore(ContextWrapper(null))
        val client = GeminiClient(
            okHttpClient = OkHttpClient(),
            moshi = moshi,
            apiKeyStore = apiKeyStore
        )

        // 1. Text response
        val textJson = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [{"text": "Hello there! How can I assist you today?"}]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 10,
                "candidatesTokenCount": 9,
                "totalTokenCount": 19
              }
            }
        """.trimIndent()
        val textRes = client.parseSuccessResponse(textJson)
        assertTrue(textRes is GeminiTurnResult.Success)
        val textSuccess = textRes as GeminiTurnResult.Success
        assertEquals("Hello there! How can I assist you today?", textSuccess.assistantText)
        assertTrue(textSuccess.toolCalls.isEmpty())
        assertEquals(10, textSuccess.promptTokens)
        assertEquals(9, textSuccess.candidateTokens)

        // 2. Tool call response with numeric args converted to string cleanly
        val toolJson = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "name": "adjust_volume",
                          "args": { "level_percent": 75, "stream": "music" }
                        }
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        val toolRes = client.parseSuccessResponse(toolJson)
        assertTrue(toolRes is GeminiTurnResult.Success)
        val toolSuccess = toolRes as GeminiTurnResult.Success
        assertEquals(1, toolSuccess.toolCalls.size)
        assertEquals("adjust_volume", toolSuccess.toolCalls.first().name)
        assertEquals("75", toolSuccess.toolCalls.first().args["level_percent"])
        assertEquals("music", toolSuccess.toolCalls.first().args["stream"])

        // 3. Blocked prompt feedback
        val blockJson = """{"promptFeedback": {"blockReason": "SAFETY"}}"""
        val blockRes = client.parseSuccessResponse(blockJson)
        assertTrue(blockRes is GeminiTurnResult.Success)
        assertEquals("I can't help with that.", (blockRes as GeminiTurnResult.Success).assistantText)

        // 4. Finish reason SAFETY
        val safetyJson = """
            {
              "candidates": [
                {
                  "content": {"parts": []},
                  "finishReason": "SAFETY"
                }
              ]
            }
        """.trimIndent()
        val safetyRes = client.parseSuccessResponse(safetyJson)
        assertTrue(safetyRes is GeminiTurnResult.Success)
        assertEquals("I can't help with that.", (safetyRes as GeminiTurnResult.Success).assistantText)

        // 5. Malformed JSON
        val malformedRes = client.parseSuccessResponse("not valid json")
        assertTrue(malformedRes is GeminiTurnResult.ServerError)
    }

    @Test
    fun geminiClient_buildGenerateContentPayload_includesThinkingConfigLow() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val apiKeyStore = ApiKeyStore(ContextWrapper(null))
        val client = GeminiClient(
            okHttpClient = OkHttpClient(),
            moshi = moshi,
            apiKeyStore = apiKeyStore
        )

        // A) Gemini 3.8 Flash includes thinkingConfig with thinkingLevel: "low"
        val payload38 = client.buildGenerateContentPayload(
            modelId = "gemini-3.8-flash",
            systemInstruction = "You are Jarvis.",
            messages = listOf(
                ChatMessage(id = 1, role = MessageRole.USER, content = "Turn on the light"),
                ChatMessage(id = 2, role = MessageRole.ASSISTANT, content = "Turning on", toolName = "toggle_flashlight"),
                ChatMessage(id = 3, role = MessageRole.TOOL, content = "Flashlight enabled", toolName = "toggle_flashlight")
            ),
            tools = listOf(
                ToolDescriptor("toggle_flashlight", "Toggles flashlight", "{}", RiskLevel.LOW.name)
            ),
            allowThinking = true
        )

        @Suppress("UNCHECKED_CAST")
        val genConfig38 = payload38["generationConfig"] as Map<String, Any?>
        assertNotNull(genConfig38["thinkingConfig"])
        @Suppress("UNCHECKED_CAST")
        val thinkingConfig38 = genConfig38["thinkingConfig"] as Map<String, Any?>
        assertEquals("low", thinkingConfig38["thinkingLevel"])

        // System instruction verified
        @Suppress("UNCHECKED_CAST")
        val sysInst = payload38["systemInstruction"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sysParts = sysInst["parts"] as List<Map<String, Any?>>
        assertEquals("You are Jarvis.", sysParts.first()["text"])

        // Tools verified
        @Suppress("UNCHECKED_CAST")
        val toolsList = payload38["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val fnDecls = toolsList.first()["functionDeclarations"] as List<Map<String, Any?>>
        assertEquals("toggle_flashlight", fnDecls.first()["name"])

        // Contents verified
        @Suppress("UNCHECKED_CAST")
        val contents = payload38["contents"] as List<Map<String, Any?>>
        assertEquals(3, contents.size)
        assertEquals("user", contents[0]["role"])
        assertEquals("model", contents[1]["role"])
        assertEquals("user", contents[2]["role"]) // Tool response sent as user turn with functionResponse

        // B) Gemini 3.8 Live (Voice) does NOT include thinkingConfig
        val payloadLive = client.buildGenerateContentPayload(
            modelId = "gemini-3.8-live",
            systemInstruction = "You are Jarvis.",
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hello")),
            tools = emptyList(),
            allowThinking = true
        )
        @Suppress("UNCHECKED_CAST")
        val genConfigLive = payloadLive["generationConfig"] as Map<String, Any?>
        assertNull(genConfigLive["thinkingConfig"])
    }

    @Test
    fun ttsManager_voiceResolutionAndGenderMapping_allSixVoices() {
        // Test all 6 prebuilt voices
        assertEquals("Puck", TtsManager.resolveLiveVoiceName("Puck", "MALE"))
        assertEquals("Charon", TtsManager.resolveLiveVoiceName("Charon", "MALE"))
        assertEquals("Fenrir", TtsManager.resolveLiveVoiceName("Fenrir", "MALE"))
        assertEquals("Aoede", TtsManager.resolveLiveVoiceName("Aoede", "FEMALE"))
        assertEquals("Kore", TtsManager.resolveLiveVoiceName("Kore", "FEMALE"))
        assertEquals("Leda", TtsManager.resolveLiveVoiceName("Leda", "FEMALE"))

        // Case insensitivity
        assertEquals("Puck", TtsManager.resolveLiveVoiceName("puck", "MALE"))
        assertEquals("Charon", TtsManager.resolveLiveVoiceName("CHARON", "MALE"))
        assertEquals("Fenrir", TtsManager.resolveLiveVoiceName("fenrir", "MALE"))
        assertEquals("Aoede", TtsManager.resolveLiveVoiceName("aoede", "FEMALE"))
        assertEquals("Kore", TtsManager.resolveLiveVoiceName("KORE", "FEMALE"))
        assertEquals("Leda", TtsManager.resolveLiveVoiceName("leda", "FEMALE"))

        // Descriptive fallback queries
        assertEquals("Charon", TtsManager.resolveLiveVoiceName("male deep", "MALE"))
        assertEquals("Fenrir", TtsManager.resolveLiveVoiceName("male authoritative", "MALE"))
        assertEquals("Aoede", TtsManager.resolveLiveVoiceName("female warm", "FEMALE"))
        assertEquals("Leda", TtsManager.resolveLiveVoiceName("female clear", "FEMALE"))

        // Default / empty fallback
        assertEquals("Puck", TtsManager.resolveLiveVoiceName("", "MALE"))
        assertEquals("Aoede", TtsManager.resolveLiveVoiceName("", "FEMALE"))
        assertEquals("Puck", TtsManager.resolveLiveVoiceName("default", "MALE"))
        assertEquals("Aoede", TtsManager.resolveLiveVoiceName("default", "FEMALE"))

        // Verify GeminiVoice enum gender separation
        val maleVoices = GeminiVoice.getVoicesForGender("MALE")
        assertEquals(3, maleVoices.size)
        assertEquals(listOf(GeminiVoice.PUCK, GeminiVoice.CHARON, GeminiVoice.FENRIR), maleVoices)

        val femaleVoices = GeminiVoice.getVoicesForGender("FEMALE")
        assertEquals(3, femaleVoices.size)
        assertEquals(listOf(GeminiVoice.AOEDE, GeminiVoice.KORE, GeminiVoice.LEDA), femaleVoices)
    }

    @Test
    fun ttsManager_cleanTextForSpeech_hindiAndEnglish() {
        // Markdown, links, and code removal
        val mdInput = "Here is **bold** text, *italic* word, `inline_code`, and [Google link](https://google.com)."
        val mdCleaned = TtsManager.cleanTextForSpeech(mdInput)
        assertFalse(mdCleaned.contains("**"))
        assertFalse(mdCleaned.contains("*"))
        assertFalse(mdCleaned.contains("`"))
        assertFalse(mdCleaned.contains("https://"))
        assertTrue(mdCleaned.contains("bold"))
        assertTrue(mdCleaned.contains("italic"))
        assertTrue(mdCleaned.contains("inline code") || mdCleaned.contains("inline_code"))
        assertTrue(mdCleaned.contains("Google link"))

        // Code fences and JSON removal
        val codeAndJson = "Start ```kotlin val x = 10``` middle {\"status\": \"ok\", \"count\": 5} end."
        val cleanedCodeJson = TtsManager.cleanTextForSpeech(codeAndJson)
        assertFalse(cleanedCodeJson.contains("```"))
        assertFalse(cleanedCodeJson.contains("val x = 10"))
        assertFalse(cleanedCodeJson.contains("\"status\""))
        assertTrue(cleanedCodeJson.contains("Start"))
        assertTrue(cleanedCodeJson.contains("end."))

        // Emojis removal
        val emojiInput = "Flashlight turned on! 🔦✨ Happy to help 🚀😊"
        val emojiCleaned = TtsManager.cleanTextForSpeech(emojiInput)
        assertFalse(emojiCleaned.contains("🔦"))
        assertFalse(emojiCleaned.contains("✨"))
        assertFalse(emojiCleaned.contains("🚀"))
        assertFalse(emojiCleaned.contains("😊"))
        assertTrue(emojiCleaned.contains("Flashlight turned on!"))

        // Numbers, percentages, currency expansion in English
        assertEquals("Battery is at 85 percent", TtsManager.cleanTextForSpeech("Battery is at 85%"))
        assertEquals("Total cost is 150 dollars", TtsManager.cleanTextForSpeech("Total cost is $150"))
        assertEquals("Timer set for 15 minutes and 30 seconds", TtsManager.cleanTextForSpeech("Timer set for 15 min and 30 sec"))

        // Hindi phrases with currency and numbers
        assertEquals("Mummy ko 500 rupees bhej do", TtsManager.cleanTextForSpeech("Mummy ko ₹500 bhej do"))
        assertEquals("Raste me 10 minutes lagenge", TtsManager.cleanTextForSpeech("Raste me 10 min lagenge"))

        // Concise confirmation (< 2 sentences when confirmed/cancelled)
        val longConfirm = "Action Confirmed. Your timer for 5 minutes is running. Extra detail here. Another sentence here."
        val shortConfirm = TtsManager.cleanTextForSpeech(longConfirm)
        val count = shortConfirm.split(Regex("(?<=[.!?])\\s+")).size
        assertTrue("Long confirmation must be trimmed to <= 2 sentences", count <= 2)
    }

    @Test
    fun localIntentRouter_hindiAndEnglishCommands_allResolveCorrectly() {
        val testCases = listOf(
            // Open App in Hindi (Suffix style)
            "YouTube open karo" to Pair("open_app", mapOf("app_name" to "YouTube")),
            "WhatsApp kholo" to Pair("open_app", mapOf("app_name" to "WhatsApp")),
            "Instagram chalu karo" to Pair("open_app", mapOf("app_name" to "Instagram")),
            "insta kholo" to Pair("open_app", mapOf("app_name" to "Instagram")),
            "wa open karo" to Pair("open_app", mapOf("app_name" to "WhatsApp")),
            "yt kholo" to Pair("open_app", mapOf("app_name" to "YouTube")),

            // Open App in English (Prefix style)
            "open YouTube" to Pair("open_app", mapOf("app_name" to "YouTube")),
            "launch WhatsApp" to Pair("open_app", mapOf("app_name" to "WhatsApp")),
            "start Instagram" to Pair("open_app", mapOf("app_name" to "Instagram")),
            "open yt" to Pair("open_app", mapOf("app_name" to "YouTube")),
            "open wa" to Pair("open_app", mapOf("app_name" to "WhatsApp")),
            "open insta" to Pair("open_app", mapOf("app_name" to "Instagram")),
            "open gpay" to Pair("open_app", mapOf("app_name" to "Google Pay")),
            "open tg" to Pair("open_app", mapOf("app_name" to "Telegram")),

            // Flashlight
            "torch jala do" to Pair("toggle_flashlight", mapOf("enabled" to "true")),
            "torch on" to Pair("toggle_flashlight", mapOf("enabled" to "true")),
            "chalu karo torch" to Pair("toggle_flashlight", mapOf("enabled" to "true")),
            "turn on flashlight" to Pair("toggle_flashlight", mapOf("enabled" to "true")),
            "switch on flashlight" to Pair("toggle_flashlight", mapOf("enabled" to "true")),
            "torch band karo" to Pair("toggle_flashlight", mapOf("enabled" to "false")),
            "torch band kar do" to Pair("toggle_flashlight", mapOf("enabled" to "false")),
            "torch bujha do" to Pair("toggle_flashlight", mapOf("enabled" to "false")),
            "turn off flashlight" to Pair("toggle_flashlight", mapOf("enabled" to "false")),

            // Volume
            "volume up" to Pair("adjust_volume", mapOf("direction" to "up")),
            "awaz tez karo" to Pair("adjust_volume", mapOf("direction" to "up")),
            "volume down" to Pair("adjust_volume", mapOf("direction" to "down")),
            "volume kam karo" to Pair("adjust_volume", mapOf("direction" to "down")),
            "mute" to Pair("adjust_volume", mapOf("direction" to "mute")),
            "set volume to 80 percent" to Pair("adjust_volume", mapOf("stream" to "music", "level_percent" to "80")),

            // YouTube Media
            "play jazz on youtube" to Pair("play_media", mapOf("query" to "jazz")),
            "youtube pe lo-fi chalao" to Pair("play_media", mapOf("query" to "lo-fi")),

            // Web Search
            "google weather in delhi" to Pair("web_search", mapOf("query" to "weather in delhi")),
            "search for latest news" to Pair("web_search", mapOf("query" to "latest news")),

            // Calls & Messages
            "call Rahul" to Pair("call_contact", mapOf("contact_name" to "Rahul")),
            "phone karo Mummy" to Pair("call_contact", mapOf("contact_name" to "Mummy")),
            "message Priya I will be late" to Pair("send_whatsapp_or_telegram", mapOf("contact_name" to "Priya", "message" to "I will be late", "app" to "whatsapp")),

            // Navigation System
            "go back" to Pair("system_navigation", mapOf("action" to "back")),
            "go home" to Pair("system_navigation", mapOf("action" to "home")),
            "recent apps" to Pair("system_navigation", mapOf("action" to "recents")),

            // Quick Settings
            "wifi settings" to Pair("open_app", mapOf("app_name" to "android.settings.WIFI_SETTINGS")),
            "open bluetooth" to Pair("open_app", mapOf("app_name" to "android.settings.BLUETOOTH_SETTINGS")),
            "brightness" to Pair("open_app", mapOf("app_name" to "android.settings.DISPLAY_SETTINGS")),
            "dnd" to Pair("open_app", mapOf("app_name" to "android.settings.ZEN_MODE_SETTINGS")),

            // Timers & Alarms
            "set a timer for 10 minutes" to Pair("set_timer", mapOf("seconds" to "600", "label" to "JARVIS Timer")),
            "set alarm for 7 am" to Pair("set_alarm", mapOf("hour" to "7", "minute" to "0", "label" to "JARVIS Alarm"))
        )

        for ((phrase, expected) in testCases) {
            val result = LocalIntentRouter.route(phrase, null)
            assertNotNull("Phrase failed to route: '$phrase'", result)
            assertTrue(
                "Phrase '$phrase' expected ToolAction but was $result",
                result is LocalRouteResult.ToolAction
            )
            val action = result as LocalRouteResult.ToolAction
            assertEquals("Tool name mismatch for '$phrase'", expected.first, action.toolName)
            for ((k, v) in expected.second) {
                assertEquals(
                    "Arg '$k' mismatch for phrase '$phrase'",
                    v,
                    action.args[k]
                )
            }
        }

        // Direct replies
        val directCases = listOf(
            "battery kitni hai",
            "battery percentage",
            "what time is it",
            "time kya hua"
        )
        for (phrase in directCases) {
            val result = LocalIntentRouter.route(phrase, null)
            assertNotNull("Direct reply phrase failed to route: '$phrase'", result)
            assertTrue("Expected DirectReply for '$phrase'", result is LocalRouteResult.DirectReply)
        }

        // Emergency Stop
        val stopResult = LocalIntentRouter.route("stop", null)
        assertTrue(stopResult is LocalRouteResult.Stop)
        val rukJaoResult = LocalIntentRouter.route("ruk jao", null)
        assertTrue(rukJaoResult is LocalRouteResult.Stop)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun settingsRepository_and_settingsViewModel_updatesAndPersistsDualModelAndVoicePreferences() = runBlocking {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeSettingsRepo = FakeSettingsRepository()

            // 1. Direct SettingsRepository testing
            assertEquals("gemini-3.8-flash", fakeSettingsRepo.flow.value.selectedTextModelId)
            assertEquals("gemini-3.8-live", fakeSettingsRepo.flow.value.selectedVoiceModelId)
            assertEquals("MALE", fakeSettingsRepo.flow.value.selectedVoiceGender)
            assertEquals("Puck", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            fakeSettingsRepo.setSelectedTextModelId("gemini-3.8-flash")
            fakeSettingsRepo.setSelectedVoiceModelId("gemini-3.8-live")
            assertEquals("gemini-3.8-flash", fakeSettingsRepo.flow.value.selectedTextModelId)
            assertEquals("gemini-3.8-live", fakeSettingsRepo.flow.value.selectedVoiceModelId)

            fakeSettingsRepo.setSelectedVoiceGender("FEMALE")
            fakeSettingsRepo.setSelectedLiveVoiceName("Aoede")
            assertEquals("FEMALE", fakeSettingsRepo.flow.value.selectedVoiceGender)
            assertEquals("Aoede", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            // 2. SettingsViewModel testing with FakeSettingsRepository
            val dummyContext = ContextWrapper(null)
            val apiKeyStore = ApiKeyStore(dummyContext)
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val geminiClient = GeminiClient(OkHttpClient(), moshi, apiKeyStore)
            val stt = FakeSpeechRecognizer()
            val tts = FakeTtsEngine()
            val voiceManager = VoiceSessionManager(stt, tts, null, FakeWakeWordEngine())
            val killSwitch = KillSwitch()
            val orchestrator = createTestOrchestrator(
                backendRepository = object : AgentBackendRepository {
                    override suspend fun runAgentStep(
                        messages: List<ChatMessage>,
                        tools: List<ToolDescriptor>,
                        memories: List<MemoryFact>,
                        screenContext: ScreenContextSnapshot?,
                        language: AppLanguage,
                        summaries: List<ConversationSummaryItem>,
                        onTextChunk: ((String) -> Unit)?,
                        isVoiceInput: Boolean
                    ): AgentStepDecision = AgentStepDecision(assistantText = "OK", toolCalls = emptyList())
                },
                tools = emptySet(),
                killSwitch = killSwitch
            )

            val viewModel = SettingsViewModel(
                appContext = dummyContext,
                apiKeyStore = apiKeyStore,
                geminiClient = geminiClient,
                settingsRepository = fakeSettingsRepo,
                voiceSessionManager = voiceManager,
                speechRecognizer = stt,
                ttsEngine = tts,
                agentOrchestrator = orchestrator,
                killSwitch = killSwitch
            )

            // Verify initial state
            assertEquals("gemini-3.8-flash", viewModel.settings.value.selectedTextModelId)
            assertEquals("gemini-3.8-live", viewModel.settings.value.selectedVoiceModelId)

            // Change text model via ViewModel
            viewModel.setSelectedTextModelId("gemini-3.8-flash")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("gemini-3.8-flash", fakeSettingsRepo.flow.value.selectedTextModelId)

            // Change voice model via ViewModel
            viewModel.setSelectedVoiceModelId("gemini-3.8-live")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("gemini-3.8-live", fakeSettingsRepo.flow.value.selectedVoiceModelId)

            // Change gender to FEMALE: live voice should automatically switch to Aoede
            viewModel.setSelectedVoiceGender("FEMALE")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("FEMALE", fakeSettingsRepo.flow.value.selectedVoiceGender)
            assertEquals("Aoede", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            // Select specific timbre: Kore
            viewModel.setSelectedLiveVoiceName("Kore")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Kore", fakeSettingsRepo.flow.value.selectedLiveVoiceName)
            assertEquals("Kore", fakeSettingsRepo.flow.value.selectedVoiceName)

            // Select specific timbre: Leda
            viewModel.setSelectedLiveVoiceName("Leda")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Leda", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            // Switch back to MALE: live voice should automatically switch to Puck
            viewModel.setSelectedVoiceGender("MALE")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("MALE", fakeSettingsRepo.flow.value.selectedVoiceGender)
            assertEquals("Puck", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            // Select male timbre: Charon
            viewModel.setSelectedLiveVoiceName("Charon")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Charon", fakeSettingsRepo.flow.value.selectedLiveVoiceName)

            // Select male timbre: Fenrir
            viewModel.setSelectedLiveVoiceName("Fenrir")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Fenrir", fakeSettingsRepo.flow.value.selectedLiveVoiceName)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

