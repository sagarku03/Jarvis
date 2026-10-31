package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val key: String,
    val value: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val toolName: String,
    val riskLevel: String,
    val argumentsSummary: String,
    val resultSummary: String,
    val userConfirmed: Boolean,
    val durationMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val triggerType: String,
    val triggerValue: String,
    val actionsDelimited: String,
    val enabled: Boolean = true,
    val lastRunAtMs: Long? = null
)
