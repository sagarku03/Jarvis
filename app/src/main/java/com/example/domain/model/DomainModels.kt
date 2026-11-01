package com.example.domain.model

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class AssistantOrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    PAUSED_KILLSWITCH
}

/**
 * Single source of truth for the half-duplex voice session state machine.
 * Microphone is NEVER open while state == THINKING or SPEAKING.
 */
enum class VoiceSessionState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

data class DeviceVoiceOption(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val quality: Int,
    val isOffline: Boolean
)

data class VoiceDiagnosticsSnapshot(
    val sessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val lastSttErrorCode: Int? = null,
    val lastSttErrorMessage: String = "None",
    val isOfflineTtsActive: Boolean = true,
    val activeVoiceId: String = "default",
    val sttLatencyMs: Long = 0L,
    val agentLatencyMs: Long = 0L,
    val ttsLatencyMs: Long = 0L
)

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val speechLocale: String
) {
    EN_IN(
        code = "en-IN",
        displayName = "English (India)",
        speechLocale = "en-IN"
    ),
    EN_US(
        code = "en-US",
        displayName = "English (US)",
        speechLocale = "en-US"
    ),
    EN_GB(
        code = "en-GB",
        displayName = "English (UK)",
        speechLocale = "en-GB"
    );

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN_IN
    }
}

enum class ConfirmationLevel(
    val code: String,
    val labelEn: String
) {
    STANDARD(
        code = "STANDARD",
        labelEn = "Standard (Auto LOW, confirm MED/HIGH)"
    ),
    STRICT(
        code = "STRICT",
        labelEn = "Strict (Confirm every phone action)"
    );

    companion object {
        fun fromCode(code: String): ConfirmationLevel =
            entries.firstOrNull { it.code == code } ?: STANDARD
    }
}

data class ConversationItem(
    val id: Long = 0L,
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long? = null,
    val title: String = "New Conversation",
    val summary: String? = null
)

data class ConversationSummaryItem(
    val id: Long = 0L,
    val conversationId: Long,
    val fromMessageId: Long,
    val toMessageId: Long,
    val text: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: Long = 0L,
    val conversationId: Long = 1L,
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val rawModelPartsJson: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM
}

data class ToolCallSpec(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val outputEn: String,
    val outputHi: String = outputEn,
    val requiresFollowUpScreenRead: Boolean = false
)

data class AgentStepDecision(
    val assistantText: String?,
    val toolCalls: List<ToolCallSpec>,
    val keyRejected: Boolean = false
)

data class ConfirmationRequest(
    val id: String,
    val toolCall: ToolCallSpec,
    val riskLevel: RiskLevel,
    val summaryEn: String,
    val summaryHi: String = summaryEn,
    val countdownSeconds: Int = 15,
    val candidateContacts: List<ContactCandidate> = emptyList()
)

data class ContactCandidate(
    val displayName: String,
    val phoneNumber: String,
    val maskedNumber: String
)

data class ActivityLogEntry(
    val id: Long = 0L,
    val toolName: String,
    val riskLevel: RiskLevel,
    val argumentsSummary: String,
    val resultSummary: String,
    val userConfirmed: Boolean,
    val durationMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class MemoryCategory {
    PROFILE,
    CONTACT,
    PREFERENCE,
    ROUTINE_HINT,
    TASK,
    NOTE,
    EPISODE;

    companion object {
        fun fromString(raw: String): MemoryCategory {
            val clean = raw.trim().uppercase()
            return entries.firstOrNull { it.name == clean } ?: when {
                clean.contains("CONTACT") -> CONTACT
                clean.contains("PROFILE") || clean.contains("NAME") || clean.contains("CITY") -> PROFILE
                clean.contains("PREF") -> PREFERENCE
                clean.contains("ROUTINE") -> ROUTINE_HINT
                clean.contains("TASK") || clean.contains("REMINDER") -> TASK
                clean.contains("EPISODE") -> EPISODE
                else -> NOTE
            }
        }
    }
}

enum class MemorySource {
    USER_EXPLICIT,
    AUTO_LEARNED,
    SETUP_WIZARD,
    TOOL_RESULT;

    companion object {
        fun fromString(raw: String): MemorySource =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: USER_EXPLICIT
    }
}

data class MemoryFact(
    val id: Long = 0L,
    val category: String,
    val key: String,
    val value: String,
    val source: String = MemorySource.USER_EXPLICIT.name,
    val confidence: Float = 1.0f,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val lastUsedAtMs: Long = System.currentTimeMillis(),
    val useCount: Int = 0,
    val supersededById: Long? = null
) {
    fun readableSentence(): String {
        val cleanVal = value.trim()
        val cleanKey = key.trim().lowercase()

        if (cleanVal.length > 18 && (cleanVal.contains(" is ") || cleanVal.contains(" are ") || cleanVal.contains(" prefers ") || cleanVal.contains(" lives "))) {
            val capitalized = cleanVal.replaceFirstChar { it.uppercase() }
            return if (capitalized.endsWith(".")) capitalized else "$capitalized."
        }

        return when {
            cleanKey == "user_name" || cleanKey == "name" -> "Your name is $cleanVal."
            cleanKey == "home_city" || cleanKey == "city" -> "You live in $cleanVal."
            cleanKey.startsWith("contact_") || cleanKey.contains("phone") -> {
                val who = cleanKey.removePrefix("contact_").replace("_", " ").trim()
                if (who.isNotEmpty()) "${who.replaceFirstChar { it.uppercase() }}'s contact is $cleanVal."
                else "Contact number is $cleanVal."
            }
            cleanKey.contains("favorite") || cleanKey.contains("favourite") -> {
                val what = cleanKey.replace("_", " ")
                "Your $what is $cleanVal."
            }
            else -> {
                val humanKey = cleanKey.replace("_", " ").trim()
                if (humanKey.isNotEmpty() && cleanVal.isNotEmpty()) {
                    "${humanKey.replaceFirstChar { it.uppercase() }}: $cleanVal"
                } else {
                    cleanVal
                }
            }
        }
    }
}

enum class VoiceEngineOption(val code: String, val labelEn: String) {
    NATURAL_GOOGLE("NATURAL_GOOGLE", "Natural (Google)"),
    PHONE_VOICE("PHONE_VOICE", "Phone voice");

    companion object {
        fun fromCode(code: String): VoiceEngineOption =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: NATURAL_GOOGLE
    }
}

enum class GeminiVoice(
    val voiceName: String,
    val gender: String,
    val description: String,
    val displayName: String = "$voiceName ($description)"
) {
    // Male voices
    PUCK("Puck", "MALE", "Dynamic"),
    CHARON("Charon", "MALE", "Deep"),
    FENRIR("Fenrir", "MALE", "Authoritative"),

    // Female voices
    AOEDE("Aoede", "FEMALE", "Warm"),
    KORE("Kore", "FEMALE", "Calm"),
    LEDA("Leda", "FEMALE", "Clear");

    companion object {
        fun fromVoiceName(name: String): GeminiVoice =
            entries.firstOrNull { it.voiceName.equals(name.trim(), ignoreCase = true) } ?: PUCK

        fun getVoicesForGender(gender: String): List<GeminiVoice> {
            val clean = gender.trim().uppercase()
            return entries.filter { it.gender.equals(clean, ignoreCase = true) }
        }

        val MALE_VOICES = entries.filter { it.gender == "MALE" }
        val FEMALE_VOICES = entries.filter { it.gender == "FEMALE" }
    }
}

data class VoiceEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val type: String,
    val message: String,
    val latencyMs: Long = 0L
)

