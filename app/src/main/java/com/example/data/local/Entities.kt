package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_items")
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val keyLabel: String,
    val encryptedValue: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val argsJson: String,
    val riskLevel: String,
    val status: String,
    val resultSummary: String
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val triggerType: String,
    val triggerValue: String,
    val actionsPrompt: String,
    val enabled: Boolean = true,
    val lastRunTimestamp: Long? = null
)
