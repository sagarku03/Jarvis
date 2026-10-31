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
import com.example.BuildConfig
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.ChatMessageDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryItemDao
import com.example.data.local.MemoryItemEntity
import com.example.data.local.RoutineDao
import com.example.data.local.RoutineEntity
import com.example.data.remote.AgentStepRequestDto
import com.example.data.remote.ApiService
import com.example.data.remote.TtsRequestDto
import com.example.data.remote.WireMessageDto
import com.example.data.remote.WireToolDescriptorDto
import com.example.data.security.VaultCipherManager
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AgentMessage
import com.example.domain.model.AppLanguage
import com.example.domain.model.AppResult
import com.example.domain.model.MessageRole
import com.example.domain.model.PendingMemoryCandidate
import com.example.domain.model.RiskLevel
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.SafetyPolicyLevel
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallRequest
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceProfile
import com.example.domain.repository.ActivityLogRecord
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.AgentStepOutput
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRecord
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class AgentBackendRepositoryImpl @Inject constructor(
    private val context: Context,
    private val remoteApiService: ApiService,
    private val optionalFakeApiService: ApiService?
) : AgentBackendRepository {

    override suspend fun executeAgentStep(
        messages: List<AgentMessage>,
        tools: List<ToolDescriptor>,
        screenContext: ScreenContextSnapshot?,
        language: AppLanguage,
        topKMemories: List<MemoryRecord>
    ): AppResult<AgentStepOutput> {
        val isOnline = checkNetworkOnline()
        if (!isOnline && !BuildConfig.USE_FAKE_BACKEND) {
            return AppResult.Error(
                messageEn = "You appear to be offline. Falling back to local Android voice & offline tools.",
                messageHi = "इंटरनेट कनेक्शन उपलब्ध नहीं है। लोकल ऑफ़लाइन मोड का उपयोग किया जा रहा है।"
            )
        }

        return try {
            val memorySystemMsg = if (topKMemories.isNotEmpty()) {
                listOf(
                    WireMessageDto(
                        role = "system",
                        content = topKMemories.joinToString("; ") { "[${it.category}] ${it.keyLabel}: ${it.contentValue}" }
                    )
                )
            } else {
                emptyList()
            }

            val wireMessages = memorySystemMsg + messages.map { msg ->
                // Stage 7/8 defense: wrap user input in <user_input> tags
                val safeContent = if (msg.role == MessageRole.USER) {
                    msg.content
                } else {
                    msg.content
                }
                WireMessageDto(
                    role = msg.role.wireName,
                    content = safeContent,
                    toolCallId = msg.toolCallId,
                    toolName = msg.toolName
                )
            }

            val wireTools = tools.map { tool ->
                WireToolDescriptorDto(
                    name = tool.name,
                    description = tool.description,
                    jsonSchema = tool.jsonSchema,
                    riskLevel = tool.riskLevel
                )
            }

            val safeScreenPayload = screenContext?.toPromptInjectionSafePayload()

            val requestDto = AgentStepRequestDto(
                messages = wireMessages,
                tools = wireTools,
                screenContext = safeScreenPayload,
                languageCode = language.code
            )

            val service = if (BuildConfig.USE_FAKE_BACKEND && optionalFakeApiService != null) {
                optionalFakeApiService
            } else {
                remoteApiService
            }
            val responseDto = service.agentStep(requestDto)

            AppResult.Success(
                AgentStepOutput(
                    assistantText = responseDto.assistantText,
                    toolCalls = responseDto.toolCalls.map { tc ->
                        ToolCallRequest(
                            id = tc.id,
                            name = tc.name,
                            args = tc.args
                        )
                    },
                    memoryCandidates = responseDto.memoryCandidates.map { cand ->
                        PendingMemoryCandidate(
                            id = cand.id,
                            category = cand.category,
                            keyLabel = cand.keyLabel,
                            contentValue = cand.contentValue,
                            sourceUtterance = cand.sourceUtterance
                        )
                    }
                )
            )
        } catch (e: Exception) {
            AppResult.Error(
                messageEn = "Backend proxy error: ${e.localizedMessage ?: "Connection failed"}",
                messageHi = "बैकएंड प्रॉक्सी त्रुटि: ${e.localizedMessage ?: "कनेक्शन विफल"}",
                throwable = e
            )
        }
    }

    override suspend fun streamTtsSentenceChunk(
        sentence: String,
        language: AppLanguage,
        voiceProfile: VoiceProfile,
        speed: Float,
        pitch: Float
    ): ByteArray? {
        return runCatching {
            val service = if (BuildConfig.USE_FAKE_BACKEND && optionalFakeApiService != null) {
                optionalFakeApiService
            } else {
                remoteApiService
            }
            val body = service.textToSpeech(
                TtsRequestDto(
                    text = sentence,
                    languageCode = language.code,
                    voiceProfile = voiceProfile.id,
                    speed = speed,
                    pitch = pitch
                )
            )
            body.bytes()
        }.getOrNull()
    }

    private fun checkNetworkOnline(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatMessageDao: ChatMessageDao
) : ChatRepository {

    override fun observeMessages(): Flow<List<AgentMessage>> {
        return chatMessageDao.observeAllMessages().map { list ->
            list.map { entity ->
                AgentMessage(
                    id = entity.id,
                    role = MessageRole.entries.firstOrNull { it.wireName == entity.role } ?: MessageRole.USER,
                    content = entity.content,
                    toolCallId = entity.toolCallId,
                    toolName = entity.toolName,
                    timestamp = entity.timestamp
                )
            }
        }
    }

    override suspend fun getRecentMessages(limit: Int): List<AgentMessage> {
        return chatMessageDao.getRecentMessagesDesc(limit)
            .reversed()
            .map { entity ->
                AgentMessage(
                    id = entity.id,
                    role = MessageRole.entries.firstOrNull { it.wireName == entity.role } ?: MessageRole.USER,
                    content = entity.content,
                    toolCallId = entity.toolCallId,
                    toolName = entity.toolName,
                    timestamp = entity.timestamp
                )
            }
    }

    override suspend fun saveMessage(message: AgentMessage) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = message.id,
                role = message.role.wireName,
                content = message.content,
                toolCallId = message.toolCallId,
                toolName = message.toolName,
                timestamp = message.timestamp
            )
        )
        chatMessageDao.pruneOldMessages(MAX_CHAT_MESSAGES)
    }

    override suspend fun clearAllMessages() {
        chatMessageDao.clearAllMessages()
    }

    companion object {
        const val MAX_CHAT_MESSAGES = 500
    }
}

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryItemDao: MemoryItemDao,
    private val cipherManager: VaultCipherManager
) : MemoryRepository {

    private val _pendingCandidates = MutableStateFlow<List<PendingMemoryCandidate>>(emptyList())
    override val pendingCandidates: StateFlow<List<PendingMemoryCandidate>> =
        _pendingCandidates.asStateFlow()

    override fun observeMemories(): Flow<List<MemoryRecord>> {
        return memoryItemDao.observeAllMemories().map { entities ->
            entities.map { entity ->
                MemoryRecord(
                    id = entity.id,
                    category = entity.category,
                    keyLabel = entity.keyLabel,
                    contentValue = cipherManager.decrypt(entity.encryptedValue),
                    updatedAt = entity.updatedAt
                )
            }
        }
    }

    override suspend fun getAllMemoriesSnapshot(): List<MemoryRecord> {
        return memoryItemDao.getAllMemoriesSnapshot().map { entity ->
            MemoryRecord(
                id = entity.id,
                category = entity.category,
                keyLabel = entity.keyLabel,
                contentValue = cipherManager.decrypt(entity.encryptedValue),
                updatedAt = entity.updatedAt
            )
        }
    }

    /**
     * Stage 5: Top-K relevant memory retrieval.
     * Scores stored memories by token overlap with the current query + recency so only the top K
     * relevant items are injected into the agent prompt.
     */
    override suspend fun getTopKRelevantMemories(query: String, k: Int): List<MemoryRecord> {
        val all = getAllMemoriesSnapshot()
        if (all.size <= k) return all
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

        return all.sortedByDescending { record ->
            val haystack = "${record.category} ${record.keyLabel} ${record.contentValue}".lowercase()
            val matchScore = tokens.count { token -> haystack.contains(token) } * 1000L
            matchScore + (record.updatedAt / 1_000_000L)
        }.take(k)
    }

    override suspend fun upsertMemory(
        id: Long,
        category: String,
        keyLabel: String,
        contentValue: String
    ) {
        val encrypted = cipherManager.encrypt(contentValue.trim())
        memoryItemDao.upsertMemory(
            MemoryItemEntity(
                id = id,
                category = category.trim().ifEmpty { "General" },
                keyLabel = keyLabel.trim(),
                encryptedValue = encrypted,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteMemory(id: Long) {
        memoryItemDao.deleteMemoryById(id)
    }

    override suspend fun clearAllMemories() {
        memoryItemDao.clearAllMemories()
        _pendingCandidates.value = emptyList()
    }

    override suspend fun exportMemoriesAsJson(): String {
        val items = getAllMemoriesSnapshot()
        val entriesJson = items.joinToString(",\n  ") { item ->
            """{"id":${item.id},"category":"${escapeJson(item.category)}","keyLabel":"${escapeJson(item.keyLabel)}","contentValue":"${escapeJson(item.contentValue)}","updatedAt":${item.updatedAt}}"""
        }
        return "[\n  $entriesJson\n]"
    }

    override fun queueMemoryCandidate(candidate: PendingMemoryCandidate) {
        val current = _pendingCandidates.value
        if (current.none { it.keyLabel.equals(candidate.keyLabel, ignoreCase = true) }) {
            _pendingCandidates.value = current + candidate
        }
    }

    override suspend fun approveMemoryCandidate(candidateId: String) {
        val target = _pendingCandidates.value.firstOrNull { it.id == candidateId } ?: return
        upsertMemory(
            category = target.category,
            keyLabel = target.keyLabel,
            contentValue = target.contentValue
        )
        dismissMemoryCandidate(candidateId)
    }

    override fun dismissMemoryCandidate(candidateId: String) {
        _pendingCandidates.value = _pendingCandidates.value.filterNot { it.id == candidateId }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

@Singleton
class RoutineRepositoryImpl @Inject constructor(
    private val routineDao: RoutineDao
) : RoutineRepository {

    override fun observeRoutines(): Flow<List<RoutineItem>> {
        return routineDao.observeAllRoutines().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getAllRoutinesSnapshot(): List<RoutineItem> {
        return routineDao.getAllRoutinesSnapshot().map { it.toDomain() }
    }

    override suspend fun upsertRoutine(
        id: Long,
        title: String,
        triggerType: RoutineTriggerType,
        triggerValue: String,
        actionsPrompt: String,
        enabled: Boolean
    ) {
        routineDao.upsertRoutine(
            RoutineEntity(
                id = id,
                title = title.trim(),
                triggerType = triggerType.id,
                triggerValue = triggerValue.trim(),
                actionsPrompt = actionsPrompt.trim(),
                enabled = enabled
            )
        )
    }

    override suspend fun setRoutineEnabled(id: Long, enabled: Boolean) {
        routineDao.setRoutineEnabled(id, enabled)
    }

    override suspend fun markRoutineExecuted(id: Long) {
        routineDao.markRoutineExecuted(id, System.currentTimeMillis())
    }

    override suspend fun deleteRoutine(id: Long) {
        routineDao.deleteRoutineById(id)
    }

    override suspend fun clearAllRoutines() {
        routineDao.clearAllRoutines()
    }

    private fun RoutineEntity.toDomain() = RoutineItem(
        id = id,
        title = title,
        triggerType = RoutineTriggerType.fromId(triggerType),
        triggerValue = triggerValue,
        actionsPrompt = actionsPrompt,
        enabled = enabled,
        lastRunTimestamp = lastRunTimestamp
    )
}

@Singleton
class ActivityLogRepositoryImpl @Inject constructor(
    private val activityLogDao: ActivityLogDao
) : ActivityLogRepository {

    companion object {
        const val MAX_ACTIVITY_LOGS = 1000
        private val REDACTED_MESSAGE_KEYS = setOf(
            "message", "text", "reply_text", "body", "content"
        )
        private val MASKED_CONTACT_KEYS = setOf(
            "phone", "contact", "recipient", "number"
        )

        fun redactSensitiveArgs(args: Map<String, String>): Map<String, String> {
            return args.mapValues { (key, value) ->
                val lowerKey = key.lowercase()
                when {
                    lowerKey in REDACTED_MESSAGE_KEYS -> "[REDACTED len=${value.length}]"
                    lowerKey in MASKED_CONTACT_KEYS -> {
                        val digits = value.count { it.isDigit() }
                        if (digits >= 7) {
                            com.example.domain.contact.ContactsResolver.maskPhoneNumber(value)
                        } else {
                            "[REDACTED_CONTACT]"
                        }
                    }
                    else -> value
                }
            }
        }
    }

    override fun observeActivityLogs(): Flow<List<ActivityLogRecord>> {
        return activityLogDao.observeAllLogs().map { entities ->
            entities.map { entity ->
                ActivityLogRecord(
                    id = entity.id,
                    timestamp = entity.timestamp,
                    toolName = entity.toolName,
                    argsJson = entity.argsJson,
                    riskLevel = RiskLevel.entries.firstOrNull { it.name == entity.riskLevel } ?: RiskLevel.LOW,
                    status = entity.status,
                    resultSummary = entity.resultSummary
                )
            }
        }
    }

    override suspend fun logToolAction(
        toolName: String,
        args: Map<String, String>,
        riskLevel: RiskLevel,
        status: String,
        resultSummary: String
    ) {
        val redacted = redactSensitiveArgs(args)
        val serializedArgs = if (redacted.isEmpty()) {
            "{}"
        } else {
            redacted.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "\"$k\":\"${v.replace("\"", "\\\"")}\""
            }
        }
        activityLogDao.insertLog(
            ActivityLogEntity(
                timestamp = System.currentTimeMillis(),
                toolName = toolName,
                argsJson = serializedArgs,
                riskLevel = riskLevel.name,
                status = status,
                resultSummary = resultSummary
            )
        )
        activityLogDao.pruneOldLogs(MAX_ACTIVITY_LOGS)
    }

    override suspend fun clearAllLogs() {
        activityLogDao.clearAllLogs()
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language_code")
        val VOICE_PROFILE = stringPreferencesKey("voice_profile_id")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val WAKE_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val WAKE_PAUSED = booleanPreferencesKey("wake_word_paused")
        val AUTO_START_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val SAFETY_LEVEL = stringPreferencesKey("safety_policy_level")
        val FULL_CONTROL = booleanPreferencesKey("full_control_mode")
        val SIMULATE_SECURE_SCREEN = booleanPreferencesKey("simulate_secure_screen")
        val PROACTIVE_SUGGESTIONS = booleanPreferencesKey("proactive_suggestions_enabled")
        val QUIET_START = stringPreferencesKey("quiet_hours_start")
        val QUIET_END = stringPreferencesKey("quiet_hours_end")
        val ANALYTICS_OPT_IN = booleanPreferencesKey("analytics_opt_in")
        val SHOW_LATENCY_PANEL = booleanPreferencesKey("show_latency_debug_panel")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
    }

    override val userSettings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE] ?: AppLanguage.HINGLISH.code),
            voiceProfile = VoiceProfile.fromId(prefs[Keys.VOICE_PROFILE] ?: VoiceProfile.JARVIS_ALPHA.id),
            voiceSpeed = (prefs[Keys.VOICE_SPEED] ?: 1.0f).coerceIn(0.6f, 1.8f),
            voicePitch = (prefs[Keys.VOICE_PITCH] ?: 1.0f).coerceIn(0.6f, 1.5f),
            wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: false,
            wakeWordSensitivity = (prefs[Keys.WAKE_SENSITIVITY] ?: 0.65f).coerceIn(0.1f, 1.0f),
            wakeWordPaused = prefs[Keys.WAKE_PAUSED] ?: false,
            autoStartOnBoot = prefs[Keys.AUTO_START_BOOT] ?: false,
            safetyPolicyLevel = SafetyPolicyLevel.fromId(
                prefs[Keys.SAFETY_LEVEL] ?: SafetyPolicyLevel.STANDARD.id
            ),
            fullControlModeEnabled = prefs[Keys.FULL_CONTROL] ?: BuildConfig.FULL_CONTROL_MODE,
            simulateSecureScreen = (prefs[Keys.SIMULATE_SECURE_SCREEN] ?: false) && BuildConfig.IS_DEV_FLAVOR,
            proactiveSuggestionsEnabled = prefs[Keys.PROACTIVE_SUGGESTIONS] ?: false,
            quietHoursStart = prefs[Keys.QUIET_START] ?: "22:00",
            quietHoursEnd = prefs[Keys.QUIET_END] ?: "07:00",
            analyticsOptIn = prefs[Keys.ANALYTICS_OPT_IN] ?: false,
            showLatencyDebugPanel = (prefs[Keys.SHOW_LATENCY_PANEL] ?: false) && BuildConfig.IS_DEV_FLAVOR,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false
        )
    }

    override suspend fun getSnapshot(): UserSettings = userSettings.first()

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    override suspend fun setVoiceProfile(profile: VoiceProfile) {
        dataStore.edit { it[Keys.VOICE_PROFILE] = profile.id }
    }

    override suspend fun setVoiceSpeed(speed: Float) {
        dataStore.edit { it[Keys.VOICE_SPEED] = speed.coerceIn(0.6f, 1.8f) }
    }

    override suspend fun setVoicePitch(pitch: Float) {
        dataStore.edit { it[Keys.VOICE_PITCH] = pitch.coerceIn(0.6f, 1.5f) }
    }

    override suspend fun setWakeWordEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_WORD] = enabled }
    }

    override suspend fun setWakeWordSensitivity(sensitivity: Float) {
        dataStore.edit { it[Keys.WAKE_SENSITIVITY] = sensitivity.coerceIn(0.1f, 1.0f) }
    }

    override suspend fun setWakeWordPaused(paused: Boolean) {
        dataStore.edit { it[Keys.WAKE_PAUSED] = paused }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_START_BOOT] = enabled }
    }

    override suspend fun setSafetyPolicyLevel(level: SafetyPolicyLevel) {
        dataStore.edit { it[Keys.SAFETY_LEVEL] = level.id }
    }

    override suspend fun setFullControlModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FULL_CONTROL] = enabled }
    }

    override suspend fun setSimulateSecureScreen(enabled: Boolean) {
        dataStore.edit { it[Keys.SIMULATE_SECURE_SCREEN] = enabled }
    }

    override suspend fun setProactiveSuggestionsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PROACTIVE_SUGGESTIONS] = enabled }
    }

    override suspend fun setQuietHours(start: String, end: String) {
        dataStore.edit {
            it[Keys.QUIET_START] = start
            it[Keys.QUIET_END] = end
        }
    }

    override suspend fun setAnalyticsOptIn(enabled: Boolean) {
        dataStore.edit { it[Keys.ANALYTICS_OPT_IN] = enabled }
    }

    override suspend fun setShowLatencyDebugPanel(show: Boolean) {
        dataStore.edit { it[Keys.SHOW_LATENCY_PANEL] = show }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = completed }
    }

    override suspend fun resetAllSettings() {
        dataStore.edit { it.clear() }
    }
}
