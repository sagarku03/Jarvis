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
class SetAlarmTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "set_alarm"
    override val description: String = "Sets an alarm for the specified hour (0-23) and minute (0-59)."
    override val jsonSchema: String = """{"type":"object","properties":{"hour":{"type":"string","description":"Hour in 24h format (0-23)"},"minute":{"type":"string","description":"Minute (0-59)"},"label":{"type":"string","description":"Alarm label"}},"required":["hour","minute"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val hour = args["hour"]?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = args["minute"]?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "JARVIS Alarm"

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "No Clock/Alarm app is installed on this device to set the alarm.",
                    outputHi = "अलार्म सेट करने के लिए इस फ़ोन पर कोई घड़ी ऐप उपलब्ध नहीं है।"
                )
            }
            context.startActivity(intent)
            val formattedTime = "%02d:%02d".format(hour, minute)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Alarm '$label' set for $formattedTime.",
                outputHi = "$formattedTime के लिए '$label' अलार्म सेट कर दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to set alarm: ${e.localizedMessage}",
                outputHi = "अलार्म सेट करने में विफल: ${e.localizedMessage}"
            )
        }
    }
}
