package com.example.tools

import android.content.Context
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisNotificationListener

@Singleton
class NotificationActionTool @Inject constructor(
    @Suppress("unused") private val context: Context
) : Tool {

    override val name: String = "notification_action"
    override val description: String = "Read recent notifications, send an inline reply, or dismiss a notification."
    override val jsonSchema: String = """{"type":"object","properties":{"action":{"type":"string","enum":["list","reply","dismiss"]},"senderOrApp":{"type":"string"},"replyText":{"type":"string"}},"required":["action"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.lowercase()?.trim() ?: "list"
        val items = JarvisNotificationListener.recentNotifications.value

        return when (action) {
            "list" -> {
                if (items.isEmpty()) {
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "No active notifications found."
                    )
                } else {
                    val summary = items.take(5).joinToString("; ") { item ->
                        "${item.packageName} (${item.title}): ${item.text}"
                    }
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Recent notifications: $summary"
                    )
                }
            }
            "reply" -> {
                val query = (args["senderOrApp"] ?: args["sender"] ?: args["app"]).orEmpty().trim()
                val replyText = (args["replyText"] ?: args["reply_text"] ?: args["message"]).orEmpty().trim()
                if (replyText.isBlank()) {
                    return ToolExecutionResult(name, false, "Reply text cannot be empty.")
                }
                val (sent, msg) = JarvisNotificationListener.sendInlineReply(query, replyText)
                ToolExecutionResult(name, sent, msg)
            }
            "dismiss" -> {
                val query = (args["senderOrApp"] ?: args["sender"] ?: args["app"]).orEmpty().trim()
                val (dismissed, msg) = JarvisNotificationListener.dismissNotification(query)
                ToolExecutionResult(name, dismissed, msg)
            }
            else -> ToolExecutionResult(name, false, "Unsupported notification action '$action'.")
        }
    }
}
