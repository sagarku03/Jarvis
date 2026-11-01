package com.example.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class OpenUrlTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "open_url"
    override val description: String = "Open an HTTP or HTTPS URL in the default browser."
    override val jsonSchema: String = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawUrl = args["url"].orEmpty().trim()
        if (rawUrl.isBlank()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Parameter 'url' is required.",
                outputHi = "यूआरएल (url) आवश्यक है।"
            )
        }
        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No browser app is available to open $normalizedUrl.",
                    outputHi = "$normalizedUrl खोलने के लिए कोई ब्राउज़र ऐप उपलब्ध नहीं है।"
                )
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Opened $normalizedUrl in browser.",
                outputHi = "ब्राउज़र में $normalizedUrl खोल दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to open URL: ${e.localizedMessage ?: "error"}.",
                outputHi = "यूआरएल खोलने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
