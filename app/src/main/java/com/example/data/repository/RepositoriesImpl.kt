package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.local.ActivityLogDao
import com.example.data.local.DiagnosticsLogger
import com.example.data.local.ActivityLogEntity
import com.example.data.local.ConversationDao
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryDao
import com.example.data.local.MemoryFactEntity
import com.example.data.local.MessageDao
import com.example.data.local.MessageEntity
import com.example.data.local.RoutineDao
import com.example.data.local.RoutineEntity
import com.example.data.local.SummaryDao
import com.example.data.local.SummaryEntity
import com.example.data.remote.GeminiClient
import com.example.data.remote.GeminiTurnResult
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.agent.MemoryLearner
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConversationItem
import com.example.domain.model.ConversationSummaryItem
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemoryFact
import com.example.domain.model.MemorySource
import com.example.domain.model.MessageRole
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.UserSettings
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.tool.ToolDescriptor
import com.example.tools.MemorySafetyFilter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

@Singleton
class AgentBackendRepositoryImpl @Inject constructor(
    private val context: Context,
    private val geminiClient: GeminiClient,
    private val settingsRepository: SettingsRepository
) : AgentBackendRepository {

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
        val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val lastNonUserIsTool = messages.lastOrNull()?.role == MessageRole.TOOL

        if (!isNetworkAvailable() && !lastNonUserIsTool) {
            val offlineDecision = parseOfflineCommand(lastUserMsg)
            if (offlineDecision != null) {
                return offlineDecision
            }
            return AgentStepDecision(
                assistantText = "You appear to be offline. Connect to the internet or use an offline command like setting an alarm, timer, flashlight, volume, calling a contact, or opening an app.",
                toolCalls = emptyList()
            )
        }

        val settings = settingsRepository.settingsFlow.first()
        val chosenModelId = run {
            val text = settings.selectedTextModelId.trim()
            if (text.isBlank() || text.contains("2.0") || text.contains("2.5-flash-lite") || text.contains("live")) {
                GeminiClient.DEFAULT_TEXT_MODEL
            } else {
                text
            }
        }
        DiagnosticsLogger.log("AgentBackend", "runAgentStep using cascade text model: $chosenModelId (isVoiceInput=$isVoiceInput)")
        val pinnedFacts = memories.filter {
            it.pinned || it.category == MemoryCategory.PROFILE.name || it.category == MemoryCategory.CONTACT.name
        }
        val queryWords = lastUserMsg.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
        val relevantFacts = memories.filter { fact ->
            val target = "${fact.key} ${fact.value} ${fact.category}".lowercase(Locale.US)
            queryWords.any { target.contains(it) }
        }.sortedWith(
            compareByDescending<MemoryFact> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.lastUsedAtMs }
        ).take(12)

        val systemInstruction = MemoryLearner.buildSystemInstruction(
            pinnedFacts = pinnedFacts,
            relevantFacts = relevantFacts.ifEmpty { memories.take(10) },
            recentSummaries = summaries,
            screenContext = screenContext
        )

        return try {
            val result = if (onTextChunk != null) {
                geminiClient.streamGenerateContent(
                    modelId = chosenModelId,
                    systemInstruction = systemInstruction,
                    messages = messages,
                    tools = tools,
                    onTextChunk = onTextChunk
                )
            } else {
                geminiClient.generateContent(
                    modelId = chosenModelId,
                    systemInstruction = systemInstruction,
                    messages = messages,
                    tools = tools
                )
            }
            when (result) {
                is GeminiTurnResult.Success -> {
                    val totalTokens = (result.promptTokens + result.candidateTokens).coerceAtLeast(1)
                    settingsRepository.recordGeminiUsage(totalTokens)
                    AgentStepDecision(
                        assistantText = result.assistantText,
                        toolCalls = result.toolCalls
                    )
                }
                is GeminiTurnResult.KeyRejected -> {
                    AgentStepDecision(
                        assistantText = result.message,
                        toolCalls = emptyList(),
                        keyRejected = true
                    )
                }
                is GeminiTurnResult.RateLimited -> {
                    AgentStepDecision(
                        assistantText = result.message,
                        toolCalls = emptyList()
                    )
                }
                is GeminiTurnResult.ServerError -> {
                    val fallback = if (!lastNonUserIsTool) parseOfflineCommand(lastUserMsg) else null
                    fallback ?: AgentStepDecision(
                        assistantText = result.message,
                        toolCalls = emptyList()
                    )
                }
                is GeminiTurnResult.BadRequest -> {
                    DiagnosticsLogger.log("AgentBackend", "Gemini returned BadRequest: ${result.message}")
                    AgentStepDecision(
                        assistantText = result.message,
                        toolCalls = emptyList()
                    )
                }
                is GeminiTurnResult.Timeout -> {
                    AgentStepDecision(
                        assistantText = result.message,
                        toolCalls = emptyList()
                    )
                }
                is GeminiTurnResult.Offline -> {
                    val fallback = if (!lastNonUserIsTool) parseOfflineCommand(lastUserMsg) else null
                    fallback ?: AgentStepDecision(
                        assistantText = "You appear to be offline. Connect to the internet or use an offline command like setting an alarm, timer, flashlight, volume, calling a contact, or opening an app.",
                        toolCalls = emptyList()
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val fallback = if (!lastNonUserIsTool) parseOfflineCommand(lastUserMsg) else null
            fallback ?: AgentStepDecision(
                assistantText = "Unable to complete request: ${e.localizedMessage ?: "Network error"}",
                toolCalls = emptyList()
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        /**
         * Offline keyword fallback for basic device utilities.
         * Never guesses a default alarm time (7:00) or timer duration (5 min) when missing — asks the user instead.
         */
        fun parseOfflineCommand(userText: String): AgentStepDecision? {
            val trimmed = userText.trim()
            val lower = trimmed.lowercase(Locale.US)
            return when {
                lower.contains("flashlight") || lower.contains("torch") -> {
                    val turnOff = lower.contains("off") || lower.contains("disable")
                    AgentStepDecision(
                        assistantText = if (turnOff) "Turning off flashlight." else "Turning on flashlight.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_flashlight",
                                name = "toggle_flashlight",
                                args = mapOf("enabled" to (!turnOff).toString())
                            )
                        )
                    )
                }
                lower.contains("alarm") -> {
                    val timeRegex = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
                    val match = timeRegex.find(lower)
                    if (match == null) {
                        return AgentStepDecision(
                            assistantText = "What time should I set the alarm for?",
                            toolCalls = emptyList()
                        )
                    }
                    val rawHour = match.groupValues[1].toIntOrNull()
                    val min = match.groupValues[2].toIntOrNull() ?: 0
                    val ampm = match.groupValues[3].lowercase(Locale.US)
                    if (rawHour == null || min !in 0..59) {
                        return AgentStepDecision(
                            assistantText = "What time should I set the alarm for?",
                            toolCalls = emptyList()
                        )
                    }
                    val hour24 = when {
                        ampm == "pm" && rawHour in 1..11 -> rawHour + 12
                        ampm == "am" && rawHour == 12 -> 0
                        else -> rawHour
                    }
                    if (hour24 !in 0..23) {
                        return AgentStepDecision(
                            assistantText = "What time should I set the alarm for?",
                            toolCalls = emptyList()
                        )
                    }
                    AgentStepDecision(
                        assistantText = "Setting your alarm.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_alarm",
                                name = "set_alarm",
                                args = mapOf(
                                    "hour" to hour24.toString(),
                                    "minute" to min.toString(),
                                    "label" to "JARVIS Alarm"
                                )
                            )
                        )
                    )
                }
                lower.contains("timer") -> {
                    val durRegex = Regex("\\b(\\d+)\\s*(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs)?\\b")
                    val match = durRegex.find(lower)
                    val amount = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (amount == null || amount <= 0) {
                        return AgentStepDecision(
                            assistantText = "How long should I set the timer for?",
                            toolCalls = emptyList()
                        )
                    }
                    val unit = match.groupValues.getOrNull(2).orEmpty()
                    val seconds = when {
                        unit.startsWith("sec") -> amount
                        unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
                        else -> amount * 60
                    }
                    AgentStepDecision(
                        assistantText = "Starting your timer.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_timer",
                                name = "set_timer",
                                args = mapOf("seconds" to seconds.toString(), "label" to "JARVIS Timer")
                            )
                        )
                    )
                }
                lower.contains("volume") -> {
                    val level = Regex("(\\d{1,3})").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
                    if (level == null) {
                        return AgentStepDecision(
                            assistantText = "What percentage should I set the volume to?",
                            toolCalls = emptyList()
                        )
                    }
                    AgentStepDecision(
                        assistantText = "Adjusting media volume to $level%.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_vol",
                                name = "adjust_volume",
                                args = mapOf("stream" to "music", "level_percent" to level.toString())
                            )
                        )
                    )
                }
                lower.startsWith("call ") -> {
                    val target = trimmed.replace(Regex("(?i)^call\\s+"), "").trim()
                    if (target.isEmpty()) {
                        return AgentStepDecision(
                            assistantText = "Who would you like to call?",
                            toolCalls = emptyList()
                        )
                    }
                    AgentStepDecision(
                        assistantText = "Calling $target.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_call",
                                name = "call_contact",
                                args = mapOf("contact_name" to target)
                            )
                        )
                    )
                }
                lower.startsWith("open ") -> {
                    val appName = trimmed.replace(Regex("(?i)^open\\s+"), "").trim()
                    if (appName.isEmpty()) return null
                    AgentStepDecision(
                        assistantText = "Opening $appName.",
                        toolCalls = listOf(
                            ToolCallSpec(
                                id = "offline_open",
                                name = "open_app",
                                args = mapOf("app_name" to appName)
                            )
                        )
                    )
                }
                else -> null
            }
        }
    }
}

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao
) : ChatRepository {

    companion object {
        const val IDLE_NEW_CONVERSATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    @Volatile
    private var cachedConversationId: Long? = null

    override val messagesFlow: Flow<List<ChatMessage>> =
        messageDao.observeAllMessages().map { list ->
            list.map { it.toDomain() }
        }

    override val conversationsFlow: Flow<List<ConversationItem>> =
        conversationDao.observeConversations().map { list ->
            list.map { it.toDomain() }
        }

    override val summariesFlow: Flow<List<ConversationSummaryItem>> =
        summaryDao.observeSummaries().map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getOrCreateActiveConversationId(nowMs: Long): Long {
        val latest = conversationDao.getLatestConversation()
        if (latest == null) {
            val newId = conversationDao.insertConversation(
                ConversationEntity(
                    startedAt = nowMs,
                    title = "Conversation"
                )
            )
            cachedConversationId = newId
            return newId
        }

        val lastMsgTs = messageDao.getLastMessageTimestamp(latest.id) ?: latest.startedAt
        if (latest.endedAt != null || (nowMs - lastMsgTs) > IDLE_NEW_CONVERSATION_MS) {
            if (latest.endedAt == null) {
                conversationDao.markConversationEnded(latest.id, lastMsgTs)
            }
            val newId = conversationDao.insertConversation(
                ConversationEntity(
                    startedAt = nowMs,
                    title = "Conversation"
                )
            )
            cachedConversationId = newId
            return newId
        }

        cachedConversationId = latest.id
        return latest.id
    }

    override suspend fun startNewConversation(title: String, nowMs: Long): Long {
        val latest = conversationDao.getLatestConversation()
        if (latest != null && latest.endedAt == null) {
            conversationDao.markConversationEnded(latest.id, nowMs)
        }
        val newId = conversationDao.insertConversation(
            ConversationEntity(
                startedAt = nowMs,
                title = title.ifBlank { "New Conversation" }
            )
        )
        cachedConversationId = newId
        return newId
    }

    override suspend fun getRecentContextMessages(limit: Int): List<ChatMessage> {
        val convId = getOrCreateActiveConversationId()
        return messageDao.getMessagesForConversation(convId)
            .takeLast(limit)
            .map { it.toDomain() }
    }

    override suspend fun getMessagesForConversation(conversationId: Long): List<ChatMessage> {
        return messageDao.getMessagesForConversation(conversationId).map { it.toDomain() }
    }

    override suspend fun searchMessages(query: String): List<ChatMessage> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        return messageDao.searchMessages(clean).map { it.toDomain() }
    }

    override suspend fun appendMessage(message: ChatMessage): Long {
        val convId = if (message.conversationId > 0L && conversationDao.getConversationById(message.conversationId) != null) {
            message.conversationId
        } else {
            getOrCreateActiveConversationId(message.timestampMs)
        }

        val existingConv = conversationDao.getConversationById(convId)
        if (existingConv != null &&
            (existingConv.title == "Conversation" || existingConv.title == "New Conversation" || existingConv.title == "Initial Conversation") &&
            message.role == MessageRole.USER &&
            message.content.isNotBlank()
        ) {
            val newTitle = message.content.trim().take(42)
            conversationDao.insertConversation(existingConv.copy(title = newTitle))
        }

        return messageDao.insertMessage(
            MessageEntity(
                conversationId = convId,
                role = message.role.name,
                content = message.content,
                toolName = message.toolName,
                toolArgsJson = message.toolArgsJson,
                rawModelPartsJson = message.rawModelPartsJson,
                timestamp = message.timestampMs
            )
        )
    }

    override suspend fun saveSummary(summary: ConversationSummaryItem) {
        summaryDao.insertSummary(
            SummaryEntity(
                conversationId = summary.conversationId,
                fromMessageId = summary.fromMessageId,
                toMessageId = summary.toMessageId,
                text = summary.text,
                createdAt = summary.createdAtMs
            )
        )
        val conv = conversationDao.getConversationById(summary.conversationId)
        if (conv != null) {
            conversationDao.updateTitleAndSummary(
                id = conv.id,
                title = conv.title,
                summary = summary.text,
                endedAt = summary.createdAtMs
            )
        }
    }

    override suspend fun getRecentSummaries(limit: Int): List<ConversationSummaryItem> {
        return summaryDao.getRecentSummaries(limit).map { it.toDomain() }
    }

    override suspend fun deleteConversation(conversationId: Long) {
        summaryDao.deleteSummariesForConversation(conversationId)
        messageDao.deleteMessagesForConversation(conversationId)
        conversationDao.deleteConversation(conversationId)
        if (cachedConversationId == conversationId) {
            cachedConversationId = null
        }
    }

    override suspend fun clearAllMessages() {
        messageDao.clearAll()
        summaryDao.clearAll()
        conversationDao.clearAll()
        cachedConversationId = null
    }

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        toolName = toolName,
        toolArgsJson = toolArgsJson,
        rawModelPartsJson = rawModelPartsJson,
        timestampMs = timestamp
    )

    private fun ConversationEntity.toDomain() = ConversationItem(
        id = id,
        startedAtMs = startedAt,
        endedAtMs = endedAt,
        title = title,
        summary = summary
    )

    private fun SummaryEntity.toDomain() = ConversationSummaryItem(
        id = id,
        conversationId = conversationId,
        fromMessageId = fromMessageId,
        toMessageId = toMessageId,
        text = text,
        createdAtMs = createdAt
    )
}

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val moshi: Moshi = Moshi.Builder().build()
) : MemoryRepository {

    override val memoriesFlow: Flow<List<MemoryFact>> =
        memoryDao.observeActiveMemories().map { list -> list.map { it.toDomain() } }

    override val learnedTodayFlow: Flow<List<MemoryFact>>
        get() {
            val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            return memoryDao.observeLearnedSince(since).map { list -> list.map { it.toDomain() } }
        }

    override fun searchMemories(query: String): Flow<List<MemoryFact>> {
        val clean = query.trim()
        if (clean.isEmpty()) return memoriesFlow
        return memoryDao.searchMemories(clean).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun searchTopFactsForContext(query: String, limit: Int): List<MemoryFact> {
        val tokens = query.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) {
            return getAllMemoriesSnapshot().take(limit)
        }
        val ftsQuery = tokens.joinToString(" OR ") { "$it*" }
        val ftsMatches = runCatching {
            memoryDao.searchFts(ftsQuery, limit)
        }.getOrDefault(emptyList())

        val results = if (ftsMatches.isNotEmpty()) {
            ftsMatches.map { it.toDomain() }
        } else {
            getAllMemoriesSnapshot().filter { fact ->
                val target = "${fact.key} ${fact.value} ${fact.category}".lowercase(Locale.US)
                tokens.any { target.contains(it) }
            }.take(limit)
        }

        if (results.isNotEmpty()) {
            runCatching {
                memoryDao.recordUsage(results.map { it.id }, System.currentTimeMillis())
            }
        }
        return results
    }

    override suspend fun getAllMemoriesSnapshot(): List<MemoryFact> =
        memoryDao.getAllActiveMemoriesSnapshot().map { it.toDomain() }

    override suspend fun upsertMemory(
        category: String,
        key: String,
        value: String,
        source: String,
        confidence: Float,
        importance: Int,
        pinned: Boolean
    ): Long {
        val cleanKey = key.trim()
        val cleanVal = value.trim()
        if (cleanKey.isEmpty() || cleanVal.isEmpty()) return -1L
        if (MemorySafetyFilter.isSensitiveOrForbidden(cleanKey, cleanVal)) return -1L

        val normCat = MemoryCategory.fromString(category).name
        val normKey = MemorySafetyFilter.normalizeKey(cleanKey)
        val now = System.currentTimeMillis()

        val existing = memoryDao.findActiveByCategoryAndKey(normCat, normKey)
        if (existing != null) {
            if (existing.value.equals(cleanVal, ignoreCase = true)) {
                val updatedId = memoryDao.insertMemory(
                    existing.copy(
                        updatedAt = now,
                        lastUsedAt = now,
                        confidence = maxOf(existing.confidence, confidence),
                        importance = maxOf(existing.importance, importance.coerceIn(1, 5)),
                        pinned = existing.pinned || pinned
                    )
                )
                runCatching { memoryDao.rebuildFts() }
                return updatedId
            } else {
                // Value changed -> insert new row and mark old row supersededById = newId
                val newId = memoryDao.insertMemory(
                    MemoryFactEntity(
                        category = normCat,
                        key = normKey,
                        value = cleanVal,
                        source = MemorySource.fromString(source).name,
                        confidence = confidence.coerceIn(0f, 1f),
                        importance = importance.coerceIn(1, 5),
                        pinned = existing.pinned || pinned || normCat == "PROFILE" || normCat == "CONTACT",
                        createdAt = now,
                        updatedAt = now,
                        lastUsedAt = now,
                        useCount = existing.useCount,
                        supersededById = null
                    )
                )
                memoryDao.markSuperseded(oldId = existing.id, newId = newId, nowMs = now)
                runCatching { memoryDao.rebuildFts() }
                return newId
            }
        }

        val insertedId = memoryDao.insertMemory(
            MemoryFactEntity(
                category = normCat,
                key = normKey,
                value = cleanVal,
                source = MemorySource.fromString(source).name,
                confidence = confidence.coerceIn(0f, 1f),
                importance = importance.coerceIn(1, 5),
                pinned = pinned || normCat == "PROFILE" || normCat == "CONTACT",
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
                useCount = 0,
                supersededById = null
            )
        )
        runCatching { memoryDao.rebuildFts() }
        return insertedId
    }

    override suspend fun updateMemory(
        id: Long,
        category: String,
        key: String,
        value: String,
        pinned: Boolean,
        importance: Int
    ) {
        val existing = memoryDao.getById(id) ?: return
        val cleanKey = MemorySafetyFilter.normalizeKey(key)
        val cleanVal = value.trim()
        if (cleanKey.isEmpty() || cleanVal.isEmpty()) return
        if (MemorySafetyFilter.isSensitiveOrForbidden(cleanKey, cleanVal)) return

        memoryDao.insertMemory(
            existing.copy(
                category = MemoryCategory.fromString(category).name,
                key = cleanKey,
                value = cleanVal,
                pinned = pinned,
                importance = importance.coerceIn(1, 5),
                updatedAt = System.currentTimeMillis()
            )
        )
        runCatching { memoryDao.rebuildFts() }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        memoryDao.setPinned(id, pinned, System.currentTimeMillis())
    }

    override suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemoryById(id)
        runCatching { memoryDao.rebuildFts() }
    }

    override suspend fun deleteMemoriesByKeyOrQuery(query: String): Int {
        val norm = MemorySafetyFilter.normalizeKey(query)
        val rawLower = query.trim().lowercase(Locale.US)
        val active = memoryDao.getAllActiveMemoriesSnapshot()
        val matching = active.filter {
            it.key.equals(norm, ignoreCase = true) ||
                it.key.lowercase(Locale.US).contains(rawLower) ||
                it.value.lowercase(Locale.US).contains(rawLower)
        }
        for (entity in matching) {
            memoryDao.deleteMemoryById(entity.id)
        }
        if (matching.isNotEmpty()) {
            runCatching { memoryDao.rebuildFts() }
        }
        return matching.size
    }

    override suspend fun clearAllMemories() {
        memoryDao.clearAll()
        runCatching { memoryDao.rebuildFts() }
    }

    override suspend fun exportAllAsJson(): String {
        val all = memoryDao.getAllActiveMemoriesSnapshot()
        val listMaps = all.map {
            mapOf(
                "id" to it.id,
                "category" to it.category,
                "key" to it.key,
                "value" to it.value,
                "source" to it.source,
                "confidence" to it.confidence,
                "importance" to it.importance,
                "pinned" to it.pinned,
                "createdAt" to it.createdAt,
                "updatedAt" to it.updatedAt,
                "useCount" to it.useCount
            )
        }
        val adapter = moshi.adapter<List<Map<String, Any?>>>(
            com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
        )
        return adapter.indent("  ").toJson(listMaps)
    }

    private fun MemoryFactEntity.toDomain() = MemoryFact(
        id = id,
        category = category,
        key = key,
        value = value,
        source = source,
        confidence = confidence,
        importance = importance,
        pinned = pinned,
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
        lastUsedAtMs = lastUsedAt,
        useCount = useCount,
        supersededById = supersededById
    )
}

