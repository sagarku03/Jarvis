package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class SystemNavigationTool @Inject constructor() : Tool {

    override val name: String = "system_navigation"
    override val description: String = "Perform global Android navigation: back, home, recents, notifications, or quick_settings."
    override val jsonSchema: String = """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["action"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val action = (args["action"] ?: "back").lowercase().trim()
        val (ok, msg) = JarvisAccessibilityService.executeSystemGlobalAction(action)
        val hiMsg = if (ok) {
            when (action) {
                "back" -> "पीछे (Back) जाया गया।"
                "home" -> "होम स्क्रीन पर जाया गया।"
                "recents" -> "हाल के ऐप्स (Recents) खोले गए।"
                "notifications" -> "सूचना पैनल (Notifications) खोला गया।"
                "quick_settings" -> "त्वरित सेटिंग्स (Quick Settings) खोली गईं।"
                else -> "सिस्टम नेविगेशन क्रिया '$action' निष्पादित की गई।"
            }
        } else {
            if (msg.contains(JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN)) {
                "सिस्टम नेविगेशन के लिए एक्सेसिबिलिटी सर्विस सक्षम नहीं है। सेटिंग्स में जाकर सक्षम करें।"
            } else {
                "नेविगेशन विफल: $msg"
            }
        }
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg, outputHi = hiMsg)
    }
}
