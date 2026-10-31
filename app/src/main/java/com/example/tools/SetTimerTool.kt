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
    override val description: String = "Start a countdown timer in the system Clock app."
    override val jsonSchema: String = """{"type":"object","properties":{"seconds":{"type":"integer"},"durationSeconds":{"type":"integer"},"label":{"type":"string"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val seconds = (args["seconds"] ?: args["durationSeconds"])?.toIntOrNull()?.coerceAtLeast(1) ?: 60
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "Jarvis Timer"

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult(name, false, "No Clock app is available on this device to start a timer.")
            }
            context.startActivity(intent)
            ToolExecutionResult(name, true, "Started timer '$label' for $seconds seconds.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to start timer: ${e.localizedMessage ?: "error"}.")
        }
    }
}
