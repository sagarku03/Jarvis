package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class NavigateTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "navigate"
    override val description: String = "Opens Google Maps directions/navigation to a destination."
    override val jsonSchema: String = """{"type":"object","properties":{"destination":{"type":"string","description":"Destination address or place name"}},"required":["destination"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val destination = args["destination"]?.trim().orEmpty()
        if (destination.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'destination' parameter.",
                outputHi = "'destination' (गंतव्य) की जानकारी नहीं मिली।"
            )
        }

        return try {
            val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
            } else {
                val webMaps = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (webMaps.resolveActivity(context.packageManager) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "No Maps or browser app is installed to navigate to '$destination'.",
                        outputHi = "'$destination' के लिए कोई मैप्स या ब्राउज़र ऐप उपलब्ध नहीं है।"
                    )
                }
                context.startActivity(webMaps)
            }
            ToolExecutionResult(
                toolName = name,
                success = true,
                outputEn = "Launched Maps navigation for '$destination'.",
                outputHi = "'$destination' के लिए मैप्स नेविगेशन शुरू कर दिया गया है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not open Maps: ${e.localizedMessage}",
                outputHi = "मैप्स खोलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
