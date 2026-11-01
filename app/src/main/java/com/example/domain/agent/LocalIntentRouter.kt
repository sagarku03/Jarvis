package com.example.domain.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class LocalRouteResult {
    data class ToolAction(
        val toolName: String,
        val args: Map<String, String>,
        val templateReply: String
    ) : LocalRouteResult()

    data class DirectReply(
        val reply: String
    ) : LocalRouteResult()

    object Stop : LocalRouteResult()
}

object LocalIntentRouter {

    private val STOP_REGEX = Regex("""^(stop|ruk jao|bas|cancel|pause)\b""", RegexOption.IGNORE_CASE)
    private val TIME_REGEX = Regex("""\b(what time is it|what's the time|current time|time kya hua|time batao|kitne baje|baje hain)\b""", RegexOption.IGNORE_CASE)
    private val BATTERY_REGEX = Regex("""\b(battery|battery level|battery percentage|battery kitni hai|battery status|kitni battery)\b""", RegexOption.IGNORE_CASE)

    private val FLASHLIGHT_ON_REGEX = Regex("""\b(torch|flashlight)\s*(on|chalu|jalao|start)\b|\b(turn on|switch on|jala do|chalu karo)\s*(torch|flashlight)\b""", RegexOption.IGNORE_CASE)
    private val FLASHLIGHT_OFF_REGEX = Regex("""\b(torch|flashlight)\s*(off|band|bujhao|stop)\b|\b(turn off|switch off|band karo|bujha do)\s*(torch|flashlight)\b""", RegexOption.IGNORE_CASE)

    private val VOL_UP_REGEX = Regex("""\b(volume|sound|awaz)\s*(badhao|up|increase|tez karo)\b|\b(increase|raise)\s*(volume|sound)\b""", RegexOption.IGNORE_CASE)
    private val VOL_DOWN_REGEX = Regex("""\b(volume|sound|awaz)\s*(kam karo|down|decrease|ghatao)\b|\b(decrease|lower)\s*(volume|sound)\b""", RegexOption.IGNORE_CASE)
    private val VOL_MUTE_REGEX = Regex("""\b(mute|silent|chup|awaz band)\b""", RegexOption.IGNORE_CASE)
    private val VOL_SET_REGEX = Regex("""\b(set volume to|volume)\s*(\d{1,3})\s*(%|percent)?\b""", RegexOption.IGNORE_CASE)

    private val YOUTUBE_PLAY_REGEX = Regex("""\b(?:play\s+(.+?)\s+on\s+youtube|youtube\s+pe\s+(.+?)\s+chalao|play\s+(.+?)\s+on\s+yt)\b""", RegexOption.IGNORE_CASE)
    private val NAV_REGEX = Regex("""\b(?:navigate to|directions to|rasta dikhao|take me to)\s+(.+)\b""", RegexOption.IGNORE_CASE)

