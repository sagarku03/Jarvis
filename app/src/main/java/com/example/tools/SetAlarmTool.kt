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
    override val description: String = "Set an alarm in the system Clock app for a given hour and minute."
    override val jsonSchema: String = """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val hour = args["hour"]?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = args["minute"]?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "Jarvis Alarm"
        val formattedTime = String.format("%02d:%02d", hour, minute)

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(name, false, "No Clock app is available on this device to set an alarm.")
            }
            context.startActivity(intent)
            ToolExecutionResult(name, true, "Alarm '$label' set for $formattedTime.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to set alarm: ${e.localizedMessage ?: "error"}.")
        }
    }
}
