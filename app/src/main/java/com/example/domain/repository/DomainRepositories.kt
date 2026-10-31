package com.example.domain.repository

import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.MemoryFact
import com.example.domain.model.RoutineItem
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceProfile
import com.example.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    val messagesFlow: Flow<List<ChatMessage>>
    suspend fun getRecentContextMessages(limit: Int = 20): List<ChatMessage>
    suspend fun appendMessage(message: ChatMessage)
    suspend fun clearAllMessages()
}

interface MemoryRepository {
    val memoriesFlow: Flow<List<MemoryFact>>
    fun searchMemories(query: String): Flow<List<MemoryFact>>
    suspend fun getAllMemoriesSnapshot(): List<MemoryFact>
    suspend fun upsertMemory(category: String, key: String, value: String)
    suspend fun deleteMemory(id: Long)
    suspend fun clearAllMemories()
}

interface ActivityLogRepository {
    val logsFlow: Flow<List<ActivityLogEntry>>
    suspend fun recordLog(entry: ActivityLogEntry)
    suspend fun clearAllLogs()
}

interface RoutineRepository {
    val routinesFlow: Flow<List<RoutineItem>>
    suspend fun upsertRoutine(routine: RoutineItem): Long
    suspend fun toggleRoutine(id: Long, enabled: Boolean)
    suspend fun deleteRoutine(id: Long)
    suspend fun markRoutineRun(id: Long, timestampMs: Long)
    suspend fun findMatchingVoiceRoutine(utterance: String): RoutineItem?
}

interface SettingsRepository {
    val settingsFlow: Flow<UserSettings>
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setVoiceProfile(voiceProfile: VoiceProfile)
    suspend fun setSpeechSpeed(speed: Float)
    suspend fun setSpeechPitch(pitch: Float)
    suspend fun setWakeWordSensitivity(sensitivity: Float)
    suspend fun setWakeWordEnabled(enabled: Boolean)
    suspend fun setWakeWordPaused(paused: Boolean)
    suspend fun setBootAutoStartEnabled(enabled: Boolean)
    suspend fun setFloatingOrbEnabled(enabled: Boolean)
    suspend fun setConfirmationLevel(level: ConfirmationLevel)
    suspend fun setKillSwitchActive(active: Boolean)
}

interface AgentBackendRepository {
    suspend fun runAgentStep(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        memories: List<MemoryFact>,
        screenContext: ScreenContextSnapshot?,
        language: AppLanguage
    ): AgentStepDecision
}
