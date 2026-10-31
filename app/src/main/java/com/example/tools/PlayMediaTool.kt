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
    override val description: String = "Search and play music or video on YouTube or the default media player."
    override val jsonSchema: String = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"].orEmpty().trim()
        if (query.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'query' is required to play media.")
        }

        return try {
            val encoded = Uri.encode(query)
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(name, false, "No YouTube or browser app is available to play '$query'.")
            }
            context.startActivity(intent)
            ToolExecutionResult(name, true, "Opened '$query' on YouTube.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to play media: ${e.localizedMessage ?: "error"}.")
        }
    }
}
