package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.contact.ContactResolutionResult
import com.example.domain.contact.ContactsResolver
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class WhatsAppTelegramTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "send_whatsapp_or_telegram"
    override val description: String = "Composes a WhatsApp or Telegram message to a verified contact after HIGH-risk SafetyGate confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"platform":{"type":"string","description":"whatsapp or telegram"},"recipient":{"type":"string","description":"Phone number or contact"},"message":{"type":"string","description":"Message text"}},"required":["platform","recipient","message"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val platform = args["platform"]?.trim()?.lowercase() ?: "whatsapp"
        val recipientQuery = args["recipient"]?.trim().orEmpty()
        val message = args["message"]?.trim().orEmpty()

        if (recipientQuery.isEmpty() || message.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Both 'recipient' and 'message' are required.",
                outputHi = "प्राप्तकर्ता और संदेश दोनों आवश्यक हैं।"
            )
        }

        val resolved = when (val res = contactsResolver.resolve(recipientQuery)) {
            is ContactResolutionResult.NotFound -> {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = res.messageEn,
                    outputHi = res.messageHi
                )
            }
            is ContactResolutionResult.Ambiguous -> {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = res.messageEn(),
                    outputHi = res.messageHi()
                )
            }
            is ContactResolutionResult.Resolved -> res.contact
        }

        val sanitizedDigits = resolved.phoneNumber.removePrefix("+")
        if (sanitizedDigits.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Resolved contact '${resolved.displayName}' does not have a valid phone number.",
                outputHi = "'${resolved.displayName}' के लिए कोई मान्य फ़ोन नंबर नहीं मिला।"
            )
        }

        return try {
            if (platform == "telegram") {
                val tgUri = Uri.parse("tg://msg?text=${Uri.encode(message)}&to=${Uri.encode(resolved.phoneNumber)}")
                val tgIntent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                    setPackage("org.telegram.messenger")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tgIntent.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "Telegram is not installed on this device.",
                        outputHi = "इस फ़ोन पर टेलीग्राम ऐप इंस्टॉल नहीं है।"
                    )
                }
                context.startActivity(tgIntent)
                ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Prepared Telegram message to ${resolved.displayName} (${resolved.maskedNumber}).",
                    outputHi = "${resolved.displayName} (${resolved.maskedNumber}) के लिए टेलीग्राम संदेश तैयार किया गया।"
                )
            } else {
                val waUri = Uri.parse(
                    "https://api.whatsapp.com/send?phone=${Uri.encode(sanitizedDigits)}&text=${Uri.encode(message)}"
                )
                val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (waIntent.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "WhatsApp is not installed on this device.",
                        outputHi = "इस फ़ोन पर व्हाट्सएप इंस्टॉल नहीं है।"
                    )
                }
                context.startActivity(waIntent)
                ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Prepared WhatsApp message to ${resolved.displayName} (${resolved.maskedNumber}).",
                    outputHi = "${resolved.displayName} (${resolved.maskedNumber}) के लिए व्हाट्सएप संदेश तैयार किया गया।"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not launch $platform: ${e.localizedMessage}",
                outputHi = "$platform खोलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
