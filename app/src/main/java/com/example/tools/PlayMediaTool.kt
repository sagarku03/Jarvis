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
class PlayMediaTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "play_media"
    override val description: String = "Search and play music or video on YouTube or the default media player."
    override val jsonSchema: String = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"].orEmpty().trim()
        if (query.isBlank()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Parameter 'query' is required to play media.",
                outputHi = "मीडिया चलाने के लिए 'query' आवश्यक है।"
            )
        }

        return try {
            val encoded = Uri.encode(query)
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No YouTube or browser app is available to play '$query'.",
                    outputHi = "'$query' चलाने के लिए कोई यूट्यूब या ब्राउज़र ऐप उपलब्ध नहीं है।"
                )
            }

            // If accessibility is enabled, auto-click the first video result
            val isAccessibilityOn = com.example.services.JarvisAccessibilityService.isConnected.value
            if (isAccessibilityOn) {
                val (clicked, _) = com.example.services.JarvisAccessibilityService.clickFirstYouTubeVideoResult()
                if (clicked) {
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Playing '$query' on YouTube.",
                        outputHi = "यूट्यूब पर '$query' चलाया जा रहा है।"
                    )
                } else {
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened results for '$query' on YouTube.",
                        outputHi = "यूट्यूब पर '$query' के परिणाम खोल दिए गए हैं।"
                    )
                }
            } else {
                ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Opened results for '$query' on YouTube. Enable Accessibility to play automatically.",
                    outputHi = "यूट्यूब पर '$query' के परिणाम खोल दिए गए हैं। स्वचालित रूप से चलाने के लिए एक्सेसिबिलिटी सक्षम करें।"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to play media: ${e.localizedMessage ?: "error"}.",
                outputHi = "मीडिया चलाने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
