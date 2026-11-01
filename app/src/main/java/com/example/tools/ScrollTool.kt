package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class ScrollTool @Inject constructor() : Tool {

    override val name: String = "scroll_screen"
    override val description: String = "Scroll the active screen up or down using Accessibility."
    override val jsonSchema: String = """{"type":"object","properties":{"direction":{"type":"string","enum":["down","up"]}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val direction = args["direction"] ?: "down"
        val (ok, msg) = JarvisAccessibilityService.executeScroll(direction)
        val hiMsg = if (ok) {
            if (direction == "up") "स्क्रीन ऊपर स्क्रॉल की गई।" else "स्क्रीन नीचे स्क्रॉल की गई।"
        } else {
            if (msg.contains(JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN)) {
                "स्क्रॉल करने के लिए एक्सेसिबिलिटी सर्विस सक्षम नहीं है। सेटिंग्स में जाकर सक्षम करें।"
            } else {
                "स्क्रॉल करने में विफल: $msg"
            }
        }
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg, outputHi = hiMsg)
    }
}
