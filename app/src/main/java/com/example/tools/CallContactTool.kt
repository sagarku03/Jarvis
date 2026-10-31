package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.contact.ContactResolutionResult
import com.example.domain.contact.ContactsResolver
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class CallContactTool @Inject constructor(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) : Tool {
    override val name: String = "call_contact"
    override val description: String = "Initiates a phone call to a verified contact or explicitly spoken phone number. High-risk action gated by SafetyGate."
    override val jsonSchema: String = """{"type":"object","properties":{"contact":{"type":"string","description":"Phone number or contact name"}},"required":["contact"]}"""
    override val riskLevel: RiskLevel = RiskLevel.HIGH

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val contactQuery = args["contact"]?.trim().orEmpty()
        if (contactQuery.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'contact' parameter for call.",
                outputHi = "कॉल के लिए 'contact' जानकारी नहीं मिली।"
            )
        }

        val resolved = when (val res = contactsResolver.resolve(contactQuery)) {
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

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:${resolved.phoneNumber}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No phone dialer app is installed to handle calls.",
                    outputHi = "कॉल करने के लिए कोई फ़ोन डायलर ऐप उपलब्ध नहीं है।"
                )
            }
            context.startActivity(intent)
            val modeLabel = if (hasCallPerm) "Direct Call" else "Dialer Prepared"
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "$modeLabel initiated for ${resolved.displayName} (${resolved.maskedNumber}).",
                outputHi = "${resolved.displayName} (${resolved.maskedNumber}) के लिए कॉल शुरू किया गया।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to initiate call to ${resolved.displayName}: ${e.localizedMessage}",
                outputHi = "${resolved.displayName} को कॉल लगाने में विफल: ${e.localizedMessage}"
            )
        }
    }
}
