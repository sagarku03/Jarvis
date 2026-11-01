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
    override val jsonSchema: String = """{"type":"object","properties":{"targetText":{"type":"string"},"target_text":{"type":"string"},"timeoutMs":{"type":"integer"},"timeout_ms":{"type":"integer"}},"required":["targetText"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetText = (args["targetText"] ?: args["target_text"]).orEmpty().trim()
        if (targetText.isBlank()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Parameter 'targetText' is required.",
                outputHi = "'targetText' पैरामीटर आवश्यक है।"
            )
        }
        val timeoutMs = (args["timeoutMs"] ?: args["timeout_ms"])?.toLongOrNull() ?: 3000L
        val (ok, msg) = JarvisAccessibilityService.waitForElement(targetText, timeoutMs)
        val hiMsg = if (ok) {
            "स्क्रीन पर तत्व '$targetText' मिल गया।"
        } else {
            if (msg.contains(JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN)) {
                "एक्सेसिबिलिटी सर्विस सक्षम नहीं है। सेटिंग्स में जाकर सक्षम करें।"
            } else {
                "${timeoutMs}ms तक '$targetText' की प्रतीक्षा का समय समाप्त हो गया।"
            }
        }
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg, outputHi = hiMsg)
    }
}
