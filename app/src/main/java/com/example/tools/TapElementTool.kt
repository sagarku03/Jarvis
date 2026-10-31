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
    override val jsonSchema: String = """{"type":"object","properties":{"targetText":{"type":"string"},"text":{"type":"string"},"x":{"type":"number"},"y":{"type":"number"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = (args["targetText"] ?: args["text"] ?: args["target_text"])?.takeIf { it.isNotBlank() }
        val x = args["x"]?.toFloatOrNull()
        val y = args["y"]?.toFloatOrNull()
        val (ok, msg) = JarvisAccessibilityService.executeTap(targetText, x, y)
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg)
    }
}
