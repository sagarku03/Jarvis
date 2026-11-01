package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class ReadScreenTool @Inject constructor() : Tool {
    override val name: String = "read_screen"
    override val description: String = "Reads the active screen accessibility node tree into a compact prompt-injection-safe JSON, skipping password fields and secure windows."
    override val jsonSchema: String = """{"type":"object","properties":{"include_bounds":{"type":"string","description":"true or false"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val snap = JarvisAccessibilityService.captureCurrentScreenSnapshot()
            ?: return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN,
                outputHi = "स्क्रीन पढ़ने के लिए एक्सेसिबिलिटी सर्विस सक्षम नहीं है। सेटिंग्स में जाकर सक्षम करें।"
            )

        if (snap.isSecureScreen) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Cannot read screen: current screen (${snap.packageName}) is marked secure or contains sensitive fields.",
                outputHi = "स्क्रीन नहीं पढ़ सकते: वर्तमान स्क्रीन (${snap.packageName}) सुरक्षित या संवेदनशील है।"
            )
        }

        val contentPreview = when {
            snap.summaryText.isNotBlank() -> " Visible content: ${snap.summaryText.take(300)}"
            snap.compactJson.isNotBlank() && snap.compactJson != "[]" -> " Nodes: ${snap.compactJson.take(300)}"
            else -> ""
        }

        return ToolExecutionResult(
            toolName = name,
            success = true,
            outputEn = "Screen read from ${snap.packageName}: ${snap.structuredNodes.size} nodes.$contentPreview",
            outputHi = "${snap.packageName} से स्क्रीन पढ़ी गई: ${snap.structuredNodes.size} तत्व मिले।$contentPreview"
        )
    }
}
