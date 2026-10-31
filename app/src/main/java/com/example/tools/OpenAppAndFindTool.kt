package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class OpenAppAndFindTool @Inject constructor(
    private val openAppTool: OpenAppTool
) : Tool {
    override val name: String = "open_app_and_find"
    override val description: String = "Opens an application and waits for a target UI element on screen."
    override val jsonSchema: String = """{"type":"object","properties":{"app_name":{"type":"string"},"target_text":{"type":"string"}},"required":["app_name","target_text"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val appName = args["app_name"]?.trim().orEmpty()
        val targetText = args["target_text"]?.trim().orEmpty()
        val launchRes = openAppTool.execute(mapOf("app_name" to appName))
        if (!launchRes.success) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = launchRes.outputEn,
                outputHi = launchRes.outputHi
            )
        }
        val (found, waitMsg) = JarvisAccessibilityService.waitForElement(targetText, 2000L)
        val waitHi = if (waitMsg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN) {
            JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
        } else {
            "'$targetText' जांचा गया: $waitMsg"
        }
        return ToolExecutionResult(
            toolName = name,
            success = found,
            outputEn = "${launchRes.outputEn} -> $waitMsg",
            outputHi = "${launchRes.outputHi} -> $waitHi"
        )
    }
}
