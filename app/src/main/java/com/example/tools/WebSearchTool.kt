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
    override val description: String = "Performs a Google web search for the given query."
    override val jsonSchema: String = """{"type":"object","properties":{"query":{"type":"string","description":"Search query text"}},"required":["query"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Search query cannot be empty.",
                outputHi = "खोज के लिए प्रश्न खाली नहीं हो सकता।"
            )
        }

        return try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
            } else {
                val fallbackBrowser = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallbackBrowser.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "No search or browser app is installed to search for '$query'.",
                        outputHi = "'$query' खोजने के लिए कोई ब्राउज़र उपलब्ध नहीं है।"
                    )
                }
                context.startActivity(fallbackBrowser)
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Launched web search for '$query'.",
                outputHi = "'$query' के लिए वेब खोज शुरू कर दी गई है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Web search failed: ${e.localizedMessage}",
                outputHi = "वेब खोज विफल रही: ${e.localizedMessage}"
            )
        }
    }
}
