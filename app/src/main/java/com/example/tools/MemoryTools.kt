package com.example.tools

import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemorySource
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.tool.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MemorySafetyFilter {
    private val SENSITIVE_KEY_WORDS = listOf(
        "otp", "one time password", "verification code", "password", "passcode",
        "upi pin", "mpin", "atm pin", "cvv", "cvc", "aadhaar", "pan card", "credit card", "debit card"
    )

    private val APP_META_BLOCKLIST = listOf(
        "key rejected", "gemini_api_key", "api key", "gemini", "token", "quota",
        "rate limit", "401", "403", "404", "429", "500", "503", "bad request",
        "system instruction", "functioncall", "functionresponse", "accessibility disabled",
        "something went wrong", "google's ai is busy"
    )

    private val OTP_PATTERN = Regex("\\b(?:otp|code|pin)\\b[^0-9]{0,20}\\b\\d{4,8}\\b", RegexOption.IGNORE_CASE)
    private val CARD_NUMBER_PATTERN = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val AADHAAR_PATTERN = Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b")
    private val PAN_PATTERN = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")

    /**
     * Normalises keys so synonyms map to a canonical key:
     * e.g., "favourite IPL team" / "favorite ipl team" -> "favorite_ipl_team"
     * "Mummy" / "Mom" -> "mom"
     */
    fun normalizeKey(rawKey: String): String {
        val cleaned = rawKey.trim()
            .lowercase(Locale.US)
            .replace("favourite", "favorite")
            .replace("colour", "color")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return when (cleaned) {
            "mummy", "maa", "mother", "ammi", "aai" -> "mom"
            "papa", "father", "pitaji", "abba" -> "dad"
            else -> cleaned.ifEmpty { "note" }
        }
    }

    /**
     * Never save OTPs, passwords, PINs, card numbers, Aadhaar/PAN, or app metadata.
     */
    fun isSensitiveOrForbidden(key: String, value: String, isSensitiveScreenActive: Boolean = false): Boolean {
        if (isSensitiveScreenActive) return true
        val combined = "${key.replace('_', ' ')} ${value.replace('_', ' ')}".lowercase(Locale.US)
        if (APP_META_BLOCKLIST.any { combined.contains(it) }) return true
        if (SENSITIVE_KEY_WORDS.any { combined.contains(it) }) return true
        if (OTP_PATTERN.containsMatchIn(combined)) return true
        if (AADHAAR_PATTERN.containsMatchIn(value)) return true
        if (PAN_PATTERN.containsMatchIn(value)) return true

        // Check for 13-19 digit card numbers while allowing 10-12 digit phone numbers (e.g. +919876543210)
        val digitsOnly = value.filter { it.isDigit() }
        if (digitsOnly.length in 13..19 && CARD_NUMBER_PATTERN.containsMatchIn(value)) {
            return true
        }
        return false
    }

    fun validateFact(key: String, value: String, isSensitiveScreenActive: Boolean = false): String? {
        if (key.isBlank() || value.isBlank()) {
            return "Key and value cannot be empty."
        }
        if (isSensitiveOrForbidden(key, value, isSensitiveScreenActive)) {
            return "Refused to save sensitive data (OTPs, passwords, PINs, or card numbers are never stored)."
        }
        return null
    }
}

class SaveMemoryTool(
    private val memoryRepository: MemoryRepository
) : Tool {
    override val name: String = "save_memory"
    override val description: String =
        "Saves a durable fact about the user (PROFILE, CONTACT, PREFERENCE, ROUTINE_HINT, TASK, NOTE, EPISODE)."
    override val jsonSchema: String = """
        {
          "type": "object",
          "properties": {
            "category": {
              "type": "string",
              "description": "Category: PROFILE, CONTACT, PREFERENCE, ROUTINE_HINT, TASK, NOTE, or EPISODE"
            },
            "key": {
              "type": "string",
              "description": "Short key name for the fact, e.g. 'favorite_ipl_team' or 'Mom'"
            },
            "value": {
              "type": "string",
              "description": "Fact value to remember, e.g. 'CSK' or '+919876543210'"
            },
            "importance": {
              "type": "string",
              "description": "Importance from 1 to 5 (default 3)"
            }
          },
          "required": ["key", "value"]
        }
    """.trimIndent()
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawCategory = args["category"].orEmpty().ifBlank { "PREFERENCE" }
        val category = MemoryCategory.fromString(rawCategory).name
        val key = args["key"].orEmpty().trim()
        val value = args["value"].orEmpty().trim()
        val importance = args["importance"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3

        if (key.isEmpty() || value.isEmpty()) {
            return ToolExecutionResult(
                name,
                false,
                "Both 'key' and 'value' are required to save a memory.",
                "मेमोरी में सहेजने के लिए 'key' और 'value' दोनों आवश्यक हैं।"
            )
        }
        if (MemorySafetyFilter.isSensitiveOrForbidden(key, value)) {
            return ToolExecutionResult(
                name,
                false,
                "Refused to save sensitive data (passwords, PINs, OTPs, or card numbers are never stored).",
                "संवेदनशील डेटा सहेजने से इनकार (पासवर्ड, पिन, ओटीपी या कार्ड नंबर कभी संग्रहीत नहीं किए जाते)।"
            )
        }

        val id = memoryRepository.upsertMemory(
            category = category,
            key = key,
            value = value,
            source = MemorySource.USER_EXPLICIT.name,
            confidence = 1.0f,
            importance = importance,
            pinned = category == "PROFILE" || category == "CONTACT"
        )
        return if (id > 0L) {
            ToolExecutionResult(
                name,
                true,
                "Saved to memory: [$category] $key = $value",
                "मेमोरी में सहेजा गया: [$category] $key = $value"
            )
        } else {
            ToolExecutionResult(
                name,
                false,
                "Could not save memory fact.",
                "मेमोरी तथ्य सहेजने में विफल।"
            )
        }
    }
}

