package com.example.tools

import android.app.SearchManager
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
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Search query cannot be empty.",
                outputHi = "खोज (query) खाली नहीं हो सकती।"
            )
        }

        return try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(searchIntent)
                return ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Started web search for '$query'.",
                    outputHi = "'$query' के लिए वेब खोज शुरू की गई।"
                )
            } catch (e: ActivityNotFoundException) {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(browserIntent)
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened web search for '$query'.",
                        outputHi = "'$query' के लिए वेब खोज खोल दी गई है।"
                    )
                } catch (e2: ActivityNotFoundException) {
                    ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "No browser is available to search for '$query'.",
                        outputHi = "'$query' खोजने के लिए कोई ब्राउज़र उपलब्ध नहीं है।"
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Web search failed: ${e.localizedMessage ?: "error"}.",
                outputHi = "वेब खोज विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
