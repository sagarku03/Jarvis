package com.example.data.remote

import com.example.di.Inject
import com.example.di.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/**
 * Debug/offline scripted backend implementing [ApiService] so that all 20 phone tools
 * and agent flows can be tested deterministically when no cloud backend URL is configured.
 */
@Singleton
class FakeBackend @Inject constructor() : ApiService {

    override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
        val lastMessage = request.messages.lastOrNull()
            ?: return AgentStepResponseDto(
                assistantText = "Hi, I'm Jarvis. How can I help?",
                toolCalls = emptyList()
            )

        // If the last message was a TOOL result, summarize and complete the turn
        if (lastMessage.role.equals("TOOL", ignoreCase = true)) {
            return AgentStepResponseDto(
                assistantText = "Done: ${lastMessage.content}",
                toolCalls = emptyList()
            )
        }

        val userText = lastMessage.content.trim()
        val lower = userText.lowercase()

        // 1. WhatsApp / Telegram message intent
        if (lower.contains("whatsapp") || lower.contains("telegram")) {
            val appType = if (lower.contains("telegram")) "telegram" else "whatsapp"
            val contact = extractContactFromCommand(userText)
            val body = extractMessageBody(userText)
            return AgentStepResponseDto(
                assistantText = "Preparing $appType message to $contact.",
                toolCalls = listOf(
                    ToolCallDto(
                        id = callId(),
                        name = "send_whatsapp_or_telegram",
                        args = mapOf(
                            "app" to appType,
                            "contact_name" to contact,
                            "message" to body
                        )
                    )
                )
            )
        }

        // 2. Phone Call intent
        if (lower.contains("call ") || lower.startsWith("call") || lower.contains("phone ") || lower.contains("dial ")) {
            val contact = extractContactFromCommand(userText)
            return AgentStepDecisionForTool(
                assistantText = "Preparing a phone call to $contact.",
                toolName = "call_contact",
                args = mapOf("contact_name" to contact)
            )
        }

        // 3. SMS intent
        if (lower.contains("sms") || lower.contains("text ") || lower.contains("message ")) {
            val contact = extractContactFromCommand(userText)
            val body = extractMessageBody(userText)
            return AgentStepDecisionForTool(
                assistantText = "Drafting an SMS to $contact.",
                toolName = "send_sms",
                args = mapOf(
                    "contact_name" to contact,
                    "message" to body
                )
            )
        }

        // 4. Alarm intent
        if (lower.contains("alarm") || lower.contains("wake me")) {
            val (hour, minute) = extractTimeFromCommand(lower)
            val timeFormatted = String.format("%02d:%02d", hour, minute)
            return AgentStepDecisionForTool(
                assistantText = "Setting an alarm for $timeFormatted.",
                toolName = "set_alarm",
                args = mapOf(
                    "hour" to hour.toString(),
                    "minute" to minute.toString(),
                    "label" to "JARVIS Voice Alarm"
                )
            )
        }

        // 5. Timer intent
        if (lower.contains("timer")) {
            val seconds = extractSecondsFromCommand(lower)
            return AgentStepDecisionForTool(
                assistantText = "Starting a timer for $seconds seconds.",
                toolName = "set_timer",
                args = mapOf(
                    "seconds" to seconds.toString(),
                    "label" to "JARVIS Timer"
                )
            )
        }

