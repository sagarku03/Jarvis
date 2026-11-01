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
import java.util.Locale

@Singleton
class SetAlarmTool @Inject constructor(
    private val context: Context
) : Tool {

    override val name: String = "set_alarm"
    override val description: String = "Set an alarm in the system Clock app for a given hour and minute."
    override val jsonSchema: String = """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawHour = args["hour"]?.toIntOrNull()
        val rawMinute = args["minute"]?.toIntOrNull()

        if (rawHour == null) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "What time should I set the alarm for?",
                outputHi = "अलार्म किस समय के लिए सेट करना है?"
            )
        }

        val hour = rawHour.coerceIn(0, 23)
        val minute = (rawMinute ?: 0).coerceIn(0, 59)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "JARVIS Alarm"
        val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
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
                    outputEn = "No Clock app is available on this device to set an alarm.",
                    outputHi = "अलार्म सेट करने के लिए इस डिवाइस पर कोई घड़ी ऐप उपलब्ध नहीं है।"
                )
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Alarm '$label' set for $formattedTime.",
                outputHi = "$formattedTime के लिए अलार्म '$label' सेट कर दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to set alarm: ${e.localizedMessage ?: "error"}.",
                outputHi = "अलार्म सेट करने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
