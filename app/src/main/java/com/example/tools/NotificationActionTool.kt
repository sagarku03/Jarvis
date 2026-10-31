package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisNotificationListener

@Singleton
class NotificationActionTool @Inject constructor() : Tool {
    override val name: String = "read_or_reply_notifications"
    override val description: String = "Summarizes recent notifications or sends a quick-reply after user confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"mode":{"type":"string","description":"summarize or reply"},"notification_key":{"type":"string"},"reply_text":{"type":"string"}},"required":["mode"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val mode = args["mode"]?.trim()?.lowercase() ?: "summarize"
        if (mode == "reply") {
            val replyText = args["reply_text"]?.trim().orEmpty()
            if (replyText.isEmpty()) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Quick-reply failed: reply_text is empty.",
                    outputHi = "त्वरित जवाब विफल: संदेश खाली है।"
                )
            }
            val key = args["notification_key"]
            val (ok, msg) = JarvisNotificationListener.sendConfirmedQuickReply(key, replyText)
            return ToolExecutionResult(
                toolName = name,
                success = ok,
                outputEn = msg,
                outputHi = if (ok) {
                    "नोटिफिकेशन का त्वरित जवाब भेजा गया: \"$replyText\""
                } else {
                    "नोटिफिकेशन का जवाब नहीं भेजा जा सका: $msg"
                }
            )
        }

        val summary = JarvisNotificationListener.summarizeNotifications()
        return ToolExecutionResult(
            toolName = name,
            success = true,
            outputEn = "Unread notifications: $summary",
            outputHi = "प्रमुख नोटिफिकेशन सारांश: $summary"
        )
    }
}
