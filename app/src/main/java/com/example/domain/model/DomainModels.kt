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

data class VoiceDiagnosticsSnapshot(
    val sessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val lastSttErrorCode: Int? = null,
    val lastSttErrorMessage: String = "None",
    val isOfflineTtsActive: Boolean = false,
    val activeVoiceId: String = VoiceProfile.EN_IN_NEURAL2_D.id,
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

enum class VoiceProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val defaultPitch: Float,
    val defaultRate: Float,
    val localeTag: String = "en-IN"
) {
    EN_IN_NEURAL2_D(
        id = "en-IN-Neural2-D",
        displayName = "Aarav · Indian English (Male)",
        subtitle = "Warm, natural Indian English assistant voice",
        defaultPitch = 1.0f,
        defaultRate = 1.0f,
        localeTag = "en-IN"
    ),
    EN_IN_NEURAL2_A(
        id = "en-IN-Neural2-A",
        displayName = "Ananya · Indian English (Female)",
        subtitle = "Clear, expressive Indian English voice",
        defaultPitch = 1.0f,
        defaultRate = 1.0f,
        localeTag = "en-IN"
    ),
    EN_US_STUDIO_O(
        id = "en-US-Studio-O",
        displayName = "Orion · US English (Studio)",
        subtitle = "Crisp studio-grade American English voice",
        defaultPitch = 1.0f,
        defaultRate = 1.0f,
        localeTag = "en-US"
    ),
    EN_GB_NEURAL2_B(
        id = "en-GB-Neural2-B",
        displayName = "Arthur · British English (Neural)",
        subtitle = "Calm, articulate British English voice",
        defaultPitch = 1.0f,
        defaultRate = 1.0f,
        localeTag = "en-GB"
    );

    companion object {
        fun fromId(id: String): VoiceProfile =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: EN_IN_NEURAL2_D
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

data class ChatMessage(
    val id: Long = 0L,
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
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
    val toolCalls: List<ToolCallSpec>
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

data class MemoryFact(
    val id: Long = 0L,
    val category: String,
    val key: String,
    val value: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class UserSettings(
    val language: AppLanguage = AppLanguage.EN_IN,
    val voiceProfile: VoiceProfile = VoiceProfile.EN_IN_NEURAL2_D,
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val wakeWordSensitivity: Float = 0.65f,
    val wakeWordEnabled: Boolean = false,
    val wakeWordPaused: Boolean = false,
    val bootAutoStartEnabled: Boolean = false,
    val floatingOrbEnabled: Boolean = false,
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.STANDARD,
    val killSwitchActive: Boolean = false
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
    val isOptionalInStoreSafeMode: Boolean = false
)

