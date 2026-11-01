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

    override val name: String = "adjust_volume"
    override val description: String = "Adjust or set device audio volume percentage, or change volume direction (up, down, mute, unmute) for media, ring, or alarm stream."
    override val jsonSchema: String = """{"type":"object","properties":{"stream":{"type":"string","enum":["media","ring","alarm","notification"]},"level_percent":{"type":"integer","description":"Volume percentage from 0 to 100"},"percent":{"type":"integer"},"level":{"type":"integer"},"direction":{"type":"string","enum":["up","down","mute","unmute"]}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val streamArg = (args["stream"] ?: "media").lowercase().trim()
        val direction = args["direction"]?.lowercase()?.trim()
        val rawPercent = args["level_percent"] ?: args["percent"] ?: args["level"]

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Audio service is not available on this device.",
                    outputHi = "इस डिवाइस पर ऑडियो सेवा उपलब्ध नहीं है।"
                )

            val streamType = when (streamArg) {
                "ring", "call", "voice" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }

            if (!direction.isNullOrBlank()) {
                when (direction) {
                    "up", "increase", "raise" -> {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        return ToolExecutionResult(
                            toolName = name,
                            success = true,
                            outputEn = "Volume increased.",
                            outputHi = "वॉल्यूम बढ़ा दिया गया है।"
                        )
                    }
                    "down", "decrease", "lower" -> {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        return ToolExecutionResult(
                            toolName = name,
                            success = true,
                            outputEn = "Volume decreased.",
                            outputHi = "वॉल्यूम कम कर दिया गया है।"
                        )
                    }
                    "mute", "silent" -> {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        return ToolExecutionResult(
                            toolName = name,
                            success = true,
                            outputEn = "Volume muted.",
                            outputHi = "वॉल्यूम म्यूट कर दिया गया है।"
                        )
                    }
                    "unmute" -> {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                        return ToolExecutionResult(
                            toolName = name,
                            success = true,
                            outputEn = "Volume unmuted.",
                            outputHi = "वॉल्यूम अनम्यूट कर दिया गया है।"
                        )
                    }
                }
            }

            val percent = rawPercent?.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val maxVolume = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val targetIndex = ((percent / 100.0) * maxVolume).roundToInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)

            val streamLabel = when (streamType) {
                AudioManager.STREAM_RING -> "Ring"
                AudioManager.STREAM_ALARM -> "Alarm"
                AudioManager.STREAM_NOTIFICATION -> "Notification"
                else -> "Media"
            }
            val streamLabelHi = when (streamType) {
                AudioManager.STREAM_RING -> "रिंग"
                AudioManager.STREAM_ALARM -> "अलार्म"
                AudioManager.STREAM_NOTIFICATION -> "नोटिफिकेशन"
                else -> "मीडिया"
            }

            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "$streamLabel volume set to $percent%.",
                outputHi = "$streamLabelHi वॉल्यूम $percent% पर सेट कर दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to change volume: ${e.localizedMessage ?: "error"}.",
                outputHi = "वॉल्यूम बदलने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
