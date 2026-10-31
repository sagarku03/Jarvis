package com.example.domain.safety

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppLanguage
import com.example.domain.model.RiskLevel
import com.example.domain.model.SafetyConfirmationRequest
import com.example.domain.model.SafetyPolicyLevel
import com.example.domain.tool.Tool
import com.example.domain.voice.TtsEngineWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class SafetyDecision {
    data object Approved : SafetyDecision()
    data class Denied(
        val reasonEn: String,
        val reasonHi: String
    ) : SafetyDecision()
}

interface SafetyGate {
    val activeConfirmationRequest: StateFlow<SafetyConfirmationRequest?>

    fun isPackageBlocked(packageName: String?): Boolean

    suspend fun evaluateBeforeExecution(
        tool: Tool,
        args: Map<String, String>,
        policyLevel: SafetyPolicyLevel,
        isSecureScreen: Boolean,
        activePackageName: String? = null,
        language: AppLanguage
    ): SafetyDecision

    fun resolveConfirmation(requestId: String, approved: Boolean)
    fun cancelPendingConfirmations()
}

@Singleton
class SafetyGateImpl @Inject constructor(
    private val ttsEngine: TtsEngineWrapper,
    killSwitch: KillSwitch
) : SafetyGate {

    companion object {
        /**
         * Stage 4 Hardened Package Blocklist:
         * Automation and HIGH-risk tools are strictly prohibited when any of these
         * banking, UPI, wallet, password-manager, or system-security packages are in foreground.
         */
        val BLOCKED_SENSITIVE_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay (GPay)
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM UPI
            "com.sbi.lotusintouch",                   // SBI YONO
            "com.snapwork.hdfc",                      // HDFC Bank
            "com.csam.icici.bank.imobile",            // ICICI iMobile
            "com.axis.mobile",                        // Axis Mobile
            "com.x8bit.bitwarden",                    // Bitwarden
            "com.agilebits.onepassword",              // 1Password
            "com.google.android.apps.authenticator2", // Google Authenticator
            "com.google.android.permissioncontroller",// Android Permission Controller
            "com.android.packageinstaller",           // Package Installer
            "com.bank.upi.secure"                     // Simulated Secure Banking Screen
        )
    }

    private val _activeConfirmationRequest = MutableStateFlow<SafetyConfirmationRequest?>(null)
    override val activeConfirmationRequest: StateFlow<SafetyConfirmationRequest?> =
        _activeConfirmationRequest.asStateFlow()

    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        killSwitch.registerCancelCallback {
            cancelPendingConfirmations()
        }
    }

    override fun isPackageBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val normalized = packageName.trim().lowercase()
        return BLOCKED_SENSITIVE_PACKAGES.any { normalized == it.lowercase() } ||
            normalized.contains(".bank") ||
            normalized.contains(".upi") ||
            normalized.contains("passkey")
    }

    override suspend fun evaluateBeforeExecution(
        tool: Tool,
        args: Map<String, String>,
        policyLevel: SafetyPolicyLevel,
        isSecureScreen: Boolean,
        activePackageName: String?,
        language: AppLanguage
    ): SafetyDecision {
        val effectiveRisk = when {
            policyLevel == SafetyPolicyLevel.STRICT && tool.riskLevel == RiskLevel.LOW -> RiskLevel.MEDIUM
            else -> tool.riskLevel
        }

        val packageBlocked = isPackageBlocked(activePackageName)

        // Stage 4: Block any accessibility automation or HIGH-risk tool on secure/banking/UPI screens
        if ((effectiveRisk == RiskLevel.HIGH || tool.requiresFullControlMode) && (isSecureScreen || packageBlocked)) {
            val pkgLabel = activePackageName ?: "secure window"
            return SafetyDecision.Denied(
                reasonEn = "Blocked by SafetyGate: '${tool.name}' cannot run on secure/banking/UPI screen ($pkgLabel).",
                reasonHi = "SafetyGate द्वारा रोका गया: सुरक्षित/बैंकिंग/UPI स्क्रीन ($pkgLabel) पर '${tool.name}' चलाने की अनुमति नहीं है।"
            )
        }

        if (effectiveRisk == RiskLevel.LOW) {
            return SafetyDecision.Approved
        }

        val formattedArgs = if (args.isEmpty()) {
            "no parameters"
        } else {
            args.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        }

        val summaryEn = when (effectiveRisk) {
            RiskLevel.MEDIUM -> "Allow JARVIS to run '${tool.name}' with ($formattedArgs)?"
            RiskLevel.HIGH -> "CRITICAL HIGH-RISK ACTION: '${tool.name}' with ($formattedArgs). Verify carefully before approving."
            RiskLevel.LOW -> ""
        }

        val summaryHi = when (effectiveRisk) {
            RiskLevel.MEDIUM -> "क्या JARVIS को '${tool.name}' ($formattedArgs) चलाने की अनुमति दें?"
            RiskLevel.HIGH -> "उच्च-जोखिम चेतावनी: '${tool.name}' ($formattedArgs)। कृपया ध्यानपूर्वक जांच कर अनुमति दें।"
            RiskLevel.LOW -> ""
        }

        val spokenPrompt = if (language == AppLanguage.HI_IN) {
            "कृपया स्क्रीन पर ${tool.name} की पुष्टि करें।"
        } else {
            "Please confirm ${tool.name.replace('_', ' ')} on screen."
        }
        ttsEngine.speak(spokenPrompt, language)

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingDecisions[requestId] = deferred

        _activeConfirmationRequest.value = SafetyConfirmationRequest(
            requestId = requestId,
            toolName = tool.name,
            riskLevel = effectiveRisk,
            args = args,
            summaryEn = summaryEn,
            summaryHi = summaryHi,
            requiresExplicitHighRiskCheck = effectiveRisk == RiskLevel.HIGH
        )

        val approved = try {
            deferred.await()
        } finally {
            pendingDecisions.remove(requestId)
            if (_activeConfirmationRequest.value?.requestId == requestId) {
                _activeConfirmationRequest.value = null
            }
        }

        return if (approved) {
            SafetyDecision.Approved
        } else {
            SafetyDecision.Denied(
                reasonEn = "Action '${tool.name}' was cancelled by user in SafetyGate.",
                reasonHi = "उपयोगकर्ता ने SafetyGate में '${tool.name}' को रद्द कर दिया।"
            )
        }
    }

    override fun resolveConfirmation(requestId: String, approved: Boolean) {
        pendingDecisions[requestId]?.complete(approved)
    }

    override fun cancelPendingConfirmations() {
        pendingDecisions.values.forEach { it.complete(false) }
        pendingDecisions.clear()
        _activeConfirmationRequest.value = null
    }
}
