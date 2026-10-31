package com.example.domain.tool

import com.example.BuildConfig
import com.example.di.Inject
import com.example.di.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val registeredTools: Set<@JvmSuppressWildcards Tool>
) {
    fun getAvailableTools(fullControlOverride: Boolean = BuildConfig.FULL_CONTROL_MODE): List<Tool> {
        val allowFullControl = fullControlOverride && !BuildConfig.STORE_SAFE_MODE
        return registeredTools
            .filter { tool -> !tool.requiresFullControlMode || allowFullControl }
            .sortedBy { it.name }
    }

    fun findTool(name: String, fullControlOverride: Boolean = BuildConfig.FULL_CONTROL_MODE): Tool? {
        return getAvailableTools(fullControlOverride).firstOrNull {
            it.name.equals(name.trim(), ignoreCase = true)
        }
    }

    fun getToolDescriptors(fullControlOverride: Boolean = BuildConfig.FULL_CONTROL_MODE): List<ToolDescriptor> {
        return getAvailableTools(fullControlOverride).map { tool ->
            ToolDescriptor(
                name = tool.name,
                description = tool.description,
                jsonSchema = tool.jsonSchema,
                riskLevel = tool.riskLevel.name
            )
        }
    }
}
