package com.example.tools

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult
import com.example.domain.tool.Tool
import com.example.services.JarvisAccessibilityService

@Singleton
class TypeTextTool @Inject constructor() : Tool {
    override val name: String = "type_text"
    override val description: String = "Types text into the currently focused or matched editable input field (password fields are strictly blocked)."
    override val jsonSchema: String = """{"type":"object","properties":{"text":{"type":"string","description":"Text to enter"},"target_hint":{"type":"string","description":"Optional input field hint"}},"required":["text"]}"""
    override val riskLevel: RiskLevel = RiskLevel.MEDIUM
    override val requiresFullControlMode: Boolean = true

    override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
        val text = args["text"]?.trim().orEmpty()
        val hint = args["target_hint"]?.trim()
        if (text.isEmpty()) {
            return ToolExecutionResult(
                toolName = name,
                success = false,
                outputEn = "Missing 'text' parameter.",
                outputHi = "टाइप करने के लिए 'text' खाली नहीं हो सकता।"
            )
        }
        val (ok, msg) = JarvisAccessibilityService.executeTypeText(text, hint)
        val outputHi = when {
            ok -> "इनपुट बॉक्स में '$text' टाइप कर दिया गया है।"
            msg == JarvisAccessibilityService.ACCESSIBILITY_DISABLED_EN -> JarvisAccessibilityService.ACCESSIBILITY_DISABLED_HI
            else -> "टाइप करने में त्रुटि: $msg"
        }
        return ToolExecutionResult(
            toolName = name,
            success = ok,
            outputEn = msg,
            outputHi = outputHi
        )
    }
}
