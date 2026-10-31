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
    override val description: String = "Taps a UI element by visible text, contentDescription, or (x,y) coordinates and verifies whether the screen changed."
    override val jsonSchema: String = """{"type":"object","properties":{"target_text":{"type":"string","description":"Label or view ID to tap"},"x":{"type":"string"},"y":{"type":"string"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = args["target_text"]?.trim()
        val x = args["x"]?.toFloatOrNull()
        val y = args["y"]?.toFloatOrNull()

        val (ok, msg) = JarvisAccessibilityService.executeTap(targetText, x, y)
        val outputHi = when {
            ok -> "'${targetText ?: "$x,$y"}' पर टैप किया गया और स्क्रीन परिवर्तन जांचा गया।"
            msg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN -> JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            else -> "टैप करने में विफल: $msg"
        }
        return ToolExecutionResult(
            toolName = name,
            success = ok,
            outputEn = msg,
            outputHi = outputHi
        )
    }
}
