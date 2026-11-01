package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class TapElementTool @Inject constructor() : Tool {

    override val name: String = "tap_element"
    override val description: String = "Tap a visible UI element by text/description or screen coordinates using Accessibility."
    override val jsonSchema: String = """{"type":"object","properties":{"targetText":{"type":"string"},"text":{"type":"string"},"target_text":{"type":"string"},"x":{"type":"number"},"y":{"type":"number"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = (args["targetText"] ?: args["text"] ?: args["target_text"])?.takeIf { it.isNotBlank() }
        val x = args["x"]?.toFloatOrNull()
        val y = args["y"]?.toFloatOrNull()
        val (ok, msg) = JarvisAccessibilityService.executeTap(targetText, x, y)
        val hiMsg = if (ok) {
            if (targetText != null) "'$targetText' पर सफलतापूर्वक टैप किया गया।"
            else "निर्देशित स्थान पर टैप किया गया।"
        } else {
            if (msg.contains(JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN)) {
                "टैप करने के लिए एक्सेसिबिलिटी सर्विस सक्षम नहीं है। कृपया सेटिंग्स में जाकर इसे सक्षम करें।"
            } else {
                "टैप करने में विफल: $msg"
            }
        }
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg, outputHi = hiMsg)
    }
}
