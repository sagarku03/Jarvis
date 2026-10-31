package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesDesc(limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun pruneOldMessages(maxRows: Int = 500)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
}

@Dao
interface MemoryItemDao {
    @Query("SELECT * FROM memory_items ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items ORDER BY updatedAt DESC")
    suspend fun getAllMemoriesSnapshot(): List<MemoryItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(entity: MemoryItemEntity): Long

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memory_items")
    suspend fun clearAllMemories()
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entity: ActivityLogEntity): Long

    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun pruneOldLogs(maxRows: Int = 1000)

    @Query("DELETE FROM activity_logs")
    suspend fun clearAllLogs()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY id ASC")
    fun observeAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY id ASC")
    suspend fun getAllRoutinesSnapshot(): List<RoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(entity: RoutineEntity): Long

    @Query("UPDATE routines SET enabled = :enabled WHERE id = :id")
    suspend fun setRoutineEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE routines SET lastRunTimestamp = :timestamp WHERE id = :id")
    suspend fun markRoutineExecuted(id: Long, timestamp: Long)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutineById(id: Long)

    @Query("DELETE FROM routines")
    suspend fun clearAllRoutines()
}