@Singleton
class ActivityLogRepositoryImpl @Inject constructor(
    private val activityLogDao: ActivityLogDao
) : ActivityLogRepository {

    override val logsFlow: Flow<List<ActivityLogEntry>> =
        activityLogDao.observeLogs().map { list ->
            list.map { entity ->
                ActivityLogEntry(
                    id = entity.id,
                    toolName = entity.toolName,
                    riskLevel = runCatching { RiskLevel.valueOf(entity.riskLevel) }.getOrDefault(RiskLevel.LOW),
                    argumentsSummary = entity.argumentsSummary,
                    resultSummary = entity.resultSummary,
                    userConfirmed = entity.userConfirmed,
                    durationMs = entity.durationMs,
                    timestampMs = entity.timestampMs
                )
            }
        }

    override suspend fun recordLog(entry: ActivityLogEntry) {
        activityLogDao.insertLog(
            ActivityLogEntity(
                toolName = entry.toolName,
                riskLevel = entry.riskLevel.name,
                argumentsSummary = entry.argumentsSummary,
                resultSummary = entry.resultSummary,
                userConfirmed = entry.userConfirmed,
                durationMs = entry.durationMs,
                timestampMs = entry.timestampMs
            )
        )
    }

    override suspend fun clearAllLogs() {
        activityLogDao.clearAll()
    }
}

