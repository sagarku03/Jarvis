package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class WaitForElementTool @Inject constructor() : Tool {

    override val name: String = "wait_for_element"
    override val description: String = "Wait up to timeoutMs for a UI element containing targetText to appear on screen."
    override val jsonSchema: String = """{"type":"object","properties":{"targetText":{"type":"string"},"timeoutMs":{"type":"integer"}},"required":["targetText"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = (args["targetText"] ?: args["target_text"]).orEmpty().trim()
        if (targetText.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'targetText' is required.")
        }
        val timeoutMs = (args["timeoutMs"] ?: args["timeout_ms"])?.toLongOrNull() ?: 3000L
        val (ok, msg) = JarvisAccessibilityService.waitForElement(targetText, timeoutMs)
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg)
    }
}
