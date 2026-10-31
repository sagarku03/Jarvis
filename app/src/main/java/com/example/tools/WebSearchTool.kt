package com.example.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class WebSearchTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "web_search"
    override val description: String = "Perform a web search in the default browser or Google Search app."
    override val jsonSchema: String = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"].orEmpty().trim()
        if (query.isBlank()) {
            return ToolExecutionResult(name, false, "Search query cannot be empty.")
        }

        return try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
                return ToolExecutionResult(name, true, "Started web search for '$query'.")
            }

            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (browserIntent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(name, false, "No browser is available to search for '$query'.")
            }
            context.startActivity(browserIntent)
            ToolExecutionResult(name, true, "Opened web search for '$query'.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Web search failed: ${e.localizedMessage ?: "error"}.")
        }
    }
}
