package com.example.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val title: String = "New Conversation",
    val summary: String? = null
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["timestamp"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long = 1L,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_facts",
    indices = [
        Index(value = ["category", "key"]),
        Index(value = ["supersededById"]),
        Index(value = ["pinned", "importance", "lastUsedAt"])
    ]
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val key: String,
    val value: String,
    val source: String = "USER_EXPLICIT",
    val confidence: Float = 1.0f,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 0,
    val supersededById: Long? = null
)

@Fts4(contentEntity = MemoryFactEntity::class)
@Entity(tableName = "memory_facts_fts")
data class MemoryFactFtsEntity(
    val key: String,
    val value: String
)

@Entity(
    tableName = "summaries",
    indices = [Index(value = ["conversationId"])]
)
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val fromMessageId: Long,
    val toMessageId: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
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
