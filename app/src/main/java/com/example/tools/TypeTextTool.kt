package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class TypeTextTool @Inject constructor() : Tool {

    override val name: String = "type_text"
    override val description: String = "Type text into the focused or matching editable input field on screen (never into password fields)."
    override val jsonSchema: String = """{"type":"object","properties":{"text":{"type":"string"},"targetHint":{"type":"string"}},"required":["text"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val text = args["text"].orEmpty()
        if (text.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'text' cannot be empty.")
        }
        val targetHint = args["targetHint"] ?: args["target_hint"]
        val (ok, msg) = JarvisAccessibilityService.executeTypeText(text, targetHint)
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg)
    }
}
