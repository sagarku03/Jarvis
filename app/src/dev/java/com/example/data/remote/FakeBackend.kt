package com.example.data.remote

import com.example.di.Inject
import com.example.di.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/**
 * Dev-only FakeBackend (`src/dev`) implementing ApiService for offline/emulator testing.
 * Never included in `prod` (`src/prod`).
 * Does NOT generate 440Hz beeps or fake STT transcripts.
 */
@Singleton
class FakeBackend @Inject constructor() : ApiService {

    override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
        val lastMessage = request.messages.lastOrNull()
            ?: return AgentStepResponseDto(
                assistantText = bilingual(
                    request.languageCode,
                    en = "I didn't catch that. How can I help you?",
                    hi = "मैं समझ नहीं पाया। बताइए मैं आपकी क्या मदद करूँ?"
                )
            )

        if (lastMessage.role == "tool") {
            val toolName = lastMessage.toolName ?: "tool"
            val toolResult = lastMessage.content
            val reply = when {
                request.languageCode.equals("hi-IN", ignoreCase = true) ->
                    "जी, $toolName: $toolResult"
                else ->
                    "$toolResult"
            }
            return AgentStepResponseDto(
                assistantText = reply,
                toolCalls = emptyList()
            )
        }

        val text = lastMessage.content.trim()
        val lower = text.lowercase()
        val extractedCandidates = detectMemoryCandidates(text)

