package com.example.domain.agent

import com.example.BuildConfig
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.ToolExecutionResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Multi-step Agent Orchestrator loop:
 * User speech/text -> Routine check -> Context assembly -> Backend /v1/agent/step ->
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
    private val killSwitch: KillSwitch
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
            "type_text"
        )

        fun isNonIdempotentTool(toolName: String): Boolean =
            toolName in NON_IDEMPOTENT_TOOLS
    }

    private val _orbState = MutableStateFlow(AssistantOrbState.IDLE)
    val orbState: StateFlow<AssistantOrbState> = _orbState.asStateFlow()

    private val _lastResponseText = MutableStateFlow(DEFAULT_GREETING)
    val lastResponseText: StateFlow<String> = _lastResponseText.asStateFlow()

    private val _lastAgentStepLatencyMs = MutableStateFlow(0L)
    val lastAgentStepLatencyMs: StateFlow<Long> = _lastAgentStepLatencyMs.asStateFlow()

    private var activeJob: Job? = null

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

        // Check if user command triggers a saved Voice Routine
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
                val timeoutMsg = "Turn timed out after 60 seconds. Stopping safely."
                chatRepository.appendMessage(
                    ChatMessage(role = MessageRole.ASSISTANT, content = timeoutMsg)
                )
                _lastResponseText.value = timeoutMsg
                _orbState.value = AssistantOrbState.IDLE
                finalOutput = timeoutMsg
            } catch (_: TimeoutCancellationException) {
                val timeoutMsg = "Turn timed out after 60 seconds. Stopping safely."
                chatRepository.appendMessage(
                    ChatMessage(role = MessageRole.ASSISTANT, content = timeoutMsg)
                )
                _lastResponseText.value = timeoutMsg
                _orbState.value = AssistantOrbState.IDLE
                finalOutput = timeoutMsg
            } catch (ce: CancellationException) {
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

        // Save user message
        chatRepository.appendMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = userText
            )
        )

        // Opportunistically extract "remember that X is Y" into local Memory
        extractMemoryIfRequested(userText)

        _orbState.value = AssistantOrbState.THINKING

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
            val memories = memoryRepository.getAllMemoriesSnapshot()
            val availableTools = toolRegistry.getAvailableDescriptors(
                fullControlMode = BuildConfig.FULL_CONTROL_MODE,
                storeSafeMode = BuildConfig.STORE_SAFE_MODE
            )

            val stepStartMs = System.currentTimeMillis()
            val stepDecision = agentBackendRepository.runAgentStep(
                messages = recentMessages,
                tools = availableTools,
                memories = memories,
                screenContext = currentScreenSnapshot,
                language = settings.language
            )
            val stepDurationMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
            activeExecutionElapsedMs += stepDurationMs
            _lastAgentStepLatencyMs.value = stepDurationMs

            if (stepDecision.toolCalls.isEmpty()) {
                finalReply = stepDecision.assistantText
                    ?: "Done."
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
                if (tool == null) {
                    val missingOutput = "Tool '${toolCall.name}' is not registered."
                    chatRepository.appendMessage(
                        ChatMessage(
                            role = MessageRole.TOOL,
                            content = missingOutput,
                            toolName = toolCall.name
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
                        // Confirmation wait time is EXCLUDED from the 60s active execution timeout
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
                                    role = MessageRole.TOOL,
                                    content = deniedMsg,
                                    toolName = toolCall.name
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

                // Execute tool: NEVER retry non-idempotent tools (calls, SMS, WhatsApp, taps, text input)
                val startMs = System.currentTimeMillis()
                val maxAttempts = if (isNonIdempotentTool(tool.name)) 1 else 2
                var execResult: ToolExecutionResult = ToolExecutionResult(
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

                chatRepository.appendMessage(
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = localizedResult,
                        toolName = tool.name
                    )
                )

                stepOutputs.add(localizedResult)

                // Refresh screen snapshot after UI automation tools
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

        return@coroutineScope finalReply
    }

    private suspend fun extractMemoryIfRequested(userText: String) {
        val lower = userText.lowercase()
        if (lower.startsWith("remember ") || lower.contains("remember that ")) {
            val cleaned = userText
                .replace(Regex("(?i)^remember( that)?\\s+"), "")
                .trim()
            if (cleaned.isNotEmpty()) {
                val parts = cleaned.split(" is ", " = ", limit = 2)
                val key = parts.firstOrNull()?.trim()?.take(40) ?: "Note"
                val value = parts.getOrNull(1)?.trim() ?: cleaned
                memoryRepository.upsertMemory(
                    category = "USER_PREFERENCE",
                    key = key,
                    value = value
                )
            }
        }
    }
}
