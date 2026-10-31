package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.contacts.ContactsResolver
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class WhatsAppTelegramTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "send_whatsapp_or_telegram"
    override val description: String = "Sends a WhatsApp or Telegram message to a resolved contact after HIGH-risk SafetyGate confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"app":{"type":"string","description":"whatsapp or telegram"},"contact_name":{"type":"string"},"message":{"type":"string"},"phone_number":{"type":"string"}},"required":["contact_name","message"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val appChoice = (args["app"] ?: args["platform"] ?: "whatsapp").trim().lowercase()
        val contactName = (args["contact_name"] ?: args["recipient"]).orEmpty().trim()
        val messageBody = args["message"]?.trim().orEmpty()
        val explicitPhone = args["phone_number"]?.trim().orEmpty()

        val targetCandidate = if (explicitPhone.isNotBlank()) {
            com.example.domain.model.ContactCandidate(
                displayName = contactName.ifEmpty { explicitPhone },
                phoneNumber = explicitPhone,
                maskedNumber = ContactsResolver.maskPhoneNumber(explicitPhone)
            )
        } else {
            val candidates = contactsResolver.resolveCandidates(contactName)
            if (candidates.isEmpty()) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Contact '$contactName' was not found in Contacts or Memory."
                )
            }
            candidates.first()
        }

        val cleanDigits = targetCandidate.phoneNumber.filter { it.isDigit() }

        return try {
            if (appChoice.contains("telegram")) {
                val tgIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("org.telegram.messenger")
                    putExtra(Intent.EXTRA_TEXT, messageBody)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolvedIntent = if (tgIntent.resolveActivity(context.packageManager) != null) {
                    tgIntent
                } else {
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://t.me/+$cleanDigits")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                }
                if (resolvedIntent.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "Telegram is not installed on this device."
                    )
                }
                context.startActivity(resolvedIntent)
                ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Opened Telegram for ${targetCandidate.displayName} (${targetCandidate.maskedNumber}): \"$messageBody\"",
                    requiresFollowUpScreenRead = true
                )
            } else {
                val waUri = Uri.parse(
                    "https://api.whatsapp.com/send?phone=$cleanDigits&text=${Uri.encode(messageBody)}"
                )
                val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val finalIntent = if (waIntent.resolveActivity(context.packageManager) != null) {
                    waIntent
                } else {
                    Intent(Intent.ACTION_VIEW, waUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                if (finalIntent.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "WhatsApp is not installed on this device."
                    )
                }
                context.startActivity(finalIntent)
                ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Opened WhatsApp chat for ${targetCandidate.displayName} (${targetCandidate.maskedNumber}): \"$messageBody\"",
                    requiresFollowUpScreenRead = true
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to open $appChoice: ${e.localizedMessage}"
            )
        }
    }
}