class ForgetMemoryTool(
    private val memoryRepository: MemoryRepository
) : Tool {
    override val name: String = "forget_memory"
    override val description: String =
        "Deletes saved memory facts matching the given key or search query after user confirmation."
    override val jsonSchema: String = """
        {
          "type": "object",
          "properties": {
            "key_or_query": {
              "type": "string",
              "description": "The key or topic to forget from memory"
            }
          },
          "required": ["key_or_query"]
        }
    """.trimIndent()
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = (args["key_or_query"] ?: args["key"] ?: args["query"]).orEmpty().trim()
        if (query.isEmpty()) {
            return ToolExecutionResult(
                name,
                false,
                "Please specify which memory key or topic to forget.",
                "कृपया बताएं कि मेमोरी से कौन सी 'key' या विषय भुलाना है।"
            )
        }
        val deletedCount = memoryRepository.deleteMemoriesByKeyOrQuery(query)
        return if (deletedCount > 0) {
            ToolExecutionResult(
                name,
                true,
                "Forgot $deletedCount memory fact(s) matching '$query'.",
                "'$query' से मेल खाते $deletedCount तथ्य भुला दिए गए।"
            )
        } else {
            ToolExecutionResult(
                name,
                false,
                "No saved memory facts matched '$query'.",
                "'$query' से मेल खाता कोई सहेजा गया तथ्य नहीं मिला।"
            )
        }
    }
}

class SearchMemoryTool(
    private val memoryRepository: MemoryRepository
) : Tool {
    override val name: String = "search_memory"
    override val description: String = "Searches saved user facts and memories by keyword."
    override val jsonSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Keyword or phrase to search in saved memories"
            }
          },
          "required": ["query"]
        }
    """.trimIndent()
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"].orEmpty().trim()
        val matches = if (query.isEmpty()) {
            memoryRepository.getAllMemoriesSnapshot().take(10)
        } else {
            memoryRepository.searchTopFactsForContext(query, limit = 10)
        }
        if (matches.isEmpty()) {
            return ToolExecutionResult(
                name,
                true,
                "No matching facts found in memory for '$query'.",
                "मेमोरी में '$query' से संबंधित कोई तथ्य नहीं मिला।"
            )
        }
        val formatted = matches.joinToString("; ") { "[${it.category}] ${it.key} = ${it.value}" }
        return ToolExecutionResult(
            name,
            true,
            "Found ${matches.size} fact(s): $formatted",
            "${matches.size} तथ्य मिले: $formatted"
        )
    }
}

class SearchHistoryTool(
    private val chatRepository: ChatRepository
) : Tool {
    override val name: String = "search_history"
    override val description: String = "Searches past conversation messages and summaries for a keyword or topic."
    override val jsonSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Keyword or topic to search in conversation history"
            },
            "date_range": {
              "type": "string",
              "description": "Optional date filter or timeframe"
            }
          },
          "required": ["query"]
        }
    """.trimIndent()
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"].orEmpty().trim()
        if (query.isEmpty()) {
            return ToolExecutionResult(
                name,
                false,
                "Please provide a search query for conversation history.",
                "बातचीत के इतिहास को खोजने के लिए कृपया 'query' प्रदान करें।"
            )
        }
        val matches = chatRepository.searchMessages(query)
        if (matches.isEmpty()) {
            return ToolExecutionResult(
                name,
                true,
                "No past messages matched '$query'.",
                "'$query' से मेल खाता कोई पिछला संदेश नहीं मिला।"
            )
        }
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        val snippet = matches.take(8).joinToString(" | ") { msg ->
            "${fmt.format(Date(msg.timestampMs))} [${msg.role.name}]: ${msg.content.take(90)}"
        }
        return ToolExecutionResult(
            name,
            true,
            "Past conversation matches: $snippet",
            "पिछली बातचीत के परिणाम: $snippet"
        )
    }
}
