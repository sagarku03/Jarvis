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
class NavigateTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "navigate_maps"
    override val description: String = "Launch Google Maps navigation or directions to a destination."
    override val jsonSchema: String = """{"type":"object","properties":{"destination":{"type":"string","description":"Place name or address to navigate to"}},"required":["destination"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val destination = args["destination"].orEmpty().trim()
        if (destination.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'destination' is required.")
        }

        return try {
            val encoded = Uri.encode(destination)
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (geoIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(geoIntent)
                return ToolExecutionResult(name, true, "Started Maps navigation to '$destination'.")
            }

            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webIntent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(name, false, "No Maps or browser app is available for '$destination'.")
            }
            context.startActivity(webIntent)
            ToolExecutionResult(name, true, "Opened Maps directions for '$destination'.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to open Maps: ${e.localizedMessage ?: "error"}.")
        }
    }
}
