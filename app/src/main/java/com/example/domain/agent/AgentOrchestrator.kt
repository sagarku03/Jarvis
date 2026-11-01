package com.example.domain.agent

import com.example.BuildConfig
import com.example.data.local.DiagnosticsLogger
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.ToolExecutionResult
import com.example.domain.model.UserFacingError
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGate
import com.example.domain.tool.ToolRegistry
import com.example.services.JarvisAccessibilityService
import com.example.tools.MemorySafetyFilter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-step Agent Orchestrator loop:
 * User speech/text -> Routine check -> Context assembly -> Gemini :generateContent ->
 * Tool Call(s) -> SafetyGate -> Tool Execution -> (Read Screen if follow-up required) ->
 * Feed Tool Result back -> Repeat (max 6 steps, 60s active execution budget excluding human confirmation wait) -> Final Reply.
 */
@Singleton
class AgentOrchestrator @Inject constructor(
    private val agentBackendRepository: AgentBackendRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val routineRepository: RoutineRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val safetyGate: SafetyGate,
    private val killSwitch: KillSwitch,
    private val memoryLearner: MemoryLearner? = null,
    private val voiceSessionManager: com.example.services.voice.VoiceSessionManager? = null
) {
    companion object {
        const val MAX_STEPS_PER_TURN = 6
        const val MAX_ACTIVE_TURN_DURATION_MS = 60_000L
        const val DEFAULT_GREETING = "Hi, I'm Jarvis. How can I help?"

        private val NON_IDEMPOTENT_TOOLS = setOf(
            "call_contact",
            "send_sms",
            "send_whatsapp_or_telegram",
            "tap_element",
            "type_text",
            "forget_memory"
        )

        private val LOW_RISK_ONE_SHOT_TOOLS = setOf(
            "open_app",
            "toggle_flashlight",
            "adjust_volume",
            "set_alarm",
            "set_timer",
            "play_media"
        )

        fun isNonIdempotentTool(toolName: String): Boolean =
            toolName in NON_IDEMPOTENT_TOOLS
    }

    private val backgroundLearnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().build()
    private val argsAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    private val _orbState = MutableStateFlow(AssistantOrbState.IDLE)
    val orbState: StateFlow<AssistantOrbState> = _orbState.asStateFlow()

    private val _orbCaption = MutableStateFlow<String?>(null)
    val orbCaption: StateFlow<String?> = _orbCaption.asStateFlow()

    private val _lastResponseText = MutableStateFlow(DEFAULT_GREETING)
    val lastResponseText: StateFlow<String> = _lastResponseText.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _lastAgentStepLatencyMs = MutableStateFlow(0L)
    val lastAgentStepLatencyMs: StateFlow<Long> = _lastAgentStepLatencyMs.asStateFlow()

    private val _keyRejectedBanner = MutableStateFlow(false)
    val keyRejectedBanner: StateFlow<Boolean> = _keyRejectedBanner.asStateFlow()

    private val _userFacingError = MutableStateFlow<UserFacingError?>(null)
    val userFacingError: StateFlow<UserFacingError?> = _userFacingError.asStateFlow()

    private var activeJob: Job? = null

    fun clearKeyRejectedBanner() {
        _keyRejectedBanner.value = false
        _userFacingError.value = null
    }

    fun clearUserFacingError() {
        _userFacingError.value = null
    }

    fun setOrbState(state: AssistantOrbState) {
        if (killSwitch.isKilled.value) {
            _orbState.value = AssistantOrbState.PAUSED_KILLSWITCH
        } else {
            _orbState.value = state
        }
    }

    fun cancelActiveTurn() {
        activeJob?.cancel()
        activeJob = null
        safetyGate.cancelPendingConfirmation()
        _orbCaption.value = null
        if (!killSwitch.isKilled.value) {
            _orbState.value = AssistantOrbState.IDLE
        }
    }

    suspend fun handleUserCommand(
        userText: String,
        screenContext: ScreenContextSnapshot? = null
    ): String = coroutineScope {
        val settings = settingsRepository.settingsFlow.first()

        if (killSwitch.isKilled.value || settings.killSwitchActive) {
            _orbState.value = AssistantOrbState.PAUSED_KILLSWITCH
            val msg = "Kill Switch is active. Tap 'Resume Assistant' before running commands."
            _lastResponseText.value = msg
            return@coroutineScope msg
        }

        val matchedRoutine = try {
            routineRepository.findMatchingVoiceRoutine(userText)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        if (matchedRoutine != null && matchedRoutine.actionsJson.isNotEmpty()) {
            routineRepository.markRoutineRun(matchedRoutine.id, System.currentTimeMillis())
            val routineOutputs = mutableListOf<String>()
            for (subCommand in matchedRoutine.actionsJson) {
                if (killSwitch.isKilled.value) break
                val subReply = executeSingleTurnWithBudget(subCommand, screenContext)
                routineOutputs.add(subReply)
            }
            val combined = "Routine '${matchedRoutine.title}' completed: ${routineOutputs.joinToString(" | ")}"
            _lastResponseText.value = combined
            return@coroutineScope combined
        }

        executeSingleTurnWithBudget(userText, screenContext)
    }

    private class TurnTimeoutException(message: String) : Exception(message)

    private suspend fun executeSingleTurnWithBudget(
        userText: String,
        initialScreenContext: ScreenContextSnapshot?
    ): String = coroutineScope {
        var finalOutput = ""
        val job = launch {
            try {
                finalOutput = executeCommandInternal(
                    userText = userText,
                    initialScreenContext = initialScreenContext
                )
            } catch (_: TurnTimeoutException) {
                val timeoutMsg = "That took too long. Try again."
                _userFacingError.value = UserFacingError(timeoutMsg, "Try again")
                _lastResponseText.value = timeoutMsg
                _orbState.value = AssistantOrbState.IDLE
                _orbCaption.value = null
                finalOutput = timeoutMsg
            } catch (_: TimeoutCancellationException) {
                val timeoutMsg = "That took too long. Try again."
                _userFacingError.value = UserFacingError(timeoutMsg, "Try again")
                _lastResponseText.value = timeoutMsg
                _orbState.value = AssistantOrbState.IDLE
                _orbCaption.value = null
                finalOutput = timeoutMsg
            } catch (ce: CancellationException) {
                _orbCaption.value = null
                throw ce
            }
        }
        activeJob = job
        job.join()
        finalOutput
    }

    private suspend fun executeCommandInternal(
        userText: String,
        initialScreenContext: ScreenContextSnapshot?
    ): String = coroutineScope {
        val settings = settingsRepository.settingsFlow.first()
        var activeExecutionElapsedMs = 0L

        val conversationId = chatRepository.getOrCreateActiveConversationId()

        // Immediately write user message to messages table
        chatRepository.appendMessage(
            ChatMessage(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = userText
            )
        )

        _orbState.value = AssistantOrbState.THINKING
        _orbCaption.value = null

        val timerJob = launch {
            delay(6000L)
            if (isActive && _orbState.value == AssistantOrbState.THINKING) {
                _orbCaption.value = "Still working… (tap to cancel)"
            }
        }

        try {
            // 1. Check local fast-path router before invoking remote model
            when (val localRoute = LocalIntentRouter.route(userText)) {
                is LocalRouteResult.Stop -> {
                    killSwitch.trigger("User issued stop command")
                    val stopMsg = "Stopped immediately."
                    _lastResponseText.value = stopMsg
                    _orbState.value = AssistantOrbState.PAUSED_KILLSWITCH
                    return@coroutineScope stopMsg
                }
                is LocalRouteResult.DirectReply -> {
                    chatRepository.appendMessage(
                        ChatMessage(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = localRoute.reply
                        )
                    )
                    _lastResponseText.value = localRoute.reply
                    _orbState.value = AssistantOrbState.IDLE
                    return@coroutineScope localRoute.reply
                }
                is LocalRouteResult.ToolAction -> {
                    val tool = toolRegistry.getTool(localRoute.toolName)
                    var toolResultText = localRoute.templateReply
                    if (tool != null) {
                        val decision = safetyGate.evaluate(
                            toolCall = ToolCallSpec(
                                id = "local_${System.currentTimeMillis()}",
                                name = localRoute.toolName,
                                args = localRoute.args
                            ),
                            tool = tool,
                            confirmationLevel = settings.confirmationLevel,
                            screenContext = initialScreenContext
                        )

                        var userConfirmed = false
                        if (!decision.allowedImmediately) {
                            if (decision.requiresConfirmation) {
                                val approval = safetyGate.requestUserApproval(
                                    toolCall = ToolCallSpec(
                                        id = "local_${System.currentTimeMillis()}",
                                        name = localRoute.toolName,
                                        args = localRoute.args
                                    ),
                                    tool = tool,
                                    reasonEn = decision.reasonEn,
                                    reasonHi = decision.reasonEn
                                )
                                if (!approval.approved) {
                                    val deniedMsg = "Cancelled '${localRoute.toolName}' as requested."
                                    _lastResponseText.value = deniedMsg
                                    _orbState.value = AssistantOrbState.IDLE
                                    return@coroutineScope deniedMsg
                                }
                                userConfirmed = true
                            } else {
                                val blockedMsg = decision.reasonEn
                                _lastResponseText.value = blockedMsg
                                _orbState.value = AssistantOrbState.IDLE
                                return@coroutineScope blockedMsg
                            }
                        }

                        val exec = tool.execute(localRoute.args)
                        activityLogRepository.recordLog(
                            ActivityLogEntry(
                                toolName = tool.name,
                                riskLevel = tool.riskLevel,
                                argumentsSummary = SafetyGate.redactSensitiveArgs(localRoute.args),
                                resultSummary = exec.outputEn,
                                userConfirmed = userConfirmed,
                                durationMs = 5L
                            )
                        )
                        toolResultText = if (localRoute.templateReply.isNotBlank()) {
                            localRoute.templateReply
                        } else {
                            exec.outputEn
                        }
                    }

                    chatRepository.appendMessage(
                        ChatMessage(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = toolResultText
                        )
                    )
                    _lastResponseText.value = toolResultText
                    _orbState.value = if (killSwitch.isKilled.value) {
                        AssistantOrbState.PAUSED_KILLSWITCH
                    } else {
                        AssistantOrbState.IDLE
                    }
                    return@coroutineScope toolResultText
                }
                null -> {
                    // proceed to remote Gemini call
                }
            }

            // Opportunistically extract explicit "remember that X is Y"
            memoryLearner?.extractExplicitHeuristicFacts(
                userText = userText,
                isSensitiveScreen = SafetyGate.isSensitiveScreen(initialScreenContext)
            ) ?: extractFallbackRememberDirective(userText)

            var stepCount = 0
            var finalReply = ""
            var currentScreenSnapshot = initialScreenContext
                ?: JarvisAccessibilityService.captureCurrentScreenSnapshot()

            while (stepCount < MAX_STEPS_PER_TURN && isActive) {
                if (activeExecutionElapsedMs >= MAX_ACTIVE_TURN_DURATION_MS) {
                    throw TurnTimeoutException("Exceeded 60s active execution budget")
                }

                if (killSwitch.isKilled.value) {
                    _orbState.value = AssistantOrbState.PAUSED_KILLSWITCH
                    finalReply = "Stopped immediately by Kill Switch."
                    break
                }

                stepCount++
                val recentMessages = chatRepository.getRecentContextMessages(limit = 20)
                val memories = memoryRepository.searchTopFactsForContext(userText, limit = 15)
                    .let { matched ->
                        val all = memoryRepository.getAllMemoriesSnapshot()
                        (all.filter { it.pinned } + matched + all).distinctBy { it.id }.take(25)
                    }
                val summaries = chatRepository.getRecentSummaries(limit = 2)
                val availableTools = toolRegistry.getAvailableDescriptors(
                    fullControlMode = BuildConfig.FULL_CONTROL_MODE,
                    storeSafeMode = BuildConfig.STORE_SAFE_MODE
                )

                val stepStartMs = System.currentTimeMillis()
                var firstTokenRecorded = false
                var sentenceBuffer = StringBuilder()

                val stepDecision = agentBackendRepository.runAgentStep(
                    messages = recentMessages,
                    tools = availableTools,
                    memories = memories,
                    screenContext = currentScreenSnapshot,
                    language = settings.language,
                    summaries = summaries,
                    onTextChunk = { chunk ->
                        if (!firstTokenRecorded) {
                            firstTokenRecorded = true
                            val firstTokenLatency = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
                            DiagnosticsLogger.logVoiceEvent("TIMING_FIRST_TOKEN", "First token received", firstTokenLatency)
                            backgroundLearnScope.launch {
                                settingsRepository.setVoiceTimings(firstTokenLatency, 0L)
                            }
                        }
                        _streamingText.value = (_streamingText.value ?: "") + chunk
                        sentenceBuffer.append(chunk)

                        val currentText = sentenceBuffer.toString()
                        val match = Regex("^(.*?)([.!?])(\\s+|\$)", RegexOption.DOT_MATCHES_ALL).find(currentText)
                        if (match != null && match.range.last < currentText.length - 1) {
                            val completed = (match.groups[1]?.value.orEmpty() + match.groups[2]?.value.orEmpty()).trim()
                            sentenceBuffer = StringBuilder(currentText.substring(match.range.last + 1))
                            if (completed.isNotEmpty()) {
                                voiceSessionManager?.queueStreamingSentence(
                                    sentence = completed,
                                    language = settings.language,
                                    selectedVoiceName = settings.selectedVoiceName,
                                    speed = settings.speechSpeed,
                                    onAudioStarted = {
                                        _orbState.value = AssistantOrbState.SPEAKING
                                        val firstWordLatency = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
                                        DiagnosticsLogger.logVoiceEvent("TIMING_FIRST_WORD", "First word spoken", firstWordLatency)
                                    }
                                )
                            }
                        }
                    }
                )

                val remainingSentence = sentenceBuffer.toString().trim()
                if (remainingSentence.isNotEmpty() && stepDecision.toolCalls.isEmpty()) {
                    voiceSessionManager?.queueStreamingSentence(
                        sentence = remainingSentence,
                        language = settings.language,
                        selectedVoiceName = settings.selectedVoiceName,
                        speed = settings.speechSpeed,
                        onAudioStarted = {
                            _orbState.value = AssistantOrbState.SPEAKING
                        }
                    )
                }

                if (stepDecision.toolCalls.isNotEmpty()) {
                    _streamingText.value = null
                }
                val stepDurationMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
                activeExecutionElapsedMs += stepDurationMs
                _lastAgentStepLatencyMs.value = stepDurationMs

                if (stepDecision.keyRejected) {
                    _keyRejectedBanner.value = true
                    val errMsg = "Invalid or missing Gemini API key. Please check your key in Settings."
                    _userFacingError.value = UserFacingError(
                        message = errMsg,
                        actionLabel = "Open Settings",
                        isOpenSettings = true
                    )
                    _orbState.value = AssistantOrbState.IDLE
                    return@coroutineScope errMsg
                }

                if (stepDecision.toolCalls.isEmpty()) {
                    val rawText = stepDecision.assistantText ?: "Done."
                    if (rawText.startsWith("Unable to complete request") ||
                        rawText.startsWith("You appear to be offline")
                    ) {
                        _userFacingError.value = UserFacingError(rawText, "Try again")
                        _orbState.value = AssistantOrbState.IDLE
                        return@coroutineScope rawText
                    }
                    finalReply = rawText
                    break
                }

                var allToolsSucceeded = true
                val stepOutputs = mutableListOf<String>()

                for (toolCall in stepDecision.toolCalls) {
                    if (killSwitch.isKilled.value) {
                        allToolsSucceeded = false
                        break
                    }

                    val tool = toolRegistry.getTool(toolCall.name)
                    val serializedArgs = runCatching { argsAdapter.toJson(toolCall.args) }.getOrNull()

                    if (tool == null) {
                        val missingOutput = "Tool '${toolCall.name}' is not registered."
                        chatRepository.appendMessage(
                            ChatMessage(
                                conversationId = conversationId,
                                role = MessageRole.TOOL,
                                content = missingOutput,
                                toolName = toolCall.name,
                                toolArgsJson = serializedArgs
                            )
                        )
                        stepOutputs.add(missingOutput)
                        allToolsSucceeded = false
                        continue
                    }

                    val decision = safetyGate.evaluate(
                        toolCall = toolCall,
                        tool = tool,
                        confirmationLevel = settings.confirmationLevel,
                        screenContext = currentScreenSnapshot
                    )

                    var userConfirmed = false
                    var effectiveToolCall = toolCall
                    if (!decision.allowedImmediately) {
                        if (decision.requiresConfirmation) {
                            val approval = safetyGate.requestUserApproval(
                                toolCall = toolCall,
                                tool = tool,
                                reasonEn = decision.reasonEn,
                                reasonHi = decision.reasonEn
                            )
                            if (!approval.approved) {
                                val deniedMsg = "Cancelled '${toolCall.name}' as requested."
                                activityLogRepository.recordLog(
                                    ActivityLogEntry(
                                        toolName = toolCall.name,
                                        riskLevel = tool.riskLevel,
                                        argumentsSummary = SafetyGate.redactSensitiveArgs(toolCall.args),
                                        resultSummary = deniedMsg,
                                        userConfirmed = false,
                                        durationMs = 0L
                                    )
                                )
                                chatRepository.appendMessage(
                                    ChatMessage(
                                        conversationId = conversationId,
                                        role = MessageRole.TOOL,
                                        content = deniedMsg,
                                        toolName = toolCall.name,
                                        toolArgsJson = serializedArgs
                                    )
                                )
                                finalReply = deniedMsg
                                allToolsSucceeded = false
                                break
                            }
                            userConfirmed = true
                            if (approval.selectedPhoneOverride != null) {
                                val updatedArgs = toolCall.args.toMutableMap().apply {
                                    put("phone_number", approval.selectedPhoneOverride)
                                }
                                effectiveToolCall = toolCall.copy(args = updatedArgs)
                            }
                        } else {
                            val blockedMsg = decision.reasonEn
                            activityLogRepository.recordLog(
                                ActivityLogEntry(
                                    toolName = toolCall.name,
                                    riskLevel = tool.riskLevel,
                                    argumentsSummary = SafetyGate.redactSensitiveArgs(toolCall.args),
                                    resultSummary = blockedMsg,
                                    userConfirmed = false,
                                    durationMs = 0L
                                )
                            )
                            finalReply = blockedMsg
                            allToolsSucceeded = false
                            break
                        }
                    }

                    val startMs = System.currentTimeMillis()
                    val maxAttempts = if (isNonIdempotentTool(tool.name)) 1 else 2
                    var execResult = ToolExecutionResult(
                        toolName = tool.name,
                        success = false,
                        outputEn = "Not executed"
                    )
                    for (attempt in 1..maxAttempts) {
                        execResult = try {
                            tool.execute(effectiveToolCall.args)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            ToolExecutionResult(
                                toolName = tool.name,
                                success = false,
                                outputEn = "Tool error: ${e.localizedMessage ?: "unknown"}"
                            )
                        }
                        if (execResult.success) break
                        if (attempt < maxAttempts) {
                            delay(200)
                        }
                    }
                    val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    activeExecutionElapsedMs += durationMs

                    val localizedResult = execResult.outputEn

                    activityLogRepository.recordLog(
                        ActivityLogEntry(
                            toolName = tool.name,
                            riskLevel = tool.riskLevel,
                            argumentsSummary = SafetyGate.redactSensitiveArgs(effectiveToolCall.args),
                            resultSummary = localizedResult,
                            userConfirmed = userConfirmed,
                            durationMs = durationMs
                        )
                    )

                    val effectiveArgsJson = runCatching { argsAdapter.toJson(effectiveToolCall.args) }.getOrNull()
                    chatRepository.appendMessage(
                        ChatMessage(
                            conversationId = conversationId,
                            role = MessageRole.TOOL,
                            content = localizedResult,
                            toolName = tool.name,
                            toolArgsJson = effectiveArgsJson
                        )
                    )

                    stepOutputs.add(localizedResult)

                    if (execResult.requiresFollowUpScreenRead || tool.requiresFullControlMode) {
                        val refreshed = JarvisAccessibilityService.captureCurrentScreenSnapshot()
                        if (refreshed != null) {
                            currentScreenSnapshot = refreshed
                        }
                    }
                }

                if (!allToolsSucceeded && finalReply.isNotEmpty()) {
                    break
                }

                // Low-risk single-tool shortcut: complete turn immediately with execution result
                if (stepDecision.toolCalls.size == 1 &&
                    stepDecision.toolCalls.first().name in LOW_RISK_ONE_SHOT_TOOLS &&
                    stepOutputs.isNotEmpty()
                ) {
                    finalReply = stepDecision.assistantText?.takeIf { it.isNotBlank() }
                        ?: stepOutputs.first()
                    break
                }

                if (stepDecision.assistantText != null && stepOutputs.isNotEmpty()) {
                    finalReply = "${stepDecision.assistantText}\n${stepOutputs.joinToString("\n")}"
                    break
                } else if (stepOutputs.isNotEmpty() && stepCount >= 2) {
                    finalReply = stepOutputs.joinToString("\n")
                    break
                }
            }

            if (finalReply.isBlank()) {
                finalReply = "Done."
            }

            chatRepository.appendMessage(
                ChatMessage(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = finalReply
                )
            )

            _lastResponseText.value = finalReply
            _orbState.value = if (killSwitch.isKilled.value) {
                AssistantOrbState.PAUSED_KILLSWITCH
            } else {
                AssistantOrbState.IDLE
            }

            val capturedScreen = currentScreenSnapshot
            if (isPersonalText(userText)) {
                backgroundLearnScope.launch {
                    runCatching {
                        memoryLearner?.extractFactsFromTurn(
                            userText = userText,
                            assistantReply = finalReply,
                            screenContext = capturedScreen
                        )
                        memoryLearner?.summarizeConversationIfNeeded(conversationId = conversationId)
                    }
                }
            }

            return@coroutineScope finalReply
        } finally {
            timerJob.cancel()
            _orbCaption.value = null
            _streamingText.value = null
        }
    }

    private fun isPersonalText(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        val personalKeywords = listOf(
            "my ", "i like", "i prefer", "call me", "remember", "birthday",
            "wife", "husband", "mom", "mother", "dad", "father", "friend",
            "home", "office", "work", "favorite", "favourite", "hate", "live in",
            "address", "phone number", "email", "allergic", "allergy"
        )
        return personalKeywords.any { lower.contains(it) }
    }

    private suspend fun extractFallbackRememberDirective(userText: String) {
        val lower = userText.lowercase(Locale.US)
        if (lower.startsWith("remember ") || lower.contains("remember that ")) {
            val cleaned = userText
                .replace(Regex("(?i)^remember( that)?\\s+"), "")
                .trim()
            if (cleaned.isNotEmpty()) {
                val parts = cleaned.split(" is ", " = ", limit = 2)
                val key = parts.firstOrNull()?.trim()?.take(40) ?: "note"
                val value = parts.getOrNull(1)?.trim() ?: cleaned
                if (MemorySafetyFilter.validateFact(key, value) == null) {
                    memoryRepository.upsertMemory(
                        category = "PREFERENCE",
                        key = key,
                        value = value
                    )
                }
            }
        }
    }
}
