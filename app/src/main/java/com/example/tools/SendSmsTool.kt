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
class SendSmsTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "send_sms"
    override val description: String = "Drafts and opens SMS messaging app with a verified recipient and message after SafetyGate confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"recipient":{"type":"string","description":"Phone number or contact name"},"message":{"type":"string","description":"SMS text content"}},"required":["recipient","message"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val recipientQuery = args["recipient"]?.trim().orEmpty()
        val message = args["message"]?.trim().orEmpty()

        if (recipientQuery.isEmpty() || message.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Both 'recipient' and 'message' are required to draft an SMS.",
                outputHi = "एसएमएस के लिए प्राप्तकर्ता और संदेश दोनों आवश्यक हैं।"
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

        return try {
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(resolved.phoneNumber)}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (smsIntent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No SMS app is installed on this device to handle messages.",
                    outputHi = "इस फ़ोन पर एसएमएस भेजने के लिए कोई ऐप उपलब्ध नहीं है।"
                )
            }
            context.startActivity(smsIntent)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Opened SMS draft to ${resolved.displayName} (${resolved.maskedNumber}).",
                outputHi = "${resolved.displayName} (${resolved.maskedNumber}) के लिए एसएमएस ड्राफ्ट तैयार किया गया।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not open SMS app: ${e.localizedMessage}",
                outputHi = "एसएमएस ऐप खोलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
