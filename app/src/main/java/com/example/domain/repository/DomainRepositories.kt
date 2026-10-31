package com.example.domain.repository

import com.example.domain.model.AgentMessage
import com.example.domain.model.AppLanguage
import com.example.domain.model.AppResult
import com.example.domain.model.PendingMemoryCandidate
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.SafetyPolicyLevel
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallRequest
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceProfile
import com.example.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AgentStepOutput(
    val assistantText: String?,
    val toolCalls: List<ToolCallRequest>,
    val memoryCandidates: List<PendingMemoryCandidate> = emptyList()
)

data class MemoryRecord(
    val id: Long = 0L,
    val category: String,
    val keyLabel: String,
    val contentValue: String,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ActivityLogRecord(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val argsJson: String,
    val riskLevel: RiskLevel,
    val status: String,
    val resultSummary: String
)

interface AgentBackendRepository {
    suspend fun executeAgentStep(
        messages: List<AgentMessage>,
        tools: List<ToolDescriptor>,
        screenContext: ScreenContextSnapshot?,
        language: AppLanguage,
        topKMemories: List<MemoryRecord>
    ): AppResult<AgentStepOutput>

    suspend fun streamTtsSentenceChunk(
        sentence: String,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float,
        pitch: Float
    ): ByteArray?
}

interface ChatRepository {
    fun observeMessages(): Flow<List<AgentMessage>>
    suspend fun getRecentMessages(limit: Int = 20): List<AgentMessage>
    suspend fun saveMessage(message: AgentMessage)
    suspend fun clearAllMessages()
}

interface MemoryRepository {
    val pendingCandidates: StateFlow<List<PendingMemoryCandidate>>

    fun observeMemories(): Flow<List<MemoryRecord>>
    suspend fun getAllMemoriesSnapshot(): List<MemoryRecord>
    suspend fun getTopKRelevantMemories(query: String, k: Int = 5): List<MemoryRecord>
    suspend fun upsertMemory(id: Long = 0L, category: String, keyLabel: String, contentValue: String)
    suspend fun deleteMemory(id: Long)
    suspend fun clearAllMemories()
    suspend fun exportMemoriesAsJson(): String

    fun queueMemoryCandidate(candidate: PendingMemoryCandidate)
    suspend fun approveMemoryCandidate(candidateId: String)
    fun dismissMemoryCandidate(candidateId: String)
}

interface RoutineRepository {
    fun observeRoutines(): Flow<List<RoutineItem>>
    suspend fun getAllRoutinesSnapshot(): List<RoutineItem>
    suspend fun upsertRoutine(
        id: Long = 0L,
        title: String,
        triggerType: RoutineTriggerType,
        triggerValue: String,
        actionsPrompt: String,
        enabled: Boolean = true
    )
    suspend fun setRoutineEnabled(id: Long, enabled: Boolean)
    suspend fun markRoutineExecuted(id: Long)
    suspend fun deleteRoutine(id: Long)
    suspend fun clearAllRoutines()
}

interface ActivityLogRepository {
    fun observeActivityLogs(): Flow<List<ActivityLogRecord>>
    suspend fun logToolAction(
        toolName: String,
        args: Map<String, String>,
        riskLevel: RiskLevel,
        status: String,
        resultSummary: String
    )
    suspend fun clearAllLogs()
}

interface SettingsRepository {
    val userSettings: Flow<UserSettings>
    suspend fun getSnapshot(): UserSettings
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setVoiceProfile(profile: VoiceProfile)
    suspend fun setVoiceSpeed(speed: Float)
    suspend fun setVoicePitch(pitch: Float)
    suspend fun setWakeWordEnabled(enabled: Boolean)
    suspend fun setWakeWordSensitivity(sensitivity: Float)
    suspend fun setWakeWordPaused(paused: Boolean)
    suspend fun setAutoStartOnBoot(enabled: Boolean)
    suspend fun setSafetyPolicyLevel(level: SafetyPolicyLevel)
    suspend fun setFullControlModeEnabled(enabled: Boolean)
    suspend fun setSimulateSecureScreen(enabled: Boolean)
    suspend fun setProactiveSuggestionsEnabled(enabled: Boolean)
    suspend fun setQuietHours(start: String, end: String)
    suspend fun setAnalyticsOptIn(enabled: Boolean)
    suspend fun setShowLatencyDebugPanel(show: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun resetAllSettings()
}
