package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.ChatMessageDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryDao
import com.example.data.local.MemoryFactEntity
import com.example.data.local.RoutineDao
import com.example.data.local.RoutineEntity
import com.example.data.remote.AgentMessageDto
import com.example.data.remote.AgentScreenContextDto
import com.example.data.remote.AgentStepRequestDto
import com.example.data.remote.AgentToolDto
import com.example.data.remote.ApiService
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AgentStepDecision
import com.example.domain.model.AppLanguage
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.MemoryFact
import com.example.domain.model.MessageRole
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceProfile
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.tool.ToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class AgentBackendRepositoryImpl @Inject constructor(
    private val context: Context,
    private val apiService: ApiService
) : AgentBackendRepository {

    override suspend fun runAgentStep(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        memories: List<MemoryFact>,
        screenContext: ScreenContextSnapshot?,
        language: AppLanguage
    ): AgentStepDecision {
        val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val lastToolMsg = messages.lastOrNull { it.role == MessageRole.TOOL }

        if (!isNetworkAvailable() && lastToolMsg == null) {
            val offlineDecision = tryOfflineCommandFallback(lastUserMsg)
            if (offlineDecision != null) {
                return offlineDecision
            }
        }

        val systemMemoryPrompt = buildString {
            append("You are JARVIS, a concise, capable personal Android voice assistant. ")
            append("Respond in clear, natural English. Users may speak Indian names or kinship terms (like Mummy, Papa, Bhai); understand them naturally. ")
            if (memories.isNotEmpty()) {
                append("Known user facts: ")
                memories.take(15).forEach { fact ->
                    append("[${fact.category}] ${fact.key}=${fact.value}; ")
                }
            }
        }

        val dtoMessages = mutableListOf(
            AgentMessageDto(role = "SYSTEM", content = systemMemoryPrompt)
        )
        messages.forEach { msg ->
            dtoMessages.add(
                AgentMessageDto(
                    role = msg.role.name,
                    content = msg.content,
                    toolName = msg.toolName
                )
            )
        }

        val dtoTools = tools.map { tool ->
            AgentToolDto(
                name = tool.name,
                description = tool.description,
                jsonSchema = tool.jsonSchema,
                riskLevel = tool.riskLevel
            )
        }

        val request = AgentStepRequestDto(
            messages = dtoMessages,
            tools = dtoTools,
            screenContext = screenContext?.let {
                AgentScreenContextDto(
                    packageName = it.packageName,
                    summaryText = it.summaryText,
                    structuredJson = it.structuredJson
                )
            },
            languageCode = language.code
        )

        return try {
            val response = apiService.agentStep(request)
            AgentStepDecision(
                assistantText = response.assistantText,
                toolCalls = response.toolCalls.map { tc ->
                    ToolCallSpec(
                        id = tc.id,
                        name = tc.name,
                        args = tc.args
                    )
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val fallback = if (lastToolMsg == null) tryOfflineCommandFallback(lastUserMsg) else null
            fallback ?: AgentStepDecision(
                assistantText = "Backend connection error: ${e.localizedMessage ?: "Unable to reach server"}",
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

    private fun tryOfflineCommandFallback(userText: String): AgentStepDecision? {
        val lower = userText.lowercase()
        return when {
            lower.contains("flashlight") || lower.contains("torch") -> {
                val turnOff = lower.contains("off") || lower.contains("band")
                AgentStepDecision(
                    assistantText = "Updating flashlight.",
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
                val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?")
                val match = timeRegex.find(lower)
                val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
                val min = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                AgentStepDecision(
                    assistantText = "Setting your alarm.",
                    toolCalls = listOf(
                        ToolCallSpec(
                            id = "offline_alarm",
                            name = "set_alarm",
                            args = mapOf(
                                "hour" to hour.toString(),
                                "minute" to min.toString(),
                                "label" to "JARVIS Offline Alarm"
                            )
                        )
                    )
                )
            }
            lower.contains("timer") -> {
                val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 5
                val seconds = if (lower.contains("sec")) num else num * 60
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
                val level = Regex("(\\d{1,3})").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                AgentStepDecision(
                    assistantText = "Adjusting media volume.",
                    toolCalls = listOf(
                        ToolCallSpec(
                            id = "offline_vol",
                            name = "adjust_volume",
                            args = mapOf("stream" to "music", "level_percent" to level.toString())
                        )
                    )
                )
            }
            lower.startsWith("open ") -> {
                val appName = userText.replace(Regex("(?i)^open\\s+"), "").trim().ifEmpty { "Settings" }
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

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatMessageDao: ChatMessageDao
) : ChatRepository {

    override val messagesFlow: Flow<List<ChatMessage>> =
        chatMessageDao.observeAllMessages().map { list ->
            list.map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                    content = entity.content,
                    toolName = entity.toolName,
                    timestampMs = entity.timestampMs
                )
            }
        }

    override suspend fun getRecentContextMessages(limit: Int): List<ChatMessage> {
        return chatMessageDao.getRecentMessages(limit)
            .reversed()
            .map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                    content = entity.content,
                    toolName = entity.toolName,
                    timestampMs = entity.timestampMs
                )
            }
    }

    override suspend fun appendMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                role = message.role.name,
                content = message.content,
                toolName = message.toolName,
                timestampMs = message.timestampMs
            )
        )
    }

    override suspend fun clearAllMessages() {
        chatMessageDao.clearAll()
    }
}

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao
) : MemoryRepository {

    private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        seedScope.launch {
            if (memoryDao.getAllMemoriesSnapshot().isEmpty()) {
                memoryDao.insertMemory(
                    MemoryFactEntity(
                        category = "PREFERENCE",
                        key = "Preferred Speech Locale",
                        value = "English (India) en-IN",
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override val memoriesFlow: Flow<List<MemoryFact>> =
        memoryDao.observeMemories().map { list -> list.map { it.toDomain() } }

    override fun searchMemories(query: String): Flow<List<MemoryFact>> =
        memoryDao.searchMemories(query).map { list -> list.map { it.toDomain() } }

    override suspend fun getAllMemoriesSnapshot(): List<MemoryFact> =
        memoryDao.getAllMemoriesSnapshot().map { it.toDomain() }

    override suspend fun upsertMemory(category: String, key: String, value: String) {
        memoryDao.insertMemory(
            MemoryFactEntity(
                category = category.trim().ifEmpty { "GENERAL" },
                key = key.trim(),
                value = value.trim(),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    override suspend fun clearAllMemories() {
        memoryDao.clearAll()
    }

    private fun MemoryFactEntity.toDomain() = MemoryFact(
        id = id,
        category = category,
        key = key,
        value = value,
        updatedAtMs = updatedAtMs
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

    private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        seedScope.launch {
            val current = routineDao.observeRoutines().first()
            if (current.isEmpty()) {
                routineDao.upsertRoutine(
                    RoutineEntity(
                        title = "Morning Briefing",
                        triggerType = RoutineTriggerType.VOICE_PHRASE.code,
                        triggerValue = "good morning jarvis",
                        actionsDelimited = "Read my notifications||Set volume to 50 percent",
                        enabled = true
                    )
                )
                routineDao.upsertRoutine(
                    RoutineEntity(
                        title = "Driving Mode",
                        triggerType = RoutineTriggerType.HEADSET_CONNECTED.code,
                        triggerValue = "Bluetooth Audio",
                        actionsDelimited = "Set volume to 80 percent||Navigate to office",
                        enabled = true
                    )
                )
            }
        }
    }

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
        val normalized = utterance.lowercase().trim()
        val list = routineDao.observeRoutines().first()
        return list
            .map { it.toDomain() }
            .firstOrNull { item ->
                item.enabled &&
                    item.triggerType == RoutineTriggerType.VOICE_PHRASE &&
                    normalized.contains(item.triggerValue.lowercase().trim())
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
        val LANGUAGE = stringPreferencesKey("language_code")
        val VOICE_PROFILE = stringPreferencesKey("voice_profile_id")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val WAKE_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val WAKE_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_PAUSED = booleanPreferencesKey("wake_word_paused")
        val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start_enabled")
        val FLOATING_ORB = booleanPreferencesKey("floating_orb_enabled")
        val CONFIRMATION_LEVEL = stringPreferencesKey("confirmation_level")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch_active")
    }

    override val settingsFlow: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE] ?: AppLanguage.EN_IN.code),
            voiceProfile = VoiceProfile.fromId(prefs[Keys.VOICE_PROFILE] ?: VoiceProfile.EN_IN_NEURAL2_D.id),
            speechSpeed = (prefs[Keys.SPEECH_SPEED] ?: 1.0f).coerceIn(0.8f, 1.2f),
            speechPitch = 1.0f,
            wakeWordSensitivity = (prefs[Keys.WAKE_SENSITIVITY] ?: 0.65f).coerceIn(0.1f, 1.0f),
            wakeWordEnabled = prefs[Keys.WAKE_ENABLED] ?: false,
            wakeWordPaused = prefs[Keys.WAKE_PAUSED] ?: false,
            bootAutoStartEnabled = prefs[Keys.BOOT_AUTO_START] ?: false,
            floatingOrbEnabled = prefs[Keys.FLOATING_ORB] ?: false,
            confirmationLevel = ConfirmationLevel.fromCode(
                prefs[Keys.CONFIRMATION_LEVEL] ?: ConfirmationLevel.STANDARD.code
            ),
            killSwitchActive = prefs[Keys.KILL_SWITCH] ?: false
        )
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    override suspend fun setVoiceProfile(voiceProfile: VoiceProfile) {
        dataStore.edit { it[Keys.VOICE_PROFILE] = voiceProfile.id }
    }

    override suspend fun setSpeechSpeed(speed: Float) {
        dataStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.8f, 1.2f) }
    }

    override suspend fun setSpeechPitch(pitch: Float) {
        dataStore.edit { it[Keys.SPEECH_PITCH] = 1.0f }
    }

    override suspend fun setWakeWordSensitivity(sensitivity: Float) {
        dataStore.edit { it[Keys.WAKE_SENSITIVITY] = sensitivity.coerceIn(0.1f, 1.0f) }
    }

    override suspend fun setWakeWordEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_ENABLED] = enabled }
    }

    override suspend fun setWakeWordPaused(paused: Boolean) {
        dataStore.edit { it[Keys.WAKE_PAUSED] = paused }
    }

    override suspend fun setBootAutoStartEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BOOT_AUTO_START] = enabled }
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
}
