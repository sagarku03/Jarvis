package com.example.tools

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
    override val description: String = "Opens a web URL in the user's default browser. Medium risk action requiring confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"url":{"type":"string","description":"Full https:// URL to open"}},"required":["url"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawUrl = args["url"]?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'url' parameter.",
                outputHi = "'url' पैरामीटर नहीं मिला।"
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
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No browser app is installed to open $normalizedUrl.",
                    outputHi = "$normalizedUrl खोलने के लिए कोई ब्राउज़र उपलब्ध नहीं है।"
                )
            }
            context.startActivity(intent)
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
                outputEn = "Failed to open URL: ${e.localizedMessage}",
                outputHi = "वेबसाइट खोलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
