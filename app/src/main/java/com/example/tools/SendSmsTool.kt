package com.example.tools

import android.content.ActivityNotFoundException
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
class SendSmsTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "send_sms"
    override val description: String = "Opens the SMS composer with pre-filled recipient and message text after SafetyGate confirmation."
    override val jsonSchema: String = """{"type":"object","properties":{"contact_name":{"type":"string"},"message":{"type":"string"},"phone_number":{"type":"string"}},"required":["contact_name","message"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
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
                    outputEn = "Contact '$contactName' was not found in Contacts or Memory.",
                    outputHi = "संपर्क '$contactName' आपके कॉन्टैक्ट्स या मेमोरी में नहीं मिला।"
                )
            }
            candidates.first()
        }

        return try {
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(targetCandidate.phoneNumber)}")
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(smsIntent)
            } catch (e: ActivityNotFoundException) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No SMS messaging app is installed on this device.",
                    outputHi = "इस डिवाइस पर कोई एसएमएस मैसेजिंग ऐप इंस्टॉल नहीं है।"
                )
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Prepared SMS to ${targetCandidate.displayName} (${targetCandidate.maskedNumber}): \"$messageBody\"",
                outputHi = "${targetCandidate.displayName} (${targetCandidate.maskedNumber}) के लिए एसएमएस तैयार किया गया: \"$messageBody\""
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to prepare SMS: ${e.localizedMessage ?: "error"}",
                outputHi = "एसएमएस तैयार करने में विफल: ${e.localizedMessage ?: "त्रुटि"}"
            )
        }
    }
}
