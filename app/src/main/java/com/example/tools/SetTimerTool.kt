package com.example.tools

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
    override val description: String = "Starts a countdown timer for the given duration in seconds."
    override val jsonSchema: String = """{"type":"object","properties":{"seconds":{"type":"string","description":"Countdown duration in seconds"},"label":{"type":"string","description":"Timer label"}},"required":["seconds"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val seconds = args["seconds"]?.toIntOrNull()?.coerceAtLeast(1) ?: 60
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "JARVIS Timer"

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No Clock/Timer app is installed on this device to start the timer.",
                    outputHi = "टाइमर शुरू करने के लिए इस फ़ोन पर कोई घड़ी ऐप उपलब्ध नहीं है।"
                )
            }
            context.startActivity(intent)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Started timer '$label' for $seconds seconds.",
                outputHi = "$seconds सेकंड के लिए '$label' टाइमर शुरू कर दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not start timer: ${e.localizedMessage}",
                outputHi = "टाइमर शुरू करने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
