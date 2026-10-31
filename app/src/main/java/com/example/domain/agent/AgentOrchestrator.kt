package com.example.domain.agent

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AgentMessage
import com.example.domain.model.AppLanguage
import com.example.domain.model.AppResult
import com.example.domain.model.MessageRole
import com.example.domain.model.OrbState
import com.example.domain.model.PendingMemoryCandidate
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolExecutionResult
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyDecision
import com.example.domain.safety.SafetyGate
import com.example.domain.tool.ToolRegistry
import com.example.domain.voice.TtsEngineWrapper
import com.example.services.JarvisAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

@Singleton
class AgentOrchestrator @Inject constructor(
    private val backendRepository: AgentBackendRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val routineRepository: RoutineRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val safetyGate: SafetyGate,
    private val ttsEngine: TtsEngineWrapper,
    private val killSwitch: KillSwitch,
    private val contactsResolver: com.example.domain.contact.ContactsResolver? = null
) {
    companion object {
        const val MAX_AGENT_STEPS = 15
        const val AGENT_TIMEOUT_MS = 60_000L
        const val MAX_TOOL_RETRIES = 2
        const val STUCK_SCREEN_THRESHOLD = 3
    }

    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeRunJob: Job? = null

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _lastResponseText = MutableStateFlow(
        "नमस्ते! मैं JARVIS हूँ। आप हिन्दी, हिंग्लिश या English में कमांड दे सकते हैं। / Ready for your command."
    )
    val lastResponseText: StateFlow<String> = _lastResponseText.asStateFlow()

    private val _currentStepInfo = MutableStateFlow<String?>(null)
    val currentStepInfo: StateFlow<String?> = _currentStepInfo.asStateFlow()

    init {
        killSwitch.registerCancelCallback {
            activeRunJob?.cancel(CancellationException("KillSwitch triggered"))
            ttsEngine.stopSpeaking()
            _orbState.value = OrbState.IDLE
            _currentStepInfo.value = "HALTED BY KILL SWITCH"
        }
    }

    fun setListeningState(listening: Boolean) {
        if (listening) {
            if (ttsEngine.isSpeaking.value || _orbState.value == OrbState.SPEAKING) {
                ttsEngine.stopSpeaking()
            }
            _orbState.value = OrbState.LISTENING
        } else if (_orbState.value == OrbState.LISTENING) {
            _orbState.value = OrbState.IDLE
        }
    }

    fun bargeIn() {
        if (ttsEngine.isSpeaking.value || _orbState.value == OrbState.SPEAKING) {
            ttsEngine.stopSpeaking()
            _orbState.value = OrbState.IDLE
        }
    }

    fun processUserUtterance(
        rawUtterance: String,
        screenContextOverride: ScreenContextSnapshot? = null
    ) {
        val utterance = rawUtterance.trim()
        if (utterance.isEmpty()) return

        val turnStartTimestampMs = System.currentTimeMillis()
        killSwitch.resetAfterKill()
        ttsEngine.stopSpeaking()
        activeRunJob?.cancel()

        activeRunJob = orchestratorScope.launch {
            val settings = settingsRepository.getSnapshot()
            val language = settings.language

            try {
                _orbState.value = OrbState.THINKING
                _currentStepInfo.value = "Understanding command & retrieving Top-K memories..."

                val userMsg = AgentMessage(
                    role = MessageRole.USER,
                    content = utterance
                )
                chatRepository.saveMessage(userMsg)

                // Stage 5: Queue candidate memory for user approval instead of silently writing
                maybeQueueMemoryCandidate(utterance)
                // Stage 6: Check if user asked to create a voice routine
                maybeCreateVoiceRoutine(utterance)

                withTimeout(AGENT_TIMEOUT_MS) {
                    var stepCount = 0
                    var loopFinished = false
                    var screenToolInvokedInTurn = shouldCaptureScreenOnDemand(utterance)
                    val recentScreenHashes = mutableListOf<String>()

                    while (stepCount < MAX_AGENT_STEPS && !loopFinished) {
                        currentCoroutineContext().ensureActive()
                        if (killSwitch.isHalted.value) {
                            throw CancellationException("KillSwitch active")
                        }

                        stepCount++
                        _currentStepInfo.value = "Agent Step $stepCount / $MAX_AGENT_STEPS"

                        val history = chatRepository.getRecentMessages(limit = 16)
                        val tools = toolRegistry.getToolDescriptors(settings.fullControlModeEnabled)
                        // Stage 5: Inject only top-K (k=5) relevant memories into agent context
                        val topKMemories = memoryRepository.getTopKRelevantMemories(utterance, k = 5)

                        val liveScreenContext = if (screenContextOverride != null || screenToolInvokedInTurn || settings.simulateSecureScreen) {
                            screenContextOverride
                                ?: resolveCurrentScreenContext(
                                    simulateSecure = settings.simulateSecureScreen,
                                    fullControlEnabled = settings.fullControlModeEnabled
                                )
                        } else {
                            null
                        }

                        // Phase 3 Privacy: Never send secure/banking screen content to backend
                        val outboundScreenContext = if (liveScreenContext?.isSecureScreen == true) {
                            null
                        } else {
                            liveScreenContext
                        }

                        when (val stepResult = backendRepository.executeAgentStep(
                            messages = history,
                            tools = tools,
                            screenContext = outboundScreenContext,
                            language = language,
                            topKMemories = topKMemories
                        )) {
                            is AppResult.Loading -> Unit
                            is AppResult.Error -> {
                                val errMsg = stepResult.localized(language)
                                speakAndRecordAssistantReply(
                                    reply = errMsg,
                                    language = language,
                                    voiceSpeed = settings.voiceSpeed,
                                    voicePitch = settings.voicePitch,
                                    voiceProfile = settings.voiceProfile,
                                    turnStartTimestampMs = turnStartTimestampMs
                                )
                                loopFinished = true
                            }
                            is AppResult.Success -> {
                                val output = stepResult.data

                                // Stage 5: Queue any backend-extracted memory candidates for user approval
                                output.memoryCandidates.forEach { candidate ->
                                    memoryRepository.queueMemoryCandidate(candidate)
                                }

                                if (output.toolCalls.isEmpty()) {
                                    val finalReply = output.assistantText ?: defaultFallbackReply(language)
                                    speakAndRecordAssistantReply(
                                        reply = finalReply,
                                        language = language,
                                        voiceSpeed = settings.voiceSpeed,
                                        voicePitch = settings.voicePitch,
                                        voiceProfile = settings.voiceProfile,
                                        turnStartTimestampMs = turnStartTimestampMs
                                    )
                                    loopFinished = true
                                } else {
                                    if (!output.assistantText.isNullOrBlank()) {
                                        _lastResponseText.value = output.assistantText
                                    }

                                    for (toolCall in output.toolCalls) {
                                        currentCoroutineContext().ensureActive()
                                        _currentStepInfo.value = "Tool: ${toolCall.name}"

                                        val tool = toolRegistry.findTool(
                                            name = toolCall.name,
                                            fullControlOverride = settings.fullControlModeEnabled
                                        )

                                        if (tool == null) {
                                            val unavailableMsg = if (language == AppLanguage.HI_IN) {
                                                "टूल '${toolCall.name}' उपलब्ध नहीं है या STORE_SAFE_MODE द्वारा प्रतिबंधित है।"
                                            } else {
                                                "Tool '${toolCall.name}' is unavailable or restricted by STORE_SAFE_MODE."
                                            }
                                            activityLogRepository.logToolAction(
                                                toolName = toolCall.name,
                                                args = toolCall.args,
                                                riskLevel = RiskLevel.LOW,
                                                status = "BLOCKED",
                                                resultSummary = unavailableMsg
                                            )
                                            chatRepository.saveMessage(
                                                AgentMessage(
                                                    role = MessageRole.TOOL,
                                                    content = unavailableMsg,
                                                    toolCallId = toolCall.id,
                                                    toolName = toolCall.name
                                                )
                                            )
                                            continue
                                        }

                                        val contactTools = setOf("call_contact", "send_sms", "send_whatsapp_or_telegram")
                                        var effectiveArgs = toolCall.args
                                        if (tool.name in contactTools && contactsResolver != null) {
                                            val rawQuery = toolCall.args["contact"] ?: toolCall.args["recipient"] ?: ""
                                            when (val res = contactsResolver.resolve(rawQuery)) {
                                                is com.example.domain.contact.ContactResolutionResult.NotFound -> {
                                                    val msg = if (language == AppLanguage.HI_IN) res.messageHi else res.messageEn
                                                    activityLogRepository.logToolAction(
                                                        toolName = tool.name,
                                                        args = toolCall.args,
                                                        riskLevel = tool.riskLevel,
                                                        status = "CONTACT_NOT_FOUND",
                                                        resultSummary = msg
                                                    )
                                                    chatRepository.saveMessage(
                                                        AgentMessage(
                                                            role = MessageRole.TOOL,
                                                            content = msg,
                                                            toolCallId = toolCall.id,
                                                            toolName = tool.name
                                                        )
                                                    )
                                                    continue
                                                }
                                                is com.example.domain.contact.ContactResolutionResult.Ambiguous -> {
                                                    val msg = if (language == AppLanguage.HI_IN) res.messageHi() else res.messageEn()
                                                    activityLogRepository.logToolAction(
                                                        toolName = tool.name,
                                                        args = toolCall.args,
                                                        riskLevel = tool.riskLevel,
                                                        status = "CONTACT_AMBIGUOUS",
                                                        resultSummary = msg
                                                    )
                                                    chatRepository.saveMessage(
                                                        AgentMessage(
                                                            role = MessageRole.TOOL,
                                                            content = msg,
                                                            toolCallId = toolCall.id,
                                                            toolName = tool.name
                                                        )
                                                    )
                                                    continue
                                                }
                                                is com.example.domain.contact.ContactResolutionResult.Resolved -> {
                                                    val resolvedLabel = "${res.contact.displayName} (${res.contact.maskedNumber})"
                                                    effectiveArgs = toolCall.args.toMutableMap().apply {
                                                        put("resolved_contact", resolvedLabel)
                                                        put("masked_number", res.contact.maskedNumber)
                                                    }
                                                }
                                            }
                                        }

                                        val effectiveScreenSnap = if (tool.requiresFullControlMode) {
                                            screenToolInvokedInTurn = true
                                            liveScreenContext ?: resolveCurrentScreenContext(
                                                simulateSecure = settings.simulateSecureScreen,
                                                fullControlEnabled = settings.fullControlModeEnabled
                                            )
                                        } else {
                                            liveScreenContext
                                        }

                                        val isSecure = effectiveScreenSnap?.isSecureScreen == true
                                        val activePkg = effectiveScreenSnap?.packageName
                                        val decision = safetyGate.evaluateBeforeExecution(
                                            tool = tool,
                                            args = effectiveArgs,
                                            policyLevel = settings.safetyPolicyLevel,
                                            isSecureScreen = isSecure,
                                            activePackageName = activePkg,
                                            language = language
                                        )

                                        when (decision) {
                                            is SafetyDecision.Denied -> {
                                                val deniedText = if (language == AppLanguage.HI_IN) {
                                                    decision.reasonHi
                                                } else {
                                                    decision.reasonEn
                                                }
                                                activityLogRepository.logToolAction(
                                                    toolName = tool.name,
                                                    args = toolCall.args,
                                                    riskLevel = tool.riskLevel,
                                                    status = "DENIED_BY_SAFETY_GATE",
                                                    resultSummary = deniedText
                                                )
                                                chatRepository.saveMessage(
                                                    AgentMessage(
                                                        role = MessageRole.TOOL,
                                                        content = deniedText,
                                                        toolCallId = toolCall.id,
                                                        toolName = tool.name
                                                    )
                                                )
                                            }
                                            is SafetyDecision.Approved -> {
                                                _orbState.value = OrbState.THINKING

                                                // Stage 4: Execute with retry on failure (max 2 retries)
                                                var attempt = 0
                                                var execResult: ToolExecutionResult
                                                do {
                                                    if (attempt > 0) {
                                                        _currentStepInfo.value = "Retrying ${tool.name} ($attempt/$MAX_TOOL_RETRIES)..."
                                                        delay(250)
                                                    }
                                                    execResult = runCatching {
                                                        tool.execute(toolCall.args)
                                                    }.getOrElse { e ->
                                                        ToolExecutionResult(
                                                            toolName = tool.name,
                                                            success = false,
                                                            outputEn = "Tool execution failed: ${e.localizedMessage ?: "unexpected error"}",
                                                            outputHi = "टूल चलाने में त्रुटि: ${e.localizedMessage ?: "अज्ञात त्रुटि"}"
                                                        )
                                                    }
                                                    attempt++
                                                } while (!execResult.success && attempt <= MAX_TOOL_RETRIES)

                                                val localizedOut = execResult.localizedOutput(language)
                                                activityLogRepository.logToolAction(
                                                    toolName = tool.name,
                                                    args = toolCall.args,
                                                    riskLevel = tool.riskLevel,
                                                    status = if (execResult.success) "SUCCESS" else "FAILED_AFTER_RETRIES",
                                                    resultSummary = localizedOut
                                                )
                                                chatRepository.saveMessage(
                                                    AgentMessage(
                                                        role = MessageRole.TOOL,
                                                        content = localizedOut,
                                                        toolCallId = toolCall.id,
                                                        toolName = tool.name
                                                    )
                                                )

                                                // Stage 4: Stuck-screen detection (same screen hash 3 times)
                                                if (tool.requiresFullControlMode) {
                                                    val postSnap = resolveCurrentScreenContext(
                                                        simulateSecure = settings.simulateSecureScreen,
                                                        fullControlEnabled = settings.fullControlModeEnabled
                                                    )
                                                    val hash = postSnap?.screenHash ?: "unknown"
                                                    recentScreenHashes.add(hash)
                                                    if (isStuckOnSameScreen(recentScreenHashes)) {
                                                        val stuckMsg = if (language == AppLanguage.HI_IN) {
                                                            "स्क्रीन लगातार 3 बार से नहीं बदली है। लगता है मैं अटक गया हूँ—कृपया स्क्रीन पर मेरी मदद करें।"
                                                        } else {
                                                            "The screen has not changed for 3 consecutive steps. I appear to be stuck—please assist me on screen."
                                                        }
                                                        speakAndRecordAssistantReply(
                                                            reply = stuckMsg,
                                                            language = language,
                                                            voiceSpeed = settings.voiceSpeed,
                                                            voicePitch = settings.voicePitch,
                                                            voiceProfile = settings.voiceProfile,
                                                            turnStartTimestampMs = turnStartTimestampMs
                                                        )
                                                        loopFinished = true
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!loopFinished && stepCount >= MAX_AGENT_STEPS) {
                        val maxStepReply = if (language == AppLanguage.HI_IN) {
                            "अधिकतम 15 चरण पूरे हो गए। सुरक्षा के लिए एजेंट लूप रोक दिया गया है।"
                        } else {
                            "Reached the maximum limit of 15 tool steps. Stopping agent loop for safety."
                        }
                        speakAndRecordAssistantReply(
                            reply = maxStepReply,
                            language = language,
                            voiceSpeed = settings.voiceSpeed,
                            voicePitch = settings.voicePitch,
                            voiceProfile = settings.voiceProfile,
                            turnStartTimestampMs = turnStartTimestampMs
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                val timeoutReply = if (language == AppLanguage.HI_IN) {
                    "60 सेकंड का समय समाप्त हो गया। कृपया अपना कमांड फिर से प्रयास करें।"
                } else {
                    "Agent loop timed out after 60 seconds. Please try your command again."
                }
                speakAndRecordAssistantReply(
                    reply = timeoutReply,
                    language = language,
                    voiceSpeed = settings.voiceSpeed,
                    voicePitch = settings.voicePitch,
                    voiceProfile = settings.voiceProfile,
                    turnStartTimestampMs = turnStartTimestampMs
                )
            } catch (_: CancellationException) {
                _orbState.value = OrbState.IDLE
                _currentStepInfo.value = "Cancelled"
            } catch (e: Exception) {
                val friendlyError = if (language == AppLanguage.HI_IN) {
                    "माफ़ कीजिए, एक अप्रत्याशित समस्या आई: ${e.localizedMessage ?: "अज्ञात त्रुटि"}"
                } else {
                    "Sorry, I ran into an unexpected issue: ${e.localizedMessage ?: "unknown error"}"
                }
                speakAndRecordAssistantReply(
                    reply = friendlyError,
                    language = language,
                    voiceSpeed = settings.voiceSpeed,
                    voicePitch = settings.voicePitch,
                    voiceProfile = settings.voiceProfile,
                    turnStartTimestampMs = turnStartTimestampMs
                )
            }
        }
    }

    fun isStuckOnSameScreen(hashes: List<String>): Boolean {
        if (hashes.size < STUCK_SCREEN_THRESHOLD) return false
        val tail = hashes.takeLast(STUCK_SCREEN_THRESHOLD)
        return tail.distinct().size == 1
    }

    fun shouldCaptureScreenOnDemand(utterance: String): Boolean {
        val lower = utterance.lowercase()
        val keywords = listOf(
            "screen", "on my screen", "read screen", "what is on", "tap ", "click ", "scroll",
            "स्क्रीन", "स्क्रीन पढ़ो", "स्क्रीन पर", "टैप", "स्क्रॉल"
        )
        return keywords.any { lower.contains(it) }
    }

    private fun resolveCurrentScreenContext(
        simulateSecure: Boolean,
        fullControlEnabled: Boolean
    ): ScreenContextSnapshot? {
        if (simulateSecure) {
            return ScreenContextSnapshot(
                packageName = "com.bank.upi.secure",
                activityName = "PinEntryActivity",
                isSecureScreen = true,
                rawVisibleTextNodes = emptyList(),
                structuredNodes = emptyList(),
                compactJson = "",
                screenHash = "com.bank.upi.secure_SECURE_BLOCKED"
            )
        }
        if (!fullControlEnabled) return null
        return JarvisAccessibilityService.captureCurrentScreenSnapshot()
    }

    private suspend fun speakAndRecordAssistantReply(
        reply: String,
        language: AppLanguage,
        voiceSpeed: Float,
        voicePitch: Float,
        voiceProfile: com.example.domain.model.VoiceProfile,
        turnStartTimestampMs: Long
    ) {
        _lastResponseText.value = reply
        _currentStepInfo.value = null
        chatRepository.saveMessage(
            AgentMessage(
                role = MessageRole.ASSISTANT,
                content = reply
            )
        )
        _orbState.value = OrbState.SPEAKING
        ttsEngine.speak(
            text = reply,
            language = language,
            speechRate = voiceSpeed,
            pitch = voicePitch,
            voiceProfile = voiceProfile,
            turnStartTimestampMs = turnStartTimestampMs,
            onDone = {
                if (_orbState.value == OrbState.SPEAKING) {
                    _orbState.value = OrbState.IDLE
                }
            }
        )
    }

    private fun maybeQueueMemoryCandidate(utterance: String) {
        val lower = utterance.lowercase()
        when {
            lower.startsWith("remember that ") || lower.startsWith("remember ") -> {
                val fact = utterance.substringAfter("remember ", "").removePrefix("that ").trim()
                if (fact.isNotBlank()) {
                    memoryRepository.queueMemoryCandidate(
                        PendingMemoryCandidate(
                            id = UUID.randomUUID().toString(),
                            category = "Extracted Preference",
                            keyLabel = fact.take(30),
                            contentValue = fact,
                            sourceUtterance = utterance
                        )
                    )
                }
            }
            utterance.contains("याद रखो") || utterance.contains("याद रखना") -> {
                val fact = utterance
                    .replace("याद रखो", "")
                    .replace("याद रखना", "")
                    .replace("कि", "")
                    .trim()
                if (fact.isNotBlank()) {
                    memoryRepository.queueMemoryCandidate(
                        PendingMemoryCandidate(
                            id = UUID.randomUUID().toString(),
                            category = "व्यक्तिगत प्राथमिकता",
                            keyLabel = fact.take(30),
                            contentValue = fact,
                            sourceUtterance = utterance
                        )
                    )
                }
            }
        }
    }

    private suspend fun maybeCreateVoiceRoutine(utterance: String) {
        val lower = utterance.lowercase()
        if (lower.startsWith("create routine ") || lower.contains("रूटीन बनाओ")) {
            val title = utterance
                .replace(Regex("(?i)create routine"), "")
                .replace("रूटीन बनाओ", "")
                .trim()
                .ifEmpty { "Custom Voice Routine" }
            routineRepository.upsertRoutine(
                title = title.take(36),
                triggerType = RoutineTriggerType.TIME_DAILY,
                triggerValue = "08:00",
                actionsPrompt = "Weather summary, calendar agenda, and top Hindi/English news",
                enabled = true
            )
        }
    }

    private fun defaultFallbackReply(language: AppLanguage): String = when (language) {
        AppLanguage.HI_IN -> "कार्य पूरा हो गया है। क्या मैं कुछ और मदद कर सकता हूँ?"
        AppLanguage.EN_IN, AppLanguage.HINGLISH -> "Done! Let me know if you need anything else."
    }
}
