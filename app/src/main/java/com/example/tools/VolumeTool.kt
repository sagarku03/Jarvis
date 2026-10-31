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

    override val name: String = "set_volume"
    override val description: String = "Set device audio volume percentage for media, ring, or alarm stream."
    override val jsonSchema: String = """{"type":"object","properties":{"stream":{"type":"string","enum":["media","ring","alarm"]},"percent":{"type":"integer"},"level":{"type":"integer"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val streamArg = args["stream"]?.lowercase()?.trim() ?: "media"
        val percent = (args["percent"] ?: args["level"])?.toIntOrNull()?.coerceIn(0, 100) ?: 50

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (streamArg) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val maxVolume = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val targetIndex = ((percent / 100.0) * maxVolume).roundToInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(streamType, targetIndex, 0)
            ToolExecutionResult(name, true, "Set $streamArg volume to $percent%.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to change volume: ${e.localizedMessage ?: "error"}.")
        }
    }
}
