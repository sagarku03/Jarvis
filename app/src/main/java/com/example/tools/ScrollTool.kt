package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class ScrollTool @Inject constructor() : Tool {
    override val name: String = "scroll"
    override val description: String = "Scrolls or swipes the active screen up or down and verifies screen change."
    override val jsonSchema: String = """{"type":"object","properties":{"direction":{"type":"string","description":"up or down"}},"required":["direction"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val direction = args["direction"]?.trim()?.lowercase() ?: "down"
        val (ok, msg) = JarvisAccessibilityService.executeScroll(direction)
        val outputHi = when {
            ok -> "स्क्रीन को '$direction' दिशा में स्क्रॉल किया गया।"
            msg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN -> JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            else -> "स्क्रॉल करने में विफल: $msg"
        }
        return ToolExecutionResult(
            toolName = name,
            success = ok,
            outputEn = msg,
            outputHi = outputHi
        )
    }
}
