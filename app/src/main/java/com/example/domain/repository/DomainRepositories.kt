package com.example.domain.repository

import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConversationItem
import com.example.domain.model.ConversationSummaryItem
import com.example.domain.model.MemoryFact
import com.example.domain.model.MemorySource
import com.example.domain.model.RoutineItem
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.UserSettings
import com.example.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow

interface AgentBackendRepository {
    suspend fun runAgentStep(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        memories: List<MemoryFact>,
        screenContext: ScreenContextSnapshot?,
        language: AppLanguage,
        summaries: List<ConversationSummaryItem> = emptyList(),
        onTextChunk: ((String) -> Unit)? = null
    ): AgentStepDecision
}

interface ChatRepository {
    val messagesFlow: Flow<List<ChatMessage>>
    val conversationsFlow: Flow<List<ConversationItem>>
    val summariesFlow: Flow<List<ConversationSummaryItem>>

    suspend fun getOrCreateActiveConversationId(nowMs: Long = System.currentTimeMillis()): Long
    suspend fun startNewConversation(title: String = "New Conversation", nowMs: Long = System.currentTimeMillis()): Long
    suspend fun getRecentContextMessages(limit: Int): List<ChatMessage>
    suspend fun getMessagesForConversation(conversationId: Long): List<ChatMessage>
    suspend fun searchMessages(query: String): List<ChatMessage>
    suspend fun appendMessage(message: ChatMessage): Long
    suspend fun saveSummary(summary: ConversationSummaryItem)
    suspend fun getRecentSummaries(limit: Int = 2): List<ConversationSummaryItem>
    suspend fun deleteConversation(conversationId: Long)
    suspend fun clearAllMessages()
}

interface MemoryRepository {
    val memoriesFlow: Flow<List<MemoryFact>>
    val learnedTodayFlow: Flow<List<MemoryFact>>

    fun searchMemories(query: String): Flow<List<MemoryFact>>
    suspend fun searchTopFactsForContext(query: String, limit: Int = 12): List<MemoryFact>
    suspend fun getAllMemoriesSnapshot(): List<MemoryFact>
    suspend fun upsertMemory(
        category: String,
        key: String,
        value: String,
        source: String = MemorySource.USER_EXPLICIT.name,
        confidence: Float = 1.0f,
        importance: Int = 3,
        pinned: Boolean = false
    ): Long
    suspend fun updateMemory(
        id: Long,
        category: String,
        key: String,
        value: String,
        pinned: Boolean,
        importance: Int = 3
    )
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun deleteMemory(id: Long)
    suspend fun deleteMemoriesByKeyOrQuery(query: String): Int
    suspend fun clearAllMemories()
    suspend fun exportAllAsJson(): String
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
    suspend fun setSetupComplete(complete: Boolean)
    suspend fun setSelectedModelId(modelId: String)
    suspend fun setAvailableModels(models: List<String>)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setSelectedVoiceName(voiceName: String)
    suspend fun setSpeechSpeed(speed: Float)
    suspend fun setSpeechPitch(pitch: Float)
    suspend fun setFloatingOrbEnabled(enabled: Boolean)
    suspend fun setConfirmationLevel(level: ConfirmationLevel)
    suspend fun setKillSwitchActive(active: Boolean)
    suspend fun setAutoLearnEnabled(enabled: Boolean)
    suspend fun setAskBeforeSavingFact(ask: Boolean)
    suspend fun setVoiceEngine(voiceEngine: com.example.domain.model.VoiceEngineOption)
    suspend fun setWakePhraseEnabled(enabled: Boolean)
    suspend fun setWakePhraseScreenOnOnly(enabled: Boolean)
    suspend fun setWakePhraseStopAfter30m(enabled: Boolean)
    suspend fun setVoiceTimings(firstTokenMs: Long, firstWordMs: Long)
    suspend fun recordGeminiUsage(tokensUsed: Int, nowMs: Long = System.currentTimeMillis())
}
