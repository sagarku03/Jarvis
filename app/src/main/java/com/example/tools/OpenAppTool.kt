package com.example.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool

@Singleton
class OpenAppTool @Inject constructor(
    private val context: Context
) : Tool {
    override val name: String = "open_app"
    override val description: String = "Opens an installed application on the phone by app name or package name."
    override val jsonSchema: String = """{"type":"object","properties":{"app_name":{"type":"string","description":"Name or package of the app to launch"}},"required":["app_name"]}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetApp = args["app_name"]?.trim().orEmpty()
        if (targetApp.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'app_name' parameter.",
                outputHi = "'app_name' पैरामीटर नहीं मिला।"
            )
        }

        return try {
            val pm = context.packageManager
            val lower = targetApp.lowercase()

            if (lower == "settings" || lower == "सेटिंग्स") {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (settingsIntent.resolveActivity(pm) == null) {
                    return ToolExecutionResult(
                        toolName = name,
                        success = false,
                        outputEn = "System Settings app could not be resolved.",
                        outputHi = "सिस्टम सेटिंग्स ऐप नहीं मिला।"
                    )
                }
                context.startActivity(settingsIntent)
                return ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Opened Android System Settings.",
                    outputHi = "सिस्टम सेटिंग्स खोल दी गई है।"
                )
            }

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val matchedInfo = resolveInfos.firstOrNull { info ->
                val label = info.loadLabel(pm).toString()
                label.contains(targetApp, ignoreCase = true) ||
                    info.activityInfo.packageName.contains(targetApp, ignoreCase = true)
            }

            if (matchedInfo != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matchedInfo.activityInfo.packageName)
                if (launchIntent != null && launchIntent.resolveActivity(pm) != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val appLabel = matchedInfo.loadLabel(pm).toString()
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Launched $appLabel (${matchedInfo.activityInfo.packageName}).",
                        outputHi = "$appLabel ऐप खोल दिया गया है।"
                    )
                }
            }

            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "App '$targetApp' is not installed on this device.",
                outputHi = "'$targetApp' ऐप इस फ़ोन पर इंस्टॉल नहीं है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Could not open '$targetApp': ${e.localizedMessage}",
                outputHi = "'$targetApp' खोलने में त्रुटि: ${e.localizedMessage}"
            )
        }
    }
}
