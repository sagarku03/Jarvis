package com.example.domain.tool

import com.example.domain.model.RiskLevel
import com.example.domain.model.ToolExecutionResult

interface Tool {
    val name: String
    val description: String
    val jsonSchema: String
    val riskLevel: RiskLevel
    val requiresFullControlMode: Boolean
        get() = false

    suspend fun execute(args: Map<String, String>): ToolExecutionResult
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val jsonSchema: String,
    val riskLevel: String
)
