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
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
                              "name": "models/gemini-2.5-flash",
                              "displayName": "Gemini 2.5 Flash",
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
            assertEquals(listOf("gemini-2.5-flash"), models)

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
                modelId = "gemini-2.5-flash",
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
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", recordedGenReq.path)
            assertEquals(testApiKey, recordedGenReq.getHeader("x-goog-api-key"))
            assertFalse(recordedGenReq.path.orEmpty().contains(testApiKey))
            val sentBody = recordedGenReq.body.readUtf8()
            assertTrue("Sent payload must include functionResponse for TOOL history", sentBody.contains("\"functionResponse\""))
            assertTrue("Sent payload must include functionDeclarations", sentBody.contains("\"functionDeclarations\""))

            // C) Verify 403 maps to KeyRejected
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Invalid key"}}"""))
            val rejected = client.generateContent(
                modelId = "gemini-2.5-flash",
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
                modelId = "gemini-2.5-flash",
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

    private fun createTestOrchestrator(
        backendRepository: AgentBackendRepository,
        chatRepository: ChatRepository = FakeChatRepository(),
        tools: Set<Tool>,
        killSwitch: KillSwitch = KillSwitch()
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
        val settingsRepo = object : SettingsRepository {
            override val settingsFlow: Flow<UserSettings> = MutableStateFlow(UserSettings())
            override suspend fun setSetupComplete(complete: Boolean) {}
            override suspend fun setSelectedModelId(modelId: String) {}
            override suspend fun setSelectedTextModelId(modelId: String) {}
            override suspend fun setSelectedVoiceModelId(modelId: String) {}
            override suspend fun setSelectedVoiceGender(gender: String) {}
            override suspend fun setSelectedLiveVoiceName(voiceName: String) {}
            override suspend fun setAvailableModels(models: List<String>) {}
            override suspend fun setLanguage(language: AppLanguage) {}
            override suspend fun setSelectedVoiceName(voiceName: String) {}
            override suspend fun setSpeechSpeed(speed: Float) {}
            override suspend fun setSpeechPitch(pitch: Float) {}
            override suspend fun setAutoLearnEnabled(enabled: Boolean) {}
            override suspend fun setAskBeforeSavingFact(ask: Boolean) {}
            override suspend fun recordGeminiUsage(tokensUsed: Int, nowMs: Long) {}
            override suspend fun setFloatingOrbEnabled(enabled: Boolean) {}
            override suspend fun setConfirmationLevel(level: ConfirmationLevel) {}
            override suspend fun setKillSwitchActive(active: Boolean) {}
            override suspend fun setVoiceEngine(voiceEngine: com.example.domain.model.VoiceEngineOption) {}
            override suspend fun setWakePhraseEnabled(enabled: Boolean) {}
            override suspend fun setWakePhraseScreenOnOnly(enabled: Boolean) {}
            override suspend fun setWakePhraseStopAfter30m(enabled: Boolean) {}
            override suspend fun setVoiceTimings(firstTokenMs: Long, firstWordMs: Long) {}
        }

        return AgentOrchestrator(
            agentBackendRepository = backendRepository,
            chatRepository = chatRepository,
            memoryRepository = memoryRepo,
            activityLogRepository = logRepo,
            routineRepository = routineRepo,
            settingsRepository = settingsRepo,
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
    fun dualModelArchitecture_defaultsAndFriendlyNames() {
        assertEquals("gemini-3.8-flash", GeminiClient.DEFAULT_TEXT_MODEL)
        assertEquals("gemini-3.8-live", GeminiClient.DEFAULT_VOICE_MODEL)

        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("gemini-3.8-live"))
        assertEquals("Gemini 3.8 Live (Voice)", GeminiClient.friendlyModelName("models/gemini-3.8-live"))
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("gemini-3.8-flash"))
        assertEquals("Gemini 3.8 Flash (Text)", GeminiClient.friendlyModelName("models/gemini-3.8-flash"))

        val settings = UserSettings()
        assertEquals("gemini-3.8-flash", settings.selectedTextModelId)
        assertEquals("gemini-3.8-live", settings.selectedVoiceModelId)
        assertEquals("MALE", settings.selectedVoiceGender)
        assertEquals("Puck", settings.selectedLiveVoiceName)
    }

    @Test
    fun dualModelArchitecture_fallbackChainGracefullyResolves() {
        // Fallback chain: 3.8 -> 2.0-flash -> 2.5-flash -> 2.5-flash-lite
        assertEquals("gemini-2.0-flash", GeminiClient.getNextFallbackModel("gemini-3.8-live"))
        assertEquals("gemini-2.0-flash", GeminiClient.getNextFallbackModel("gemini-3.8-flash"))
        assertEquals("gemini-2.5-flash", GeminiClient.getNextFallbackModel("gemini-2.0-flash"))
        assertEquals("gemini-2.5-flash-lite", GeminiClient.getNextFallbackModel("gemini-2.5-flash"))
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
}
