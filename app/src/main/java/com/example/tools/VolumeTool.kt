package com.example.tools

import android.content.Context
import android.media.AudioManager
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import kotlin.math.roundToInt

@Singleton
class VolumeTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "volume"
    override val description: String = "Sets the device audio volume percentage (0-100) for music, ring, or alarm stream."
    override val jsonSchema: String = """{"type":"object","properties":{"level":{"type":"string","description":"Volume percentage from 0 to 100"},"stream":{"type":"string","description":"music, ring, or alarm"}},"required":["level"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val percent = args["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 60
        val streamArg = args["stream"]?.lowercase() ?: "music"

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val streamType = when (streamArg) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
                val targetIndex = ((percent / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Set $streamArg volume to $percent%.",
                outputHi = "$streamArg की आवाज़ $percent% पर सेट कर दी गई है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to change volume: ${e.localizedMessage}",
                outputHi = "वॉल्यूम बदलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
