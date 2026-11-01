package com.example.domain.agent

import com.example.data.remote.GeminiClient
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConversationSummaryItem
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemoryFact
import com.example.domain.model.MemorySource
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.SafetyGate
import com.example.tools.MemorySafetyFilter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first

@Singleton
class MemoryLearner @Inject constructor(
    private val geminiClient: GeminiClient?,
    private val memoryRepository: MemoryRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val moshi: Moshi = Moshi.Builder().build()
) {
    companion object {
        const val MAX_SYSTEM_PROMPT_CHARS = 10_000 // ~2,500 tokens
        const val ROLLING_SUMMARY_THRESHOLD_MESSAGES = 30

        fun buildSystemInstruction(
            pinnedFacts: List<MemoryFact>,
            relevantFacts: List<MemoryFact>,
            recentSummaries: List<ConversationSummaryItem>,
            screenContext: ScreenContextSnapshot?
        ): String {
            val sb = StringBuilder()
            sb.append(
                "You are JARVIS, a concise, capable personal Android assistant. " +
                    "Respond in clear, natural English. Users may speak Indian names or kinship terms " +
                    "(such as Mummy, Papa, Bhai, Didi); understand them naturally. " +
                    "Use the provided tools whenever the user asks to perform an action on the phone " +
                    "(calls, SMS, WhatsApp, alarms, timers, volume, flashlight, opening apps, reading/tapping screen, or saving/searching memory). " +
                    "Never invent phone numbers or fake tool execution.\n"
            )

            val combinedFacts = (pinnedFacts + relevantFacts)
                .distinctBy { "${it.category}:${MemorySafetyFilter.normalizeKey(it.key)}" }

            if (combinedFacts.isNotEmpty()) {
                sb.append("\n[User Memory Facts]\n")
                for (fact in combinedFacts) {
                    val line = "- [${fact.category}] ${fact.key}: ${fact.value}\n"
                    if (sb.length + line.length > MAX_SYSTEM_PROMPT_CHARS - 1800) break
                    sb.append(line)
                }
            }

            if (recentSummaries.isNotEmpty()) {
                sb.append("\n[Recent Conversation Summaries]\n")
                for (summary in recentSummaries.take(2)) {
                    val line = "- ${summary.text.trim()}\n"
                    if (sb.length + line.length > MAX_SYSTEM_PROMPT_CHARS - 800) break
                    sb.append(line)
                }
            }

            if (screenContext != null &&
                !SafetyGate.isSensitiveScreen(screenContext) &&
                screenContext.summaryText.isNotBlank()
            ) {
                val screenBlock = "\n[Active Screen (${screenContext.packageName})]\n" +
                    screenContext.summaryText.take(600) + "\n"
                if (sb.length + screenBlock.length <= MAX_SYSTEM_PROMPT_CHARS) {
                    sb.append(screenBlock)
                }
            }

            return sb.toString().take(MAX_SYSTEM_PROMPT_CHARS)
        }
    }

    private val listMapAdapter = moshi.adapter<List<Map<String, Any?>>>(
        Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
    )

    private var turnCounter = 0

    suspend fun extractFactsFromTurn(
        userText: String,
        assistantReply: String,
        screenContext: ScreenContextSnapshot?
    ): List<String> {
        val learnedKeys = mutableListOf<String>()
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.autoLearnEnabled) return learnedKeys
        val isSensitiveScreen = SafetyGate.isSensitiveScreen(screenContext)
        if (isSensitiveScreen) return learnedKeys

        // Never learn from error turns, tool outputs, or OTP/auth phrases
        val lowerUser = userText.lowercase()
        val lowerReply = assistantReply.lowercase()
        if (lowerUser.contains("otp") || lowerUser.contains("verification code") ||
            lowerUser.contains("password") || lowerUser.contains("pin") ||
            lowerReply.contains("error") || lowerReply.contains("failed to") ||
            lowerUser.startsWith("tool:") || lowerUser.contains("toolresult")
        ) {
            return learnedKeys
        }

        // 1. Deterministic high-confidence extraction for explicit user statements
        extractExplicitHeuristicFacts(userText, isSensitiveScreen)

        // 2. Only run heavier LLM auto-learn on personal statements once every 3 turns
        turnCounter++
        if (turnCounter % 3 != 0) return learnedKeys

        val isPersonalStatement = lowerUser.contains("my ") || lowerUser.contains("i am ") ||
            lowerUser.contains("i live ") || lowerUser.contains("i work ") ||
            lowerUser.contains("i prefer ") || lowerUser.contains("i like ") ||
            lowerUser.contains("remember ") || lowerUser.contains("favourite") || lowerUser.contains("favorite")
        if (!isPersonalStatement) return learnedKeys

        val client = geminiClient ?: return learnedKeys
        if (userText.length < 8) return learnedKeys

        val systemInstruction = """
            Extract only durable personal facts about the user from this conversation turn.
            Output a JSON array of objects with fields:
            [{"category":"PROFILE|CONTACT|PREFERENCE|ROUTINE_HINT|TASK|NOTE|EPISODE","key":"string","value":"string","confidence":0.0-1.0,"importance":1-5}]
            Rules:
            - Only include durable facts with confidence >= 0.7.
            - NEVER include OTPs, verification codes, passwords, PINs, card numbers, or temporary small talk.
            - If there are no durable facts, output [].
        """.trimIndent()

        val prompt = "User: $userText\nAssistant: $assistantReply"
        val rawJson = client.generateStructuredJson(
            modelId = settings.selectedModelId,
            systemInstruction = systemInstruction,
            userPrompt = prompt
        ) ?: return learnedKeys

        val parsedList = runCatching { listMapAdapter.fromJson(rawJson.trim()) }.getOrNull().orEmpty()
        for (item in parsedList) {
            val rawCat = (item["category"] as? String).orEmpty()
            val key = (item["key"] as? String)?.trim().orEmpty()
            val value = (item["value"] as? String)?.trim().orEmpty()
            val confidence = (item["confidence"] as? Number)?.toFloat() ?: 0.8f
            val importance = ((item["importance"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)

            if (key.isEmpty() || value.isEmpty() || confidence < 0.7f) continue
            if (MemorySafetyFilter.isSensitiveOrForbidden(key, value, isSensitiveScreen)) continue

            val category = MemoryCategory.fromString(rawCat).name
            memoryRepository.upsertMemory(
                category = category,
                key = key,
                value = value,
                source = MemorySource.AUTO_LEARNED.name,
                confidence = confidence,
                importance = importance,
                pinned = category == "PROFILE" || category == "CONTACT"
            )
            learnedKeys.add(key)
        }
        return learnedKeys
    }

    internal suspend fun extractExplicitHeuristicFacts(
        userText: String,
        isSensitiveScreen: Boolean = false
    ) {
        if (isSensitiveScreen) return
        val trimmed = userText.trim()

        // Pattern A: "remember (that) X is Y"
        val rememberRegex = Regex("(?i)^remember(?:\\s+that)?\\s+(.+?)\\s+(?:is|=)\\s+(.+)$")
        rememberRegex.find(trimmed)?.let { match ->
            val rawKey = match.groupValues[1].replace(Regex("(?i)^my\\s+"), "").trim()
            val rawVal = match.groupValues[2].trim().trimEnd('.', '!')
            if (!MemorySafetyFilter.isSensitiveOrForbidden(rawKey, rawVal, isSensitiveScreen)) {
                memoryRepository.upsertMemory(
                    category = MemoryCategory.PREFERENCE.name,
                    key = rawKey,
                    value = rawVal,
                    source = MemorySource.AUTO_LEARNED.name,
                    confidence = 0.95f,
                    importance = 4,
                    pinned = false
                )
            }
            return
        }

        // Pattern B: "my favourite/favorite X is Y"
        val favRegex = Regex("(?i)\\bmy\\s+favo(?:u)?rite\\s+([a-z0-9\\s]{2,35}?)\\s+is\\s+([^.,!?]+)")
        favRegex.find(trimmed)?.let { match ->
            val topic = match.groupValues[1].trim()
            val favValue = match.groupValues[2].trim()
            val key = "favorite_$topic"
            if (!MemorySafetyFilter.isSensitiveOrForbidden(key, favValue, isSensitiveScreen)) {
                memoryRepository.upsertMemory(
                    category = MemoryCategory.PREFERENCE.name,
                    key = key,
                    value = favValue,
                    source = MemorySource.AUTO_LEARNED.name,
                    confidence = 0.92f,
                    importance = 4,
                    pinned = false
                )
            }
        }

        // Pattern C: "my name is X" / "call me X"
        val nameRegex = Regex("(?i)^(?:my\\s+name\\s+is|call\\s+me)\\s+([A-Za-z][A-Za-z\\s]{1,30})$")
        nameRegex.find(trimmed)?.let { match ->
            val nameVal = match.groupValues[1].trim()
            if (!MemorySafetyFilter.isSensitiveOrForbidden("user_name", nameVal, isSensitiveScreen)) {
                memoryRepository.upsertMemory(
                    category = MemoryCategory.PROFILE.name,
                    key = "user_name",
                    value = nameVal,
                    source = MemorySource.AUTO_LEARNED.name,
                    confidence = 0.95f,
                    importance = 5,
                    pinned = true
                )
            }
        }
    }

    suspend fun summarizeConversationIfNeeded(conversationId: Long, force: Boolean = false) {
        val messages = chatRepository.getMessagesForConversation(conversationId)
        if (!force && messages.size <= ROLLING_SUMMARY_THRESHOLD_MESSAGES) return
        if (messages.isEmpty()) return

        val existingSummaries = chatRepository.getRecentSummaries(limit = 5)
            .filter { it.conversationId == conversationId }
        val lastSummarizedToId = existingSummaries.maxOfOrNull { it.toMessageId } ?: 0L
        val unsummarized = messages.filter { it.id > lastSummarizedToId }

        if (!force && unsummarized.size < 20) return
        val batch = if (force) unsummarized else unsummarized.dropLast(6).ifEmpty { unsummarized }
        if (batch.isEmpty()) return

        val fromId = batch.first().id
        val toId = batch.last().id

        val settings = settingsRepository.settingsFlow.first()
        val transcript = batch.joinToString("\n") { "${it.role.name}: ${it.content.take(160)}" }
        val llmSummary = geminiClient?.generateStructuredJson(
            modelId = settings.selectedModelId,
            systemInstruction = "Summarize this conversation segment in 2-3 concise sentences focusing on user requests, decisions, and outcomes. Output plain text or {\"summary\":\"...\"}.",
            userPrompt = transcript
        )?.let { raw ->
            raw.removePrefix("{\"summary\":\"").removeSuffix("\"}").trim()
        }

        val summaryText = if (!llmSummary.isNullOrBlank()) {
            llmSummary
        } else {
            val userTopics = batch.filter { it.role.name == "USER" }.take(3).joinToString("; ") { it.content.take(60) }
            val assistantTopics = batch.filter { it.role.name == "ASSISTANT" }.takeLast(2).joinToString("; ") { it.content.take(60) }
            "Discussed: $userTopics. Outcomes: $assistantTopics."
        }

        chatRepository.saveSummary(
            ConversationSummaryItem(
                conversationId = conversationId,
                fromMessageId = fromId,
                toMessageId = toId,
                text = summaryText
            )
        )
    }
}