data class UserFacingError(
    val message: String,
    val actionLabel: String = "Try again",
    val isOpenSettings: Boolean = false
)

data class UserSettings(
    val setupComplete: Boolean = false,
    val selectedModelId: String = "gemini-3.8-flash",
    val selectedTextModelId: String = "gemini-3.8-flash",
    val selectedVoiceModelId: String = "gemini-3.8-live",
    val selectedVoiceGender: String = "MALE",
    val selectedLiveVoiceName: String = "Puck",
    val availableModels: List<String> = listOf("gemini-3.8-flash", "gemini-3.8-live"),
    val language: AppLanguage = AppLanguage.EN_IN,
    val voiceEngine: VoiceEngineOption = VoiceEngineOption.NATURAL_GOOGLE,
    val selectedVoiceName: String = "",
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val floatingOrbEnabled: Boolean = false,
    val wakePhraseEnabled: Boolean = false,
    val wakePhraseScreenOnOnly: Boolean = true,
    val wakePhraseStopAfter30m: Boolean = true,
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.STANDARD,
    val killSwitchActive: Boolean = false,
    val autoLearnEnabled: Boolean = true,
    val askBeforeSavingFact: Boolean = false,
    val requestsToday: Int = 0,
    val tokensToday: Int = 0,
    val totalRequests: Int = 0,
    val totalTokens: Int = 0,
    val timeToFirstTokenMs: Long = 0L,
    val timeToFirstWordMs: Long = 0L,
    val usageDayEpoch: Long = 0L
)

data class ScreenNodeSnapshot(
    val id: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean
)

data class ScreenContextSnapshot(
    val packageName: String,
    val summaryText: String = "",
    val activityName: String = "",
    val isSecureScreen: Boolean = false,
    val rawVisibleTextNodes: List<String> = emptyList(),
    val structuredNodes: List<ScreenNodeSnapshot> = emptyList(),
    val structuredJson: String = "[]",
    val compactJson: String = structuredJson,
    val screenHash: String = "",
    val isSensitiveAppBlocked: Boolean = isSecureScreen,
    val capturedAtMs: Long = System.currentTimeMillis()
)

data class LatencyMetrics(
    val sttLatencyMs: Long = 0L,
    val agentStepLatencyMs: Long = 0L,
    val ttsFirstAudioLatencyMs: Long = 0L,
    val endToFirstAudioMs: Long = 0L,
    val usingStreamingBackend: Boolean = false
)

enum class RoutineTriggerType(
    val code: String,
    val labelEn: String
) {
    VOICE_PHRASE("VOICE_PHRASE", "Voice Phrase"),
    TIME_DAILY("TIME_DAILY", "Daily Schedule (HH:mm)"),
    HEADSET_CONNECTED("HEADSET_CONNECTED", "Headset / Bluetooth Connected"),
    CHARGING_STARTED("CHARGING_STARTED", "Charging Connected");

    companion object {
        fun fromCode(code: String): RoutineTriggerType =
            entries.firstOrNull { it.code == code } ?: VOICE_PHRASE
    }
}

data class RoutineItem(
    val id: Long = 0L,
    val title: String,
    val triggerType: RoutineTriggerType,
    val triggerValue: String,
    val actionsJson: List<String>,
    val enabled: Boolean = true,
    val lastRunAtMs: Long? = null
)

data class OemBatteryGuide(
    val oemName: String,
    val stepsEn: String
)

data class NotificationItemSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val hasReplyAction: Boolean,
    val isOtpRedacted: Boolean,
    val postedAtMs: Long
)

data class PermissionItemStatus(
    val id: String,
    val titleEn: String,
    val rationaleEn: String,
    val isGranted: Boolean,
    val isRequiredForSetup: Boolean = false,
    val isOptionalInStoreSafeMode: Boolean = false
)
