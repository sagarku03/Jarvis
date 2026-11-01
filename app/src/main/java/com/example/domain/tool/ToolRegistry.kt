package com.example.domain.tool

import com.example.BuildConfig
import com.example.di.Inject
import com.example.di.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards Tool>
) {
    fun getAllTools(): List<Tool> = tools.sortedBy { it.name }

    fun getTool(name: String): Tool? {
        val cleanName = name.trim()
        return tools.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
            ?: if (cleanName.equals("set_volume", ignoreCase = true)) tools.firstOrNull { it.name == "adjust_volume" } else null
    }

    fun findTool(name: String, fullControlOverride: Boolean = BuildConfig.FULL_CONTROL_MODE): Tool? {
        val cleanName = name.trim()
        return getAvailableTools(fullControlOverride).firstOrNull {
            it.name.equals(cleanName, ignoreCase = true)
        } ?: if (cleanName.equals("set_volume", ignoreCase = true)) {
            getAvailableTools(fullControlOverride).firstOrNull { it.name == "adjust_volume" }
        } else null
    }

    fun getAvailableTools(fullControlOverride: Boolean = BuildConfig.FULL_CONTROL_MODE): List<Tool> {
        val allowFullControl = fullControlOverride && !BuildConfig.STORE_SAFE_MODE
        return tools
            .filter { tool -> !tool.requiresFullControlMode || allowFullControl }
            .sortedBy { it.name }
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

    fun getAvailableDescriptors(
        fullControlMode: Boolean,
        storeSafeMode: Boolean
    ): List<ToolDescriptor> {
        val allowAccessibility = fullControlMode && !storeSafeMode
        return tools
            .filter { !it.requiresFullControlMode || allowAccessibility }
            .sortedBy { it.name }
            .map { tool ->
                ToolDescriptor(
                    name = tool.name,
                    description = tool.description,
                    jsonSchema = tool.jsonSchema,
                    riskLevel = tool.riskLevel.name
                )
            }
    }
}
