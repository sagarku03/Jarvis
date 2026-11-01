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
    override val jsonSchema: String = """{"type":"object","properties":{"action":{"type":"string","enum":["list","reply","dismiss"]},"senderOrApp":{"type":"string"},"replyText":{"type":"string"},"reply_text":{"type":"string"}},"required":["action"]}"""
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
                        outputEn = "No active notifications found.",
                        outputHi = "कोई सक्रिय सूचना (notification) नहीं मिली।"
                    )
                } else {
                    val summary = items.take(5).joinToString("; ") { item ->
                        "${item.packageName} (${item.title}): ${item.text}"
                    }
                    ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Recent notifications: $summary",
                        outputHi = "हाल की सूचनाएं: $summary"
                    )
                }
            }
            "reply" -> {
                val query = (args["senderOrApp"] ?: args["sender"] ?: args["app"]).orEmpty().trim()
                val replyText = (args["replyText"] ?: args["reply_text"] ?: args["message"]).orEmpty().trim()
                if (replyText.isBlank()) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "Reply text cannot be empty.",
                        outputHi = "उत्तर (replyText) खाली नहीं हो सकता।"
                    )
                }
                val (sent, msg) = JarvisNotificationListener.sendInlineReply(query, replyText)
                val hiMsg = if (sent) "'$query' को सफलतापूर्वक उत्तर भेजा गया: \"$replyText\"" else "उत्तर भेजने में विफल: $msg"
                ToolExecutionResult(toolName = name, success = sent, outputEn = msg, outputHi = hiMsg)
            }
            "dismiss" -> {
                val query = (args["senderOrApp"] ?: args["sender"] ?: args["app"]).orEmpty().trim()
                val (dismissed, msg) = JarvisNotificationListener.dismissNotification(query)
                val hiMsg = if (dismissed) "'$query' की सूचना हटा दी गई।" else "सूचना हटाने में विफल: $msg"
                ToolExecutionResult(toolName = name, success = dismissed, outputEn = msg, outputHi = hiMsg)
            }
            else -> ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Unsupported notification action '$action'.",
                outputHi = "असमर्थित सूचना क्रिया '$action'।"
            )
        }
    }
}
