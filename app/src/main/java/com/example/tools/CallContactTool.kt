package com.example.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.contacts.ContactsResolver
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class CallContactTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "call_contact"
    override val description: String = "Places a phone call to a contact name or phone number after SafetyGate approval."
    override val jsonSchema: String = """{"type":"object","properties":{"contact_name":{"type":"string","description":"Contact name or phone number"},"phone_number":{"type":"string","description":"Optional resolved phone number"}},"required":["contact_name"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val contactName = (args["contact_name"] ?: args["contact"]).orEmpty().trim()
        val explicitPhone = args["phone_number"]?.trim().orEmpty()

        val targetCandidate = if (explicitPhone.isNotBlank()) {
            val matched = contactsResolver.resolveCandidates(contactName)
                .firstOrNull { it.phoneNumber == explicitPhone }
            matched ?: com.example.domain.model.ContactCandidate(
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
                    outputEn = "Contact '$contactName' was not found in your Contacts or saved Memory facts.",
                    outputHi = "संपर्क '$contactName' आपके कॉन्टैक्ट्स या मेमोरी में नहीं मिला।"
                )
            }
            if (candidates.size > 1) {
                val options = candidates.joinToString(", ") { "${it.displayName} (${it.maskedNumber})" }
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Multiple contacts match '$contactName': $options. Please specify which one to call.",
                    outputHi = "'$contactName' के लिए कई संपर्क मिले: $options। कृपया बताएं किसे कॉल करना है।"
                )
            }
            candidates.first()
        }

        return try {
            val hasCallPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val callIntent = Intent(action).apply {
                data = Uri.parse("tel:${Uri.encode(targetCandidate.phoneNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(callIntent)
            } catch (e: ActivityNotFoundException) {
                if (action == Intent.ACTION_CALL) {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${Uri.encode(targetCandidate.phoneNumber)}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                } else {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "No phone dialer app is available on this device.",
                        outputHi = "इस डिवाइस पर कोई फोन डायलर ऐप उपलब्ध नहीं है।"
                    )
                }
            }

            val verbEn = if (hasCallPerm) "Calling" else "Opened dialer for"
            val verbHi = if (hasCallPerm) "कॉल की जा रही है" else "डायलर खोला गया"

            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "$verbEn ${targetCandidate.displayName} (${targetCandidate.maskedNumber}).",
                outputHi = "$verbHi: ${targetCandidate.displayName} (${targetCandidate.maskedNumber})।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to place call: ${e.localizedMessage ?: "error"}",
                outputHi = "कॉल करने में विफल: ${e.localizedMessage ?: "त्रुटि"}"
            )
        }
    }
}
