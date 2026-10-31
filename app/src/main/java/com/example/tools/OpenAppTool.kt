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
    override val description: String = "Launch an installed Android application by app name or package name."
    override val jsonSchema: String = """{"type":"object","properties":{"appName":{"type":"string"},"app_name":{"type":"string"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetApp = (args["appName"] ?: args["app_name"]).orEmpty().trim()
        if (targetApp.isBlank()) {
            return ToolExecutionResult(name, false, "Parameter 'appName' is required.")
        }

        return try {
            if (targetApp.equals("settings", ignoreCase = true) ||
                targetApp.equals("system settings", ignoreCase = true)
            ) {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return ToolExecutionResult(name, true, "Opened System Settings.")
            }

            val pm = context.packageManager
            val directLaunch = pm.getLaunchIntentForPackage(targetApp)
            if (directLaunch != null) {
                directLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directLaunch)
                return ToolExecutionResult(name, true, "Opened $targetApp.")
            }

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matched = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                val pkg = resolveInfo.activityInfo.packageName
                label.contains(targetApp, ignoreCase = true) || pkg.contains(targetApp, ignoreCase = true)
            }

            if (matched != null) {
                val appLabel = matched.loadLabel(pm).toString()
                val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return ToolExecutionResult(name, true, "Opened $appLabel.")
                }
            }

            ToolExecutionResult(name, false, "App '$targetApp' is not installed on this device.")
        } catch (e: Exception) {
            ToolExecutionResult(name, false, "Failed to open '$targetApp': ${e.localizedMessage ?: "error"}.")
        }
    }
}
