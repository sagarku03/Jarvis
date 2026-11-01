package com.example.tools

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
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

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "No camera flashlight feature found on this device.",
                outputHi = "इस डिवाइस पर टॉर्च (फ्लैशलाइट) की सुविधा उपलब्ध नहीं है।"
            )
        }

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolExecutionResult(
                    toolName = name,
                    success = false,
                    outputEn = "Camera service is unavailable.",
                    outputHi = "कैमरा सेवा अनुपलब्ध है।"
                )

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                runCatching {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            } ?: cameraManager.cameraIdList.firstOrNull()
            ?: return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "No camera with flashlight found on this device.",
                outputHi = "इस डिवाइस पर टॉर्च वाला कैमरा नहीं मिला।"
            )

            cameraManager.setTorchMode(cameraId, turnOn)
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = if (turnOn) "Flashlight turned on." else "Flashlight turned off.",
                outputHi = if (turnOn) "टॉर्च चालू कर दी गई है।" else "टॉर्च बंद कर दी गई है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not toggle flashlight: ${e.message ?: "hardware unavailable"}.",
                outputHi = "टॉर्च चालू/बंद करने में विफल: ${e.message ?: "हार्डवेयर अनुपलब्ध"}।"
            )
        }
    }
}
