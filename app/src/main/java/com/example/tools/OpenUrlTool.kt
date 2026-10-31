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
    override val description: String = "Open an HTTP or HTTPS URL in the default browser."
    override val jsonSchema: String = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawUrl = args["url"].orEmpty().trim()
        if (rawUrl.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'url' is required.")
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
                return ToolExecutionResult(name, false, "No browser app is available to open $normalizedUrl.")
            }
            context.startActivity(intent)
            ToolExecutionResult(name, true, "Opened $normalizedUrl in browser.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to open URL: ${e.localizedMessage ?: "error"}.")
        }
    }
}
