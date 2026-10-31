package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class SystemNavigationTool @Inject constructor() : Tool {
    override val name: String = "press_back_or_home"
    override val description: String = "Executes system global actions: back, home, recents, notifications, or quick_settings."
    override val jsonSchema: String = """{"type":"object","properties":{"action":{"type":"string","description":"back, home, recents, notifications, or quick_settings"}},"required":["action"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.trim()?.lowercase() ?: "back"
        val (ok, msg) = JarvisAccessibilityService.executeSystemGlobalAction(action)
        val outputHi = when {
            ok -> "सिस्टम नेविगेशन '$action' पूरा किया गया।"
            msg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN -> JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            else -> "सिस्टम नेविगेशन विफल: $msg"
        }
        return ToolExecutionResult(
            toolName = name,
            success = ok,
            outputEn = msg,
            outputHi = outputHi
        )
    }
}
