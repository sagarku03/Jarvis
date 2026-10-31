package com.example.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class FlashlightTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "flashlight"
    override val description: String = "Turns the device flashlight/torch ON or OFF."
    override val jsonSchema: String = """{"type":"object","properties":{"state":{"type":"string","description":"'on' or 'off'"}},"required":["state"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val desiredState = args["state"]?.trim()?.lowercase() ?: "on"
        val enable = desiredState != "off" && desiredState != "false" && desiredState != "0"

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraManager == null || cameraId == null) {
                return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Flashlight hardware is unavailable on this device.",
                    outputHi = "इस डिवाइस पर टॉर्च हार्डवेयर उपलब्ध नहीं है।"
                )
            }
            cameraManager.setTorchMode(cameraId, enable)
            val statusWordEn = if (enable) "ON" else "OFF"
            val statusWordHi = if (enable) "चालू (ON)" else "बंद (OFF)"
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Flashlight switched $statusWordEn.",
                outputHi = "फ़ोन की टॉर्च $statusWordHi कर दी गई है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Flashlight hardware unavailable: ${e.localizedMessage}",
                outputHi = "टॉर्च हार्डवेयर उपलब्ध नहीं है: ${e.localizedMessage}"
            )
        }
    }
}
