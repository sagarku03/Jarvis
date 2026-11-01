package com.example.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class SetTimerTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "set_timer"
    override val description: String = "Start a countdown timer in the system Clock app."
    override val jsonSchema: String = """{"type":"object","properties":{"seconds":{"type":"integer"},"durationSeconds":{"type":"integer"},"label":{"type":"string"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawSeconds = (args["seconds"] ?: args["durationSeconds"])?.toIntOrNull()
        if (rawSeconds == null) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "How long should I set the timer for?",
                outputHi = "टाइमर कितनी देर के लिए सेट करना है?"
            )
        }

        val seconds = rawSeconds.coerceAtLeast(1)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "JARVIS Timer"

        val durationLabel = when {
            seconds >= 3600 -> "${seconds / 3600} hour"
            seconds >= 60 -> "${seconds / 60} minute"
            else -> "$seconds second"
        } + if (seconds > 60 && (seconds % 60 == 0 || seconds % 3600 == 0)) "s" else ""

        val durationLabelHi = when {
            seconds >= 3600 -> "${seconds / 3600} घंटे"
            seconds >= 60 -> "${seconds / 60} मिनट"
            else -> "$seconds सेकंड"
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No Clock app is available on this device to start a timer.",
                    outputHi = "टाइमर शुरू करने के लिए इस डिवाइस पर कोई घड़ी ऐप उपलब्ध नहीं है।"
                )
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Started timer '$label' for $durationLabel ($seconds seconds).",
                outputHi = "$label के लिए $durationLabelHi का टाइमर शुरू किया गया।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to start timer: ${e.localizedMessage ?: "error"}.",
                outputHi = "टाइमर शुरू करने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
