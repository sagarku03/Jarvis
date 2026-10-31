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
                outputHi = JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            )

        return ToolExecutionResult(
            toolName = name,
            success = true,
            outputEn = "Screen read from ${snap.packageName}: ${snap.structuredNodes.size} nodes (secure=${snap.isSecureScreen}).",
            outputHi = "${snap.packageName} से स्क्रीन पढ़ी गई: ${snap.structuredNodes.size} तत्व मिले।"
        )
    }
}
