package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class SystemNavigationTool @Inject constructor() : Tool {

    override val name: String = "system_navigation"
    override val description: String = "Perform global Android navigation: back, home, recents, notifications, or quick_settings."
    override val jsonSchema: String = """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["action"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"] ?: "back"
        val (ok, msg) = JarvisAccessibilityService.executeSystemGlobalAction(action)
        return ToolExecutionResult(toolName = name, success = ok, outputEn = msg)
    }
}
