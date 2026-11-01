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
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Parameter 'destination' is required.",
                outputHi = "मंजिल (destination) का नाम आवश्यक है।"
            )
        }

        return try {
            val encoded = Uri.encode(destination)
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(geoIntent)
                return ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Started Maps navigation to '$destination'.",
                    outputHi = "'$destination' के लिए गूगल मैप्स नेविगेशन शुरू किया गया।"
                )
            } catch (e: ActivityNotFoundException) {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(webIntent)
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened Maps directions for '$destination'.",
                        outputHi = "'$destination' के लिए मैप्स दिशा-निर्देश खोल दिए गए हैं।"
                    )
                } catch (e2: ActivityNotFoundException) {
                    ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "No Maps or browser app is available for '$destination'.",
                        outputHi = "'$destination' के लिए कोई मैप्स या ब्राउज़र ऐप उपलब्ध नहीं है।"
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to open Maps: ${e.localizedMessage ?: "error"}.",
                outputHi = "मैप्स खोलने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
