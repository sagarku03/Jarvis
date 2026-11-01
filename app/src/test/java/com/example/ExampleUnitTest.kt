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
                summaries: List<ConversationSummaryItem>
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
}
