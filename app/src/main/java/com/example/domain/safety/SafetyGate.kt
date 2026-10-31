package com.example.domain.safety

import com.example.BuildConfig
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.contacts.ContactsResolver
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConfirmationRequest
import com.example.domain.model.RiskLevel
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ToolCallSpec
import com.example.domain.tool.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class SafetyGateDecision(
    val allowedImmediately: Boolean,
    val requiresConfirmation: Boolean,
    val reasonEn: String,
    val reasonHi: String = reasonEn
)

data class UserApprovalOutcome(
    val approved: Boolean,
    val selectedPhoneOverride: String? = null
)

interface SafetyGate {
    val pendingConfirmation: StateFlow<ConfirmationRequest?>

    fun evaluate(
        toolCall: ToolCallSpec,
        tool: Tool,
        confirmationLevel: ConfirmationLevel,
        screenContext: ScreenContextSnapshot?
    ): SafetyGateDecision

    suspend fun requestUserApproval(
        toolCall: ToolCallSpec,
        tool: Tool,
        reasonEn: String,
        reasonHi: String = reasonEn
    ): UserApprovalOutcome

    fun resolveUserApproval(
        approved: Boolean,
        selectedPhoneOverride: String? = null
    )

    fun cancelPendingConfirmation()

    companion object {
        val BLOCKED_SENSITIVE_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM UPI
            "com.dreamplug.androidapp",               // CRED
            "com.sbi.lotusintouch",                   // YONO SBI
            "com.snapwork.hdfc",                      // HDFC Bank
            "com.csam.icici.bank.imobile",            // iMobile ICICI
            "com.axis.mobile",                        // Axis Mobile
            "com.whatsapp.w4b.payments",              // Payments
            "com.android.settings.password",          // System password prompt
            "com.google.android.apps.authenticator2", // Google Authenticator
            "com.azure.authenticator",                // Microsoft Authenticator
            "com.agilebits.onepassword",              // 1Password
            "com.x8bit.bitwarden"                     // Bitwarden
        )

        private val SENSITIVE_KEYWORDS = listOf(
            "password", "passcode", "upi pin", "mpin", "cvv", "netbanking", "enter pin"
        )

        fun isSensitiveScreen(snapshot: ScreenContextSnapshot?): Boolean {
            if (snapshot == null) return false
            if (snapshot.isSensitiveAppBlocked) return true
            val pkg = snapshot.packageName.lowercase()
            if (BLOCKED_SENSITIVE_PACKAGES.any { pkg.contains(it) }) return true
            val textLower = snapshot.summaryText.lowercase()
            return SENSITIVE_KEYWORDS.any { textLower.contains(it) }
        }

        fun redactSensitiveArgs(args: Map<String, String>): String {
            if (args.isEmpty()) return "{}"
            return args.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                val keyLower = k.lowercase()
                val masked = when {
                    keyLower.contains("password") || keyLower.contains("pin") || keyLower.contains("otp") -> "***REDACTED***"
                    keyLower.contains("phone") || keyLower.contains("number") -> ContactsResolver.maskPhoneNumber(v)
                    else -> v
                }
                "$k=\"$masked\""
            }
        }
    }
}

@Singleton
class SafetyGateImpl @Inject constructor(
    private val contactsResolver: ContactsResolver? = null
) : SafetyGate {

    private val _pendingConfirmation = MutableStateFlow<ConfirmationRequest?>(null)
    override val pendingConfirmation: StateFlow<ConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private var activeDeferred: CompletableDeferred<UserApprovalOutcome>? = null

    override fun evaluate(
        toolCall: ToolCallSpec,
        tool: Tool,
        confirmationLevel: ConfirmationLevel,
        screenContext: ScreenContextSnapshot?
    ): SafetyGateDecision {
        // 1. Block Full-Control Accessibility tools when STORE_SAFE_MODE is enabled
        if (tool.requiresFullControlMode && (BuildConfig.STORE_SAFE_MODE || !BuildConfig.FULL_CONTROL_MODE)) {
            return SafetyGateDecision(
                allowedImmediately = false,
                requiresConfirmation = false,
                reasonEn = "Tool '${tool.name}' is disabled in Store-Safe Mode."
            )
        }

        // 2. Hard block on banking / UPI / password / authenticator screens
        if (SafetyGate.isSensitiveScreen(screenContext) &&
            (tool.requiresFullControlMode || tool.riskLevel == RiskLevel.HIGH)
        ) {
            return SafetyGateDecision(
                allowedImmediately = false,
                requiresConfirmation = false,
                reasonEn = "SafetyGate blocked '${tool.name}' because a banking, UPI, or password screen is active."
            )
        }

        // 3. Evaluate risk matrix vs user's ConfirmationLevel
        return when (tool.riskLevel) {
            RiskLevel.LOW -> {
                if (confirmationLevel == ConfirmationLevel.STRICT) {
                    SafetyGateDecision(
                        allowedImmediately = false,
                        requiresConfirmation = true,
                        reasonEn = formatPromptEn(toolCall, tool.riskLevel)
                    )
                } else {
                    SafetyGateDecision(
                        allowedImmediately = true,
                        requiresConfirmation = false,
                        reasonEn = "Allowed (LOW risk)"
                    )
                }
            }

            RiskLevel.MEDIUM, RiskLevel.HIGH -> {
                SafetyGateDecision(
                    allowedImmediately = false,
                    requiresConfirmation = true,
                    reasonEn = formatPromptEn(toolCall, tool.riskLevel)
                )
            }
        }
    }

    override suspend fun requestUserApproval(
        toolCall: ToolCallSpec,
        tool: Tool,
        reasonEn: String,
        reasonHi: String
    ): UserApprovalOutcome {
        cancelPendingConfirmation()
        val deferred = CompletableDeferred<UserApprovalOutcome>()
        activeDeferred = deferred

        val contactQuery = toolCall.args["contact_name"]
            ?: toolCall.args["contact"]
            ?: toolCall.args["recipient"]
            ?: toolCall.args["phone_number"]
        val candidates = if (!contactQuery.isNullOrBlank() && contactsResolver != null) {
            runCatching { contactsResolver?.resolveCandidates(contactQuery).orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val enrichedEn = if (candidates.size == 1) {
            "$reasonEn\nMatched contact: ${candidates.first().displayName} (${candidates.first().maskedNumber})"
        } else {
            reasonEn
        }

        _pendingConfirmation.value = ConfirmationRequest(
            id = toolCall.id,
            toolCall = toolCall,
            riskLevel = tool.riskLevel,
            summaryEn = enrichedEn,
            countdownSeconds = 15,
            candidateContacts = candidates
        )

        val outcome = withTimeoutOrNull(15_500L) {
            deferred.await()
        } ?: UserApprovalOutcome(approved = false)

        _pendingConfirmation.value = null
        activeDeferred = null
        return outcome
    }

    override fun resolveUserApproval(approved: Boolean, selectedPhoneOverride: String?) {
        activeDeferred?.complete(
            UserApprovalOutcome(
                approved = approved,
                selectedPhoneOverride = selectedPhoneOverride
            )
        )
        _pendingConfirmation.value = null
        activeDeferred = null
    }

    override fun cancelPendingConfirmation() {
        activeDeferred?.complete(UserApprovalOutcome(approved = false))
        activeDeferred = null
        _pendingConfirmation.value = null
    }

    private fun formatPromptEn(toolCall: ToolCallSpec, risk: RiskLevel): String {
        val argsText = SafetyGate.redactSensitiveArgs(toolCall.args)
        return "Confirm ${risk.name} risk action '${toolCall.name}' with $argsText?"
    }
}
