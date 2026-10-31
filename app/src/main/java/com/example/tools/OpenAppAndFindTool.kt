package com.example.tools

import android.content.Context
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService
import kotlinx.coroutines.delay

@Singleton
class OpenAppAndFindTool @Inject constructor(
    context: Context
) : Tool {

    private val openAppTool = OpenAppTool(context)

    override val name: String = "open_app_and_find"
    override val description: String = "Open an app and optionally locate a UI element by text using Accessibility."
    override val jsonSchema: String = """{"type":"object","properties":{"appName":{"type":"string"},"findText":{"type":"string"}},"required":["appName"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val appName = (args["appName"] ?: args["app_name"]).orEmpty().trim()
        val findText = (args["findText"] ?: args["find_text"]).orEmpty().trim()
        val openResult = openAppTool.execute(mapOf("appName" to appName))
        if (!openResult.success || findText.isBlank()) {
            return openResult
        }

        val service = JarvisAccessibilityService.instance.value
            ?: return ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "${openResult.outputEn} (Accessibility service is not enabled to search for '$findText'.)"
            )

        delay(450L)
        val snapshot = service.captureScreenContext()
        val match = snapshot?.structuredNodes?.firstOrNull {
            it.text.contains(findText, ignoreCase = true) ||
                it.contentDescription.contains(findText, ignoreCase = true)
        }
        return if (match != null) {
            val label = match.text.ifEmpty { match.contentDescription }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "${openResult.outputEn} Found element '$label' at ${match.bounds}."
            )
        } else {
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "${openResult.outputEn} Element '$findText' was not immediately visible on screen."
            )
        }
    }
}