    private val CALL_REGEX = Regex("""^(?:call|dial|phone karo|ko call karo|ko call lagao)\s+(.+)$|^(?:call)\s+(.+)$|^(.+?)\s+ko\s+call(?:\s+karo|\s+lagao)?$""", RegexOption.IGNORE_CASE)
    private val OPEN_APP_REGEX = Regex("""^(?:open|launch|start|kholo|chalu karo)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun route(utterance: String, context: Context? = null): LocalRouteResult? {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return null

        // 1. Emergency stop
        if (STOP_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.Stop
        }

        // 2. What time is it
        if (TIME_REGEX.containsMatchIn(trimmed)) {
            val formatted = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            return LocalRouteResult.DirectReply("It's $formatted.")
        }

        // 3. Battery status
        if (BATTERY_REGEX.containsMatchIn(trimmed)) {
            val level = getBatteryPercent(context)
            return if (level != null) {
                LocalRouteResult.DirectReply("Battery is at $level%.")
            } else {
                LocalRouteResult.DirectReply("Unable to check battery level right now.")
            }
        }

        // 4. Flashlight / Torch
        if (FLASHLIGHT_ON_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.ToolAction(
                toolName = "toggle_flashlight",
                args = mapOf("enabled" to "true"),
                templateReply = "Flashlight turned on."
            )
        }
        if (FLASHLIGHT_OFF_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.ToolAction(
                toolName = "toggle_flashlight",
                args = mapOf("enabled" to "false"),
                templateReply = "Flashlight turned off."
            )
        }

        // 5. Volume control
        val volSetMatch = VOL_SET_REGEX.find(trimmed)
        if (volSetMatch != null) {
            val pct = volSetMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return LocalRouteResult.ToolAction(
                toolName = "adjust_volume",
                args = mapOf("stream" to "music", "level_percent" to pct.toString()),
                templateReply = "Volume set to $pct%."
            )
        }
        if (VOL_UP_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.ToolAction(
                toolName = "adjust_volume",
                args = mapOf("direction" to "up"),
                templateReply = "Volume increased."
            )
        }
        if (VOL_DOWN_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.ToolAction(
                toolName = "adjust_volume",
                args = mapOf("direction" to "down"),
                templateReply = "Volume decreased."
            )
        }
        if (VOL_MUTE_REGEX.containsMatchIn(trimmed)) {
            return LocalRouteResult.ToolAction(
                toolName = "adjust_volume",
                args = mapOf("direction" to "mute"),
                templateReply = "Volume muted."
            )
        }

        // 6. Alarms
        val alarmResult = parseAlarmCommand(trimmed)
        if (alarmResult != null) return alarmResult

        // 7. Timers
        val timerResult = parseTimerCommand(trimmed)
        if (timerResult != null) return timerResult

        // 8. YouTube Media
        val ytMatch = YOUTUBE_PLAY_REGEX.find(trimmed)
        if (ytMatch != null) {
            val q = ytMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (q.isNotEmpty()) {
                return LocalRouteResult.ToolAction(
                    toolName = "play_media",
                    args = mapOf("query" to q),
                    templateReply = "Playing $q on YouTube."
                )
            }
        }

        // 9. Navigation
        val navMatch = NAV_REGEX.find(trimmed)
        if (navMatch != null) {
            val dest = navMatch.groupValues[1].trim()
            if (dest.isNotEmpty()) {
                return LocalRouteResult.ToolAction(
                    toolName = "navigate_maps",
                    args = mapOf("destination" to dest),
                    templateReply = "Opening directions to $dest."
                )
            }
        }

        // 10. Call Contact
        val callMatch = CALL_REGEX.find(trimmed)
        if (callMatch != null) {
            val target = callMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (target.isNotEmpty() && !target.contains("police") && !target.contains("ambulance")) {
                return LocalRouteResult.ToolAction(
                    toolName = "call_contact",
                    args = mapOf("contact_name" to target),
                    templateReply = "Calling $target."
                )
            }
        }

        // 11. Open App
        val appMatch = OPEN_APP_REGEX.find(trimmed)
        if (appMatch != null) {
            val appQuery = appMatch.groupValues[1].trim()
            if (appQuery.isNotEmpty()) {
                val resolvedName = resolveAppName(appQuery, context)
                return LocalRouteResult.ToolAction(
                    toolName = "open_app",
                    args = mapOf("app_name" to resolvedName),
                    templateReply = "Opening $resolvedName."
                )
            }
        }

        return null
    }

    internal fun parseAlarmCommand(text: String): LocalRouteResult? {
        val lower = text.lowercase(Locale.US)
        if (!lower.contains("alarm") && !lower.contains("wake me up")) return null

        // If user says "set an alarm" without time
        val hasDigits = lower.any { it.isDigit() }
        val hasPhrasedTime = lower.contains("half past") || lower.contains("quarter past") || lower.contains("quarter to")
        if (!hasDigits && !hasPhrasedTime) {
            return LocalRouteResult.DirectReply("What time should I set the alarm for?")
        }

        var hour = 7
        var minute = 0
        var isPm = lower.contains("pm") || lower.contains("evening") || lower.contains("night") || lower.contains("shaam")
        var isAm = lower.contains("am") || lower.contains("morning") || lower.contains("subah")

        if (lower.contains("half past")) {
            val match = Regex("""half past\s+(\w+|\d+)""").find(lower)
            val hStr = match?.groupValues?.getOrNull(1).orEmpty()
            hour = wordToNumber(hStr) ?: hStr.toIntOrNull() ?: 6
            minute = 30
        } else if (lower.contains("quarter past")) {
            val match = Regex("""quarter past\s+(\w+|\d+)""").find(lower)
            val hStr = match?.groupValues?.getOrNull(1).orEmpty()
            hour = wordToNumber(hStr) ?: hStr.toIntOrNull() ?: 7
            minute = 15
        } else if (lower.contains("quarter to")) {
            val match = Regex("""quarter to\s+(\w+|\d+)""").find(lower)
            val hStr = match?.groupValues?.getOrNull(1).orEmpty()
            val target = wordToNumber(hStr) ?: hStr.toIntOrNull() ?: 8
            hour = if (target > 1) target - 1 else 12
            minute = 45
        } else {
            // Check HH:mm e.g. "6:30", "18:45"
            val colonMatch = Regex("""(\d{1,2}):(\d{2})""").find(lower)
            if (colonMatch != null) {
                hour = colonMatch.groupValues[1].toInt()
                minute = colonMatch.groupValues[2].toInt()
            } else {
                // Check single digit hour e.g. "7 am", "8 baje", "at 6"
                val hourMatch = Regex("""(\d{1,2})\s*(?:am|pm|baje|o'clock)?""").find(lower)
                if (hourMatch != null) {
                    hour = hourMatch.groupValues[1].toInt()
                    minute = 0
                }
            }
        }

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
        return LocalRouteResult.ToolAction(
            toolName = "set_alarm",
            args = mapOf(
                "hour" to hour.toString(),
                "minute" to minute.toString(),
                "label" to "JARVIS Alarm"
            ),
            templateReply = "Setting alarm for $formattedTime."
        )
    }

    internal fun parseTimerCommand(text: String): LocalRouteResult? {
        val lower = text.lowercase(Locale.US)
        if (!lower.contains("timer")) return null

        val digitsRegex = Regex("""(\d+)\s*(hour|hours|hr|hrs|ghanta|ghante|minute|minutes|min|mins|second|seconds|sec|secs)?""")
        val match = digitsRegex.find(lower)
        if (match == null) {
            return LocalRouteResult.DirectReply("How long should I set the timer for?")
        }

        val amount = match.groupValues[1].toIntOrNull() ?: return LocalRouteResult.DirectReply("How long should I set the timer for?")
        val unit = match.groupValues.getOrNull(2).orEmpty()

        val seconds = when {
            unit.startsWith("sec") -> amount
            unit.startsWith("hour") || unit.startsWith("hr") || unit.startsWith("ghant") -> amount * 3600
            else -> amount * 60
        }

        val durationLabel = when {
            seconds >= 3600 -> "${seconds / 3600} hour"
            seconds >= 60 -> "${seconds / 60} minute"
            else -> "$seconds second"
        } + if (seconds > 60 && (seconds % 60 == 0 || seconds % 3600 == 0)) "s" else ""

        return LocalRouteResult.ToolAction(
            toolName = "set_timer",
            args = mapOf(
                "seconds" to seconds.toString(),
                "label" to "JARVIS Timer"
            ),
            templateReply = "Starting $durationLabel timer."
        )
    }

    private fun resolveAppName(query: String, context: Context?): String {
        val clean = query.trim().lowercase(Locale.US)
        val canonical = when {
            clean.contains("youtube") -> "YouTube"
            clean.contains("whatsapp") -> "WhatsApp"
            clean.contains("chrome") -> "Chrome"
            clean.contains("camera") -> "Camera"
            clean.contains("settings") -> "Settings"
            clean.contains("maps") -> "Google Maps"
            clean.contains("gmail") -> "Gmail"
            clean.contains("spotify") -> "Spotify"
            clean.contains("calc") -> "Calculator"
            clean.contains("clock") -> "Clock"
            clean.contains("telegram") -> "Telegram"
            clean.contains("instagram") -> "Instagram"
            else -> query.trim().replaceFirstChar { it.uppercase() }
        }
        return canonical
    }

    private fun getBatteryPercent(context: Context?): Int? {
        if (context == null) return null
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct != null && pct in 0..100) return pct
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        }.getOrNull()
    }

    private fun wordToNumber(word: String): Int? {
        return when (word.trim().lowercase(Locale.US)) {
            "one", "ek" -> 1
            "two", "do" -> 2
            "three", "teen" -> 3
            "four", "chaar" -> 4
            "five", "paanch" -> 5
            "six", "chhe" -> 6
            "seven", "saat" -> 7
            "eight", "aath" -> 8
            "nine", "nau" -> 9
            "ten", "das" -> 10
            "eleven", "gyarah" -> 11
            "twelve", "baarah" -> 12
            else -> null
        }
    }
}