        // 6. Flashlight / Torch intent
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off") || lower.contains("disable")
            val stateStr = if (turnOff) "false" else "true"
            return AgentStepDecisionForTool(
                assistantText = if (turnOff) "Turning flashlight off." else "Turning flashlight on.",
                toolName = "toggle_flashlight",
                args = mapOf("enabled" to stateStr)
            )
        }

        // 7. Volume control intent
        if (lower.contains("volume") || lower.contains("sound")) {
            val level = extractPercentage(lower) ?: if (lower.contains("max") || lower.contains("full")) 90 else 50
            return AgentStepDecisionForTool(
                assistantText = "Adjusting media volume to $level%.",
                toolName = "adjust_volume",
                args = mapOf(
                    "stream" to "music",
                    "level_percent" to level.toString()
                )
            )
        }

        // 8. Play YouTube / Spotify / Media
        if (lower.contains("play ") || lower.contains("youtube") || lower.contains("song") || lower.contains("music")) {
            val query = userText
                .replace(Regex("(?i)(play|on youtube|youtube|music|song)"), "")
                .trim()
                .ifEmpty { "lofi chill beats" }
            return AgentStepDecisionForTool(
                assistantText = "Playing '$query' on YouTube.",
                toolName = "play_media",
                args = mapOf("query" to query)
            )
        }

        // 9. Maps Navigation
        if (lower.contains("navigate") || lower.contains("directions to") || lower.contains("map") || lower.contains("route to")) {
            val destination = userText
                .replace(Regex("(?i)(navigate to|directions to|route to|open maps for|map)"), "")
                .trim()
                .ifEmpty { "Connaught Place New Delhi" }
            return AgentStepDecisionForTool(
                assistantText = "Starting Google Maps navigation to $destination.",
                toolName = "navigate_maps",
                args = mapOf("destination" to destination)
            )
        }

        // 10. Multi-step Open App & Search inside it
        if (lower.contains("open ") && (lower.contains(" and search ") || lower.contains(" and find "))) {
            val cleaned = userText.replace(Regex("(?i)^open\\s+"), "").trim()
            val parts = cleaned.split(Regex("(?i)\\s+and\\s+(search|find)\\s+"), limit = 2)
            val appName = parts.firstOrNull()?.trim().orEmpty().ifEmpty { "YouTube" }
            val query = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { "news" }
            return AgentStepDecisionForTool(
                assistantText = "Opening $appName and searching for '$query'.",
                toolName = "open_app_and_find",
                args = mapOf(
                    "app_name" to appName,
                    "search_query" to query
                )
            )
        }

        // 11. Open App intent
        if (lower.startsWith("open ") || lower.contains("launch ")) {
            val appName = userText
                .replace(Regex("(?i)(open|launch|app)"), "")
                .trim()
                .ifEmpty { "Settings" }
            return AgentStepDecisionForTool(
                assistantText = "Opening $appName.",
                toolName = "open_app",
                args = mapOf("app_name" to appName)
            )
        }

        // 12. Notification Read / Reply / Dismiss intent
        if (lower.contains("notification") || lower.contains("unread")) {
            val action = when {
                lower.contains("reply") -> "reply"
                lower.contains("dismiss") || lower.contains("clear") -> "dismiss"
                else -> "list"
            }
            return AgentStepDecisionForTool(
                assistantText = "Checking notifications ($action).",
                toolName = "notification_action",
                args = mapOf(
                    "action" to action,
                    "target" to extractContactFromCommand(userText),
                    "reply_text" to extractMessageBody(userText)
                )
            )
        }

        // 13. Read Screen intent
        if (lower.contains("read screen") || lower.contains("what is on my screen") || lower.contains("what's on my screen")) {
            return AgentStepDecisionForTool(
                assistantText = "Reading the current screen layout.",
                toolName = "read_screen",
                args = emptyMap()
            )
        }

        // 14. Tap Element intent
        if (lower.startsWith("tap ") || lower.startsWith("click ")) {
            val target = userText.replace(Regex("(?i)^(tap|click)\\s+(on\\s+)?"), "").trim()
            return AgentStepDecisionForTool(
                assistantText = "Tapping '$target' on screen.",
                toolName = "tap_element",
                args = mapOf("target_text" to target)
            )
        }

        // 15. Type Text intent
        if (lower.startsWith("type ") || lower.startsWith("enter text ")) {
            val textToType = userText.replace(Regex("(?i)^(type|enter text)\\s+"), "").trim()
            return AgentStepDecisionForTool(
                assistantText = "Typing '$textToType' into the focused field.",
                toolName = "type_text",
                args = mapOf("text" to textToType)
            )
        }

        // 16. Scroll Screen intent
        if (lower.contains("scroll down") || lower.contains("scroll up") || lower.contains("swipe ")) {
            val dir = when {
                lower.contains("up") -> "up"
                lower.contains("left") -> "left"
                lower.contains("right") -> "right"
                else -> "down"
            }
            return AgentStepDecisionForTool(
                assistantText = "Scrolling $dir.",
                toolName = "scroll_screen",
                args = mapOf("direction" to dir)
            )
        }

        // 17. Wait for Element intent
        if (lower.startsWith("wait for ")) {
            val target = userText.replace(Regex("(?i)^wait for\\s+"), "").trim()
            return AgentStepDecisionForTool(
                assistantText = "Waiting for '$target' to appear on screen.",
                toolName = "wait_for_element",
                args = mapOf("target_text" to target, "timeout_ms" to "4000")
            )
        }

        // 18. System Global Navigation
        if (lower == "go back" || lower == "go home" || lower == "open notifications" || lower == "open quick settings" || lower == "show recents") {
            val sysAction = when {
                lower.contains("home") -> "home"
                lower.contains("notification") -> "notifications"
                lower.contains("quick") -> "quick_settings"
                lower.contains("recent") -> "recents"
                else -> "back"
            }
            return AgentStepDecisionForTool(
                assistantText = "Executing system action: $sysAction.",
                toolName = "system_navigation",
                args = mapOf("action" to sysAction)
            )
        }

        // 19. Open URL intent
        if (lower.contains(".com") || lower.contains(".org") || lower.contains(".in") || lower.startsWith("https://")) {
            val urlToken = userText.split(" ").firstOrNull {
                it.contains(".com") || it.contains(".org") || it.contains(".in") || it.startsWith("http")
            } ?: "https://www.google.com"
            return AgentStepDecisionForTool(
                assistantText = "Opening $urlToken in your browser.",
                toolName = "open_url",
                args = mapOf("url" to urlToken)
            )
        }

        // 20. Web Search intent
        if (lower.startsWith("search ") || lower.contains("google ")) {
            val query = userText
                .replace(Regex("(?i)^(search for|search|google)\\s+"), "")
                .trim()
            return AgentStepDecisionForTool(
                assistantText = "Searching the web for '$query'.",
                toolName = "web_search",
                args = mapOf("query" to query)
            )
        }

        // Default conversational response
        return AgentStepResponseDto(
            assistantText = "I heard: \"$userText\". I can open apps, place calls, send SMS or WhatsApp messages, set alarms and timers, adjust volume, toggle the flashlight, or read the screen.",
            toolCalls = emptyList()
        )
    }

    override suspend fun speechToText(request: SttRequestDto): SttResponseDto {
        return SttResponseDto(
            transcript = "Open YouTube",
            confidence = 0.96f,
            isFinal = true
        )
    }

    override suspend fun synthesizeSpeech(request: TtsRequestDto): ResponseBody {
        // Return empty body so AndroidTtsEngineWrapper cleanly uses high-quality local TTS and shows "Offline voice" chip
        return ByteArray(0).toResponseBody("audio/mpeg".toMediaType())
    }

    private fun AgentStepDecisionForTool(
        assistantText: String,
        toolName: String,
        args: Map<String, String>
    ): AgentStepResponseDto {
        return AgentStepResponseDto(
            assistantText = assistantText,
            toolCalls = listOf(
                ToolCallDto(
                    id = callId(),
                    name = toolName,
                    args = args
                )
            )
        )
    }

    private fun callId(): String = "call_${UUID.randomUUID().toString().take(8)}"

    private fun extractContactFromCommand(text: String): String {
        val knownNames = listOf("Mom", "Mummy", "Dad", "Papa", "Rahul", "Priya", "Amit", "Rohit", "Neha")
        for (name in knownNames) {
            if (text.contains(name, ignoreCase = true)) return name
        }
        val words = text.trim().split(Regex("\\s+"))
        val afterKeyword = words.dropWhile {
            !it.equals("call", ignoreCase = true) &&
                !it.equals("to", ignoreCase = true) &&
                !it.equals("text", ignoreCase = true)
        }.drop(1)
        return afterKeyword.firstOrNull()?.replace(Regex("[^A-Za-z0-9+]"), "")?.ifEmpty { "Mom" } ?: "Mom"
    }

    private fun extractMessageBody(text: String): String {
        val markers = listOf("saying ", "that ", ": ")
        for (marker in markers) {
            val idx = text.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val extracted = text.substring(idx + marker.length).trim()
                if (extracted.isNotEmpty()) return extracted
            }
        }
        return "I will reach in 10 minutes."
    }

    private fun extractTimeFromCommand(lower: String): Pair<Int, Int> {
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val match = timeRegex.find(lower)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 7
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val amPm = match.groupValues[3]
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
        }
        return 7 to 0
    }

    private fun extractSecondsFromCommand(lower: String): Int {
        val numRegex = Regex("(\\d+)")
        val value = numRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        return when {
            lower.contains("hour") -> value * 3600
            lower.contains("sec") -> value
            else -> value * 60
        }
    }

    private fun extractPercentage(lower: String): Int? {
        val pctRegex = Regex("(\\d{1,3})")
        val v = pctRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return v.coerceIn(0, 100)
    }
}