@Singleton
class RoutineRepositoryImpl @Inject constructor(
    private val routineDao: RoutineDao
) : RoutineRepository {

    override val routinesFlow: Flow<List<RoutineItem>> =
        routineDao.observeRoutines().map { list ->
            list.map { entity -> entity.toDomain() }
        }

    override suspend fun upsertRoutine(routine: RoutineItem): Long {
        return routineDao.upsertRoutine(
            RoutineEntity(
                id = routine.id,
                title = routine.title.trim(),
                triggerType = routine.triggerType.code,
                triggerValue = routine.triggerValue.trim(),
                actionsDelimited = routine.actionsJson.joinToString("||"),
                enabled = routine.enabled,
                lastRunAtMs = routine.lastRunAtMs
            )
        )
    }

    override suspend fun toggleRoutine(id: Long, enabled: Boolean) {
        val current = routineDao.observeRoutines().first().firstOrNull { it.id == id } ?: return
        routineDao.upsertRoutine(current.copy(enabled = enabled))
    }

    override suspend fun deleteRoutine(id: Long) {
        routineDao.deleteRoutineById(id)
    }

    override suspend fun markRoutineRun(id: Long, timestampMs: Long) {
        routineDao.updateLastRun(id, timestampMs)
    }

    override suspend fun findMatchingVoiceRoutine(utterance: String): RoutineItem? {
        val normalized = utterance.lowercase(Locale.US).trim()
        val list = routineDao.observeRoutines().first()
        return list
            .map { it.toDomain() }
            .firstOrNull { item ->
                item.enabled &&
                    item.triggerType == RoutineTriggerType.VOICE_PHRASE &&
                    normalized.contains(item.triggerValue.lowercase(Locale.US).trim())
            }
    }

    private fun RoutineEntity.toDomain() = RoutineItem(
        id = id,
        title = title,
        triggerType = RoutineTriggerType.fromCode(triggerType),
        triggerValue = triggerValue,
        actionsJson = actionsDelimited.split("||").map { it.trim() }.filter { it.isNotEmpty() },
        enabled = enabled,
        lastRunAtMs = lastRunAtMs
    )
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val SELECTED_TEXT_MODEL_ID = stringPreferencesKey("selected_text_model_id")
        val SELECTED_VOICE_MODEL_ID = stringPreferencesKey("selected_voice_model_id")
        val SELECTED_VOICE_GENDER = stringPreferencesKey("selected_voice_gender")
        val SELECTED_LIVE_VOICE_NAME = stringPreferencesKey("selected_live_voice_name")
        val SELECTED_TTS_MODEL_ID = stringPreferencesKey("selected_tts_model_id")
        val REALTIME_LIVE_MODE = booleanPreferencesKey("realtime_live_mode")
        val AVAILABLE_MODELS = stringPreferencesKey("available_models_delimited")
        val LANGUAGE = stringPreferencesKey("language_code")
        val SELECTED_VOICE_NAME = stringPreferencesKey("selected_voice_name")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val FLOATING_ORB = booleanPreferencesKey("floating_orb_enabled")
        val CONFIRMATION_LEVEL = stringPreferencesKey("confirmation_level")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch_active")
        val AUTO_LEARN = booleanPreferencesKey("auto_learn_enabled")
        val ASK_BEFORE_SAVING_FACT = booleanPreferencesKey("ask_before_saving_fact")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val WAKE_PHRASE_ENABLED = booleanPreferencesKey("wake_phrase_enabled")
        val WAKE_PHRASE_SCREEN_ON = booleanPreferencesKey("wake_phrase_screen_on")
        val WAKE_PHRASE_30M = booleanPreferencesKey("wake_phrase_30m")
        val TIME_FIRST_TOKEN = longPreferencesKey("time_first_token")
        val TIME_FIRST_WORD = longPreferencesKey("time_first_word")
        val REQUESTS_TODAY = intPreferencesKey("requests_today")
        val TOKENS_TODAY = intPreferencesKey("tokens_today")
        val TOTAL_REQUESTS = intPreferencesKey("total_requests")
        val TOTAL_TOKENS = intPreferencesKey("total_tokens")
        val USAGE_DAY_EPOCH = longPreferencesKey("usage_day_epoch")
    }

    override val settingsFlow: Flow<UserSettings> = dataStore.data.map { prefs ->
        val rawModels = prefs[Keys.AVAILABLE_MODELS].orEmpty()
            .split("||")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("2.0") && !it.contains("2.5-flash-lite") }
            .ifEmpty { listOf(GeminiClient.DEFAULT_TEXT_MODEL, GeminiClient.DEFAULT_VOICE_MODEL) }

        val currentDay = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        val savedDay = prefs[Keys.USAGE_DAY_EPOCH] ?: currentDay
        val reqToday = if (savedDay == currentDay) (prefs[Keys.REQUESTS_TODAY] ?: 0) else 0
        val tokToday = if (savedDay == currentDay) (prefs[Keys.TOKENS_TODAY] ?: 0) else 0

        val savedModelId = prefs[Keys.SELECTED_MODEL_ID]
        val resolvedModelId = if (savedModelId.isNullOrBlank() || savedModelId.contains("2.0") || savedModelId.contains("2.5-flash-lite")) {
            rawModels.firstOrNull { it == GeminiClient.DEFAULT_TEXT_MODEL } ?: rawModels.first()
        } else {
            savedModelId
        }

        val savedVoiceModelId = prefs[Keys.SELECTED_VOICE_MODEL_ID]
        val resolvedVoiceModelId = if (savedVoiceModelId.isNullOrBlank() || savedVoiceModelId.contains("2.0") || savedVoiceModelId.contains("2.5-flash-lite")) {
            GeminiClient.DEFAULT_VOICE_MODEL
        } else {
            savedVoiceModelId
        }

        val savedTextModelId = prefs[Keys.SELECTED_TEXT_MODEL_ID]
        val resolvedTextModelId = if (savedTextModelId.isNullOrBlank() || savedTextModelId.contains("2.0") || savedTextModelId.contains("2.5-flash-lite")) {
            GeminiClient.DEFAULT_TEXT_MODEL
        } else {
            savedTextModelId
        }

        val savedTtsModelId = prefs[Keys.SELECTED_TTS_MODEL_ID]
        val resolvedTtsModelId = if (savedTtsModelId.isNullOrBlank() || savedTtsModelId.contains("2.0") || savedTtsModelId.contains("live")) {
            GeminiClient.DEFAULT_TTS_MODEL
        } else {
            savedTtsModelId
        }

        UserSettings(
            setupComplete = prefs[Keys.SETUP_COMPLETE] ?: false,
            selectedModelId = resolvedModelId,
            selectedTextModelId = resolvedTextModelId,
            selectedVoiceModelId = resolvedVoiceModelId,
            selectedTtsModelId = resolvedTtsModelId,
            isRealtimeLiveMode = prefs[Keys.REALTIME_LIVE_MODE] ?: false,
            selectedVoiceGender = prefs[Keys.SELECTED_VOICE_GENDER] ?: "MALE",
            selectedLiveVoiceName = prefs[Keys.SELECTED_LIVE_VOICE_NAME] ?: "Puck",
            availableModels = rawModels,
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE] ?: AppLanguage.EN_IN.code),
            voiceEngine = com.example.domain.model.VoiceEngineOption.fromCode(
                prefs[Keys.VOICE_ENGINE] ?: com.example.domain.model.VoiceEngineOption.NATURAL_GOOGLE.code
            ),
            selectedVoiceName = prefs[Keys.SELECTED_VOICE_NAME].orEmpty(),
            speechSpeed = (prefs[Keys.SPEECH_SPEED] ?: 1.0f).coerceIn(0.8f, 1.3f),
            speechPitch = 1.0f,
            floatingOrbEnabled = prefs[Keys.FLOATING_ORB] ?: false,
            wakePhraseEnabled = prefs[Keys.WAKE_PHRASE_ENABLED] ?: false,
            wakePhraseScreenOnOnly = prefs[Keys.WAKE_PHRASE_SCREEN_ON] ?: true,
            wakePhraseStopAfter30m = prefs[Keys.WAKE_PHRASE_30M] ?: true,
            confirmationLevel = ConfirmationLevel.fromCode(
                prefs[Keys.CONFIRMATION_LEVEL] ?: ConfirmationLevel.STANDARD.code
            ),
            killSwitchActive = prefs[Keys.KILL_SWITCH] ?: false,
            autoLearnEnabled = prefs[Keys.AUTO_LEARN] ?: true,
            askBeforeSavingFact = prefs[Keys.ASK_BEFORE_SAVING_FACT] ?: false,
            requestsToday = reqToday,
            tokensToday = tokToday,
            totalRequests = prefs[Keys.TOTAL_REQUESTS] ?: 0,
            totalTokens = prefs[Keys.TOTAL_TOKENS] ?: 0,
            timeToFirstTokenMs = prefs[Keys.TIME_FIRST_TOKEN] ?: 0L,
            timeToFirstWordMs = prefs[Keys.TIME_FIRST_WORD] ?: 0L,
            usageDayEpoch = currentDay
        )
    }

    override suspend fun setSetupComplete(complete: Boolean) {
        dataStore.edit { it[Keys.SETUP_COMPLETE] = complete }
    }

    override suspend fun setSelectedModelId(modelId: String) {
        dataStore.edit { it[Keys.SELECTED_MODEL_ID] = modelId.removePrefix("models/").trim() }
    }

    override suspend fun setSelectedTextModelId(modelId: String) {
        dataStore.edit { it[Keys.SELECTED_TEXT_MODEL_ID] = modelId.removePrefix("models/").trim() }
    }

    override suspend fun setSelectedVoiceModelId(modelId: String) {
        dataStore.edit { it[Keys.SELECTED_VOICE_MODEL_ID] = modelId.removePrefix("models/").trim() }
    }

    override suspend fun setSelectedTtsModelId(modelId: String) {
        dataStore.edit { it[Keys.SELECTED_TTS_MODEL_ID] = modelId.removePrefix("models/").trim() }
    }

    override suspend fun setRealtimeLiveMode(enabled: Boolean) {
        dataStore.edit { it[Keys.REALTIME_LIVE_MODE] = enabled }
    }

    override suspend fun setSelectedVoiceGender(gender: String) {
        dataStore.edit { it[Keys.SELECTED_VOICE_GENDER] = gender.trim().uppercase() }
    }

    override suspend fun setSelectedLiveVoiceName(voiceName: String) {
        dataStore.edit { it[Keys.SELECTED_LIVE_VOICE_NAME] = voiceName.trim() }
    }

    override suspend fun setAvailableModels(models: List<String>) {
        val clean = models.map { it.removePrefix("models/").trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isNotEmpty()) {
            dataStore.edit { it[Keys.AVAILABLE_MODELS] = clean.joinToString("||") }
        }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    override suspend fun setSelectedVoiceName(voiceName: String) {
        dataStore.edit { it[Keys.SELECTED_VOICE_NAME] = voiceName.trim() }
    }

    override suspend fun setSpeechSpeed(speed: Float) {
        dataStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.8f, 1.3f) }
    }

    override suspend fun setSpeechPitch(pitch: Float) {
        dataStore.edit { it[Keys.SPEECH_PITCH] = 1.0f }
    }

    override suspend fun setFloatingOrbEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FLOATING_ORB] = enabled }
    }

    override suspend fun setConfirmationLevel(level: ConfirmationLevel) {
        dataStore.edit { it[Keys.CONFIRMATION_LEVEL] = level.code }
    }

    override suspend fun setKillSwitchActive(active: Boolean) {
        dataStore.edit { it[Keys.KILL_SWITCH] = active }
    }

    override suspend fun setAutoLearnEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_LEARN] = enabled }
    }

    override suspend fun setAskBeforeSavingFact(ask: Boolean) {
        dataStore.edit { it[Keys.ASK_BEFORE_SAVING_FACT] = ask }
    }

    override suspend fun setVoiceEngine(voiceEngine: com.example.domain.model.VoiceEngineOption) {
        dataStore.edit { it[Keys.VOICE_ENGINE] = voiceEngine.code }
    }

    override suspend fun setWakePhraseEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_PHRASE_ENABLED] = enabled }
    }

    override suspend fun setWakePhraseScreenOnOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_PHRASE_SCREEN_ON] = enabled }
    }

    override suspend fun setWakePhraseStopAfter30m(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_PHRASE_30M] = enabled }
    }

    override suspend fun setVoiceTimings(firstTokenMs: Long, firstWordMs: Long) {
        dataStore.edit {
            it[Keys.TIME_FIRST_TOKEN] = firstTokenMs
            it[Keys.TIME_FIRST_WORD] = firstWordMs
        }
    }

    override suspend fun recordGeminiUsage(tokensUsed: Int, nowMs: Long) {
        val day = nowMs / (24L * 60L * 60L * 1000L)
        dataStore.edit { prefs ->
            val savedDay = prefs[Keys.USAGE_DAY_EPOCH] ?: day
            val prevReqToday = if (savedDay == day) (prefs[Keys.REQUESTS_TODAY] ?: 0) else 0
            val prevTokToday = if (savedDay == day) (prefs[Keys.TOKENS_TODAY] ?: 0) else 0
            prefs[Keys.USAGE_DAY_EPOCH] = day
            prefs[Keys.REQUESTS_TODAY] = prevReqToday + 1
            prefs[Keys.TOKENS_TODAY] = prevTokToday + tokensUsed.coerceAtLeast(0)
            prefs[Keys.TOTAL_REQUESTS] = (prefs[Keys.TOTAL_REQUESTS] ?: 0) + 1
            prefs[Keys.TOTAL_TOKENS] = (prefs[Keys.TOTAL_TOKENS] ?: 0) + tokensUsed.coerceAtLeast(0)
        }
    }
}