        if (lower.contains("read screen") || lower.contains("what is on screen") ||
            lower.contains("स्क्रीन पढ़ो") || lower.contains("screen padho")
        ) {
            return singleToolCall(
                name = "read_screen",
                args = mapOf("include_bounds" to "true"),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Reading active screen hierarchy.",
                    hi = "वर्तमान स्क्रीन के तत्वों को पढ़ रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.startsWith("tap ") || lower.contains("click on ") ||
            lower.contains("पर टैप करो") || lower.contains("tap karo")
        ) {
            val targetText = text
                .replace(Regex("(?i)(tap on|tap|click on|पर टैप करो|tap karo)"), "")
                .trim()
                .ifEmpty { "Search" }
            return singleToolCall(
                name = "tap_element",
                args = mapOf("target_text" to targetText),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Tapping on '$targetText'.",
                    hi = "'$targetText' पर टैप कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.startsWith("type ") || lower.contains("type text ") || lower.contains("टाइप करो")) {
            val inputVal = text
                .replace(Regex("(?i)(type text|type|टाइप करो)"), "")
                .trim()
            return singleToolCall(
                name = "type_text",
                args = mapOf("text" to inputVal),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Typing '$inputVal' into focused input field.",
                    hi = "इनपुट बॉक्स में '$inputVal' टाइप कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("scroll down") || lower.contains("scroll up") ||
            lower.contains("नीचे स्क्रॉल") || lower.contains("ऊपर स्क्रॉल") || lower.contains("scroll karo")
        ) {
            val dir = if (lower.contains("up") || lower.contains("ऊपर")) "up" else "down"
            return singleToolCall(
                name = "scroll",
                args = mapOf("direction" to dir),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Scrolling $dir on current screen.",
                    hi = "स्क्रीन को $dir स्क्रॉल कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("go back") || lower.contains("press back") || lower.contains("go home") ||
            lower.contains("पीछे जाओ") || lower.contains("होम स्क्रीन")
        ) {
            val action = when {
                lower.contains("home") || lower.contains("होम") -> "home"
                lower.contains("recent") -> "recents"
                else -> "back"
            }
            return singleToolCall(
                name = "press_back_or_home",
                args = mapOf("action" to action),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Executing system navigation: $action.",
                    hi = "सिस्टम नेविगेशन ($action) चला रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.startsWith("wait for ") || lower.contains("प्रतीक्षा करो")) {
            val query = text.replace(Regex("(?i)(wait for|प्रतीक्षा करो)"), "").trim()
            return singleToolCall(
                name = "wait_for_element",
                args = mapOf("target_text" to query, "timeout_ms" to "3000"),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Waiting for element '$query' to appear on screen.",
                    hi = "स्क्रीन पर '$query' दिखने की प्रतीक्षा कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("open ") && lower.contains(" and find ")) {
            val app = text.substringAfter("open ", "").substringBefore(" and find").trim()
            val findTarget = text.substringAfter("and find ", "").trim()
            return singleToolCall(
                name = "open_app_and_find",
                args = mapOf("app_name" to app, "target_text" to findTarget),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Opening $app and locating '$findTarget'.",
                    hi = "$app खोलकर '$findTarget' ढूंढ रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("whatsapp") || lower.contains("telegram") ||
            lower.contains("व्हाट्सएप") || lower.contains("टेलीग्राम")
        ) {
            val platform = if (lower.contains("telegram") || lower.contains("टेलीग्राम")) "telegram" else "whatsapp"
            val phoneRegex = Regex("(\\+?\\d{10,13})")
            val recipient = phoneRegex.find(text)?.value ?: extractRecipientName(text)
            val body = extractSmsBody(text)
            return singleToolCall(
                name = "send_whatsapp_or_telegram",
                args = mapOf(
                    "platform" to platform,
                    "recipient" to recipient,
                    "message" to body
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Preparing $platform message to $recipient.",
                    hi = "$recipient को $platform संदेश तैयार कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("notification") || lower.contains("नोटिफिकेशन") || lower.contains("unread messages")) {
            val isReply = lower.contains("reply") || lower.contains("जवाब")
            return singleToolCall(
                name = "read_or_reply_notifications",
                args = mapOf(
                    "mode" to if (isReply) "reply" else "summarize",
                    "reply_text" to if (isReply) extractSmsBody(text) else ""
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = if (isReply) "Preparing notification quick-reply." else "Checking notifications.",
                    hi = if (isReply) "नोटिफिकेशन का जवाब तैयार कर रहा हूँ।" else "नोटिफिकेशन देख रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("good morning") || lower.contains("सुप्रभात") || lower.contains("morning briefing")) {
            return AgentStepResponseDto(
                assistantText = bilingual(
                    request.languageCode,
                    en = "Good morning! How can I assist you with your schedule or phone controls today?",
                    hi = "सुप्रभात! आज मैं आपके शेड्यूल या फ़ोन नियंत्रण में क्या मदद कर सकता हूँ?"
                ),
                toolCalls = emptyList(),
                memoryCandidates = extractedCandidates
            )
        }

        if (lower.contains("flashlight") || lower.contains("torch") ||
            lower.contains("टॉर्च") || lower.contains("फ्लैशलाइट") || lower.contains("light jala") || lower.contains("light band")
        ) {
            val turnOff = lower.contains("off") || lower.contains("band") || lower.contains("बंद") || lower.contains("बुझा")
            val state = if (turnOff) "off" else "on"
            return singleToolCall(
                name = "flashlight",
                args = mapOf("state" to state),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Toggling flashlight $state.",
                    hi = "टॉर्च $state कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("alarm") || lower.contains("अलार्म") || lower.contains("subah utha")) {
            val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
            val match = timeRegex.find(lower)
            var hour = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
            val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val amPm = match?.groupValues?.getOrNull(3)?.lowercase() ?: ""
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0

            return singleToolCall(
                name = "set_alarm",
                args = mapOf(
                    "hour" to hour.toString(),
                    "minute" to minute.toString(),
                    "label" to "JARVIS Alarm"
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Setting alarm for %02d:%02d.".format(hour, minute),
                    hi = "%02d:%02d का अलार्म सेट कर रहा हूँ।".format(hour, minute)
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("timer") || lower.contains("टाइमर")) {
            val numRegex = Regex("(\\d+)")
            val amount = numRegex.find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 5
            val isSeconds = lower.contains("sec") || lower.contains("सेकंड")
            val seconds = if (isSeconds) amount else amount * 60
            return singleToolCall(
                name = "set_timer",
                args = mapOf(
                    "seconds" to seconds.toString(),
                    "label" to "JARVIS Timer"
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Starting timer for $seconds seconds.",
                    hi = "$seconds सेकंड का टाइमर शुरू कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("volume") || lower.contains("आवाज़") || lower.contains("आवाज") || lower.contains("awaz") || lower.contains("mute")) {
            val numRegex = Regex("(\\d+)")
            val level = when {
                lower.contains("mute") || lower.contains("म्यूट") || lower.contains("कम") || lower.contains("low") -> 20
                lower.contains("full") || lower.contains("max") || lower.contains("तेज़") || lower.contains("badha") -> 90
                else -> numRegex.find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 60
            }
            return singleToolCall(
                name = "volume",
                args = mapOf(
                    "level" to level.toString(),
                    "stream" to "music"
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Adjusting media volume to $level%.",
                    hi = "मीडिया वॉल्यूम $level% पर सेट कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("sms") || lower.contains("message bhejo") || lower.contains("text ") ||
            lower.contains("मैसेज") || lower.contains("संदेश") || lower.contains("msg")
        ) {
            val phoneRegex = Regex("(\\+?\\d{10,13})")
            val recipient = phoneRegex.find(text)?.value ?: extractRecipientName(text)
            val body = extractSmsBody(text)
            return singleToolCall(
                name = "send_sms",
                args = mapOf(
                    "recipient" to recipient,
                    "message" to body
                ),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Preparing SMS to $recipient.",
                    hi = "$recipient को एसएमएस तैयार कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("call ") || lower.contains("phone lagao") || lower.contains("कॉल") || lower.contains("फ़ोन लगाओ") || lower.contains("dial ")) {
            val phoneRegex = Regex("(\\+?\\d{10,13})")
            val target = phoneRegex.find(text)?.value
                ?: text.replace(Regex("(?i)(call|dial|phone lagao|ko call karo|को कॉल करो|कॉल करो|कॉल)"), "").trim()
            return singleToolCall(
                name = "call_contact",
                args = mapOf("contact" to target),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Looking up contact $target for call.",
                    hi = "कॉल के लिए $target को खोज रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("navigate") || lower.contains("route to") || lower.contains("directions to") ||
            lower.contains("रास्ता") || lower.contains("नेविगेट") || lower.contains("map") || lower.contains("rasta")
        ) {
            val destination = text
                .replace(Regex("(?i)(navigate to|navigate|directions to|route to|ka rasta batao|रास्ता बताओ|नेविगेट करो|maps)"), "")
                .trim()
            return singleToolCall(
                name = "navigate",
                args = mapOf("destination" to destination),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Launching navigation to $destination.",
                    hi = "$destination के लिए मैप्स नेविगेशन खोल रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("play ") || lower.contains("youtube") || lower.contains("गाना") ||
            lower.contains("चलाओ") || lower.contains("chalao") || lower.contains("song")
        ) {
            val query = text
                .replace(Regex("(?i)(play|on youtube|youtube par|गाना चलाओ|चलाओ|chalao|song)"), "")
                .trim()
            return singleToolCall(
                name = "play_media",
                args = mapOf("query" to query),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Playing '$query' on YouTube.",
                    hi = "यूट्यूब पर '$query' चला रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("http://") || lower.contains("https://") || lower.contains(".com") || lower.contains(".in") || lower.contains(".org")) {
            val urlRegex = Regex("(https?://\\S+|\\b[a-zA-Z0-9.-]+\\.(?:com|in|org|io|net|gov)\\S*)")
            val rawUrl = urlRegex.find(text)?.value ?: ""
            val normalizedUrl = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            return singleToolCall(
                name = "open_url",
                args = mapOf("url" to normalizedUrl),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Opening $normalizedUrl in browser.",
                    hi = "ब्राउज़र में $normalizedUrl खोल रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("open ") || lower.contains("launch ") || lower.contains("खोलो") || lower.contains("kholo")) {
            val appName = text
                .replace(Regex("(?i)(open|launch|app|खोलो|kholo)"), "")
                .trim()
            return singleToolCall(
                name = "open_app",
                args = mapOf("app_name" to appName),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Opening $appName.",
                    hi = "$appName ऐप खोल रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        if (lower.contains("search ") || lower.contains("google ") || lower.contains("खोजो") || lower.contains("ढूंढो") || lower.contains("khojo")) {
            val query = text
                .replace(Regex("(?i)(search for|search|google|खोजो|ढूंढो|khojo)"), "")
                .trim()
                .ifEmpty { text }
            return singleToolCall(
                name = "web_search",
                args = mapOf("query" to query),
                assistantText = bilingual(
                    request.languageCode,
                    en = "Searching the web for '$query'.",
                    hi = "'$query' के लिए वेब पर खोज कर रहा हूँ।"
                ),
                candidates = extractedCandidates
            )
        }

        val systemMemorySummary = request.messages
            .firstOrNull { it.role == "system" }
            ?.content
            .orEmpty()

        val conversationalReply = when {
            lower.contains("memory") || lower.contains("याद") || lower.contains("what do you know") -> {
                if (systemMemorySummary.isNotBlank()) {
                    bilingual(
                        request.languageCode,
                        en = "Saved memories in your Vault: $systemMemorySummary",
                        hi = "आपकी मेमोरी वॉल्ट में सहेजी गई जानकारी: $systemMemorySummary"
                    )
                } else {
                    bilingual(
                        request.languageCode,
                        en = "Your Memory Vault is empty. Say 'Remember that...' and approve to save.",
                        hi = "आपकी मेमोरी वॉल्ट खाली है। कुछ सहेजने के लिए कहें 'याद रखो कि...'।"
                    )
                }
            }
            else -> bilingual(
                request.languageCode,
                en = "I heard: \"$text\". How would you like me to help?",
                hi = "आपने कहा: \"$text\"। बताइए मैं क्या करूँ?"
            )
        }

        return AgentStepResponseDto(
            assistantText = conversationalReply,
            toolCalls = emptyList(),
            memoryCandidates = extractedCandidates
        )
    }

    override suspend fun speechToText(request: SttRequestDto): SttResponseDto {
        // Honest behavior: never fabricate a fake transcript
        return SttResponseDto(text = "")
    }

    override suspend fun textToSpeech(request: TtsRequestDto): ResponseBody {
        // Honest behavior: never return a 440Hz beep; return empty so local TTS fallback speaks cleanly
        return ByteArray(0).toResponseBody("application/octet-stream".toMediaType())
    }

    private fun detectMemoryCandidates(utterance: String): List<WireMemoryCandidateDto> {
        val lower = utterance.lowercase()
        if (lower.contains("my favorite ") ||
            lower.contains("nickname for ") ||
            lower.contains("remember that ") ||
            lower.contains("मेरा पसंदीदा") ||
            lower.contains("याद रखो")
        ) {
            return listOf(
                WireMemoryCandidateDto(
                    id = "cand_${UUID.randomUUID().toString().take(8)}",
                    category = "Backend Extracted Fact",
                    keyLabel = utterance.take(28),
                    contentValue = utterance,
                    sourceUtterance = utterance
                )
            )
        }
        return emptyList()
    }

    private fun singleToolCall(
        name: String,
        args: Map<String, String>,
        assistantText: String,
        candidates: List<WireMemoryCandidateDto> = emptyList()
    ): AgentStepResponseDto {
        return AgentStepResponseDto(
            assistantText = assistantText,
            toolCalls = listOf(
                WireToolCallDto(
                    id = "call_${UUID.randomUUID().toString().take(8)}",
                    name = name,
                    args = args
                )
            ),
            memoryCandidates = candidates
        )
    }

    private fun bilingual(langCode: String, en: String, hi: String): String {
        return if (langCode.equals("hi-IN", ignoreCase = true)) hi else en
    }

    private fun extractRecipientName(text: String): String {
        return text.substringAfter("to ", "").substringBefore(" saying").substringBefore(" that").trim()
    }

    private fun extractSmsBody(text: String): String {
        return text.substringAfter("saying ", "")
            .ifEmpty { text.substringAfter("कि ", "") }
            .ifEmpty { text.substringAfter(":", "") }
            .trim()
    }
}
