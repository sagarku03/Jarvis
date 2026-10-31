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

    override val name: String = "toggle_flashlight"
    override val description: String = "Turn the device camera flashlight (torch) on or off."
    override val jsonSchema: String = """{"type":"object","properties":{"enabled":{"type":"string","description":"true or false"},"state":{"type":"string","enum":["on","off"]}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val rawState = args["enabled"] ?: args["state"] ?: "true"
        val turnOn = rawState.equals("true", ignoreCase = true) || rawState.equals("on", ignoreCase = true)

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolExecutionResult(name, false, "No camera with flashlight found on this device.")
            cameraManager.setTorchMode(cameraId, turnOn)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = if (turnOn) "Flashlight turned on." else "Flashlight turned off."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not toggle flashlight: ${e.message ?: "hardware unavailable"}."
            )
        }
    }
}
