package com.example.tools

import android.content.ActivityNotFoundException
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
    override val description: String = "Launch an installed Android application by app name, package name, or system settings intent action."
    override val jsonSchema: String = """{"type":"object","properties":{"appName":{"type":"string"},"app_name":{"type":"string"}}}"""
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val targetApp = (args["appName"] ?: args["app_name"]).orEmpty().trim()
        if (targetApp.isBlank()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Parameter 'appName' is required.",
                outputHi = "ऐप का नाम (appName) आवश्यक है।"
            )
        }

        return try {
            // Check direct settings actions
            if (targetApp.startsWith("android.settings.") || targetApp.startsWith("android.intent.action.")) {
                val intent = Intent(targetApp).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try {
                    context.startActivity(intent)
                    val label = targetApp.substringAfterLast(".").replace("_", " ").lowercase()
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened $label.",
                        outputHi = "$label खोल दिया गया है।"
                    )
                } catch (e: ActivityNotFoundException) {
                    val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened System Settings.",
                        outputHi = "सिस्टम सेटिंग्स खोल दी गई हैं।"
                    )
                }
            }

            val lowerTarget = targetApp.lowercase()
            when {
                lowerTarget == "settings" || lowerTarget == "system settings" -> {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened System Settings.",
                        outputHi = "सिस्टम सेटिंग्स खोल दी गई हैं।"
                    )
                }
                lowerTarget == "wifi" || lowerTarget == "wi-fi" || lowerTarget == "wifi settings" -> {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened Wi-Fi Settings.",
                        outputHi = "वाई-फाई सेटिंग्स खोल दी गई हैं।"
                    )
                }
                lowerTarget == "bluetooth" || lowerTarget == "bluetooth settings" -> {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened Bluetooth Settings.",
                        outputHi = "ब्लूटूथ सेटिंग्स खोल दी गई हैं।"
                    )
                }
            }

            val canonicalPackages = mapOf(
                "youtube" to "com.google.android.youtube",
                "whatsapp" to "com.whatsapp",
                "instagram" to "com.instagram.android",
                "chrome" to "com.android.chrome",
                "google maps" to "com.google.android.apps.maps",
                "maps" to "com.google.android.apps.maps",
                "spotify" to "com.spotify.music",
                "telegram" to "org.telegram.messenger",
                "gmail" to "com.google.android.gm"
            )
            val resolvedPkg = canonicalPackages[lowerTarget] ?: targetApp

            val pm = context.packageManager
            val directLaunch = pm.getLaunchIntentForPackage(resolvedPkg) ?: pm.getLaunchIntentForPackage(targetApp)
            if (directLaunch != null) {
                directLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directLaunch)
                return ToolExecutionResult(
                    toolName = name,
                    success = true,
                    outputEn = "Opened $targetApp.",
                    outputHi = "$targetApp खोल दिया गया है।"
                )
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
                    return ToolExecutionResult(
                        toolName = name,
                        success = true,
                        outputEn = "Opened $appLabel.",
                        outputHi = "$appLabel खोल दिया गया है।"
                    )
                }
            }

            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "App '$targetApp' is not installed on this device.",
                outputHi = "ऐप '$targetApp' इस डिवाइस पर इंस्टॉल नहीं है।"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Failed to open '$targetApp': ${e.localizedMessage ?: "error"}.",
                outputHi = "'$targetApp' खोलने में विफल: ${e.localizedMessage ?: "त्रुटि"}।"
            )
        }
    }
}
