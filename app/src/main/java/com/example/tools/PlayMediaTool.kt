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
class PlayMediaTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "play_media"
    override val description: String = "Searches and plays videos or music on YouTube via intent."
    override val jsonSchema: String = """{"type":"object","properties":{"query":{"type":"string","description":"Song, artist, or video title to play on YouTube"}},"required":["query"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'query' parameter for media playback.",
                outputHi = "मीडिया चलाने के लिए 'query' आवश्यक है।"
            )
        }

        return try {
            val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No YouTube or browser app is installed to play '$query'.",
                    outputHi = "'$query' चलाने के लिए यूट्यूब या ब्राउज़र उपलब्ध नहीं है।"
                )
            }
            context.startActivity(intent)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Opened YouTube search for '$query'.",
                outputHi = "यूट्यूब पर '$query' खोल दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Media playback intent failed: ${e.localizedMessage}",
                outputHi = "यूट्यूब चलाने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
