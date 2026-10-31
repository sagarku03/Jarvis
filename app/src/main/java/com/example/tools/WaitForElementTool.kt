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
    override val description: String = "Polls the active screen until an element matching target_text appears or timeout_ms expires."
    override val jsonSchema: String = """{"type":"object","properties":{"target_text":{"type":"string"},"timeout_ms":{"type":"string"}},"required":["target_text"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = args["target_text"]?.trim().orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull() ?: 3000L
        val (ok, msg) = JarvisAccessibilityService.waitForElement(targetText, timeoutMs)
        val outputHi = when {
            ok -> "स्क्रीन पर '$targetText' तत्व मिल गया है।"
            msg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN -> JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            else -> "'$targetText' के लिए समय समाप्त हो गया।"
        }
        return ToolExecutionResult(
            toolName = name,
            success = ok,
            outputEn = msg,
            outputHi = outputHi
        )
    }
}
