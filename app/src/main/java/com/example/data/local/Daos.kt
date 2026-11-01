package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY startedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(entity: ConversationEntity): Long

    @Query("UPDATE conversations SET endedAt = :endedAt WHERE id = :id")
    suspend fun markConversationEnded(id: Long, endedAt: Long)

    @Query("UPDATE conversations SET title = :title, summary = :summary, endedAt = COALESCE(endedAt, :endedAt) WHERE id = :id")
    suspend fun updateTitleAndSummary(id: Long, title: String, summary: String, endedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("SELECT MAX(timestamp) FROM messages WHERE conversationId = :conversationId")
    suspend fun getLastMessageTimestamp(conversationId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries ORDER BY createdAt DESC")
    fun observeSummaries(): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int): List<SummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(entity: SummaryEntity): Long

    @Query("DELETE FROM summaries WHERE conversationId = :conversationId")
    suspend fun deleteSummariesForConversation(conversationId: Long)

    @Query("DELETE FROM summaries")
    suspend fun clearAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_facts WHERE supersededById IS NULL ORDER BY pinned DESC, importance DESC, updatedAt DESC")
    fun observeActiveMemories(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE supersededById IS NULL AND source = 'AUTO_LEARNED' AND createdAt >= :sinceEpochMs ORDER BY createdAt DESC")
    fun observeLearnedSince(sinceEpochMs: Long): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE supersededById IS NULL ORDER BY pinned DESC, importance DESC, updatedAt DESC")
    suspend fun getAllActiveMemoriesSnapshot(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY id ASC")
    suspend fun getAllMemoriesIncludingSuperseded(): List<MemoryFactEntity>

    @Query(
        """
        SELECT memory_facts.* FROM memory_facts
        JOIN memory_facts_fts ON memory_facts.id = memory_facts_fts.rowid
        WHERE memory_facts_fts MATCH :ftsQuery AND memory_facts.supersededById IS NULL
        ORDER BY memory_facts.pinned DESC, memory_facts.importance DESC, memory_facts.lastUsedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(ftsQuery: String, limit: Int): List<MemoryFactEntity>

    @Query(
        """
        SELECT * FROM memory_facts
        WHERE supersededById IS NULL
          AND (key LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%')
        ORDER BY pinned DESC, importance DESC, updatedAt DESC
        """
    )
    fun searchMemories(query: String): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE supersededById IS NULL AND category = :category AND LOWER(key) = LOWER(:key) LIMIT 1")
    suspend fun findActiveByCategoryAndKey(category: String, key: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MemoryFactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(entity: MemoryFactEntity): Long

    @Query("UPDATE memory_facts SET supersededById = :newId, updatedAt = :nowMs WHERE id = :oldId")
    suspend fun markSuperseded(oldId: Long, newId: Long, nowMs: Long)

    @Query("UPDATE memory_facts SET lastUsedAt = :nowMs, useCount = useCount + 1 WHERE id IN (:ids)")
    suspend fun recordUsage(ids: List<Long>, nowMs: Long)

    @Query("UPDATE memory_facts SET pinned = :pinned, updatedAt = :nowMs WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, nowMs: Long)

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clearAll()

    @Query("INSERT INTO memory_facts_fts(memory_facts_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestampMs DESC LIMIT 250")
    fun observeLogs(): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entity: ActivityLogEntity): Long

    @Query("DELETE FROM activity_logs")
    suspend fun clearAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY id DESC")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(entity: RoutineEntity): Long

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutineById(id: Long)

    @Query("UPDATE routines SET lastRunAtMs = :timestampMs WHERE id = :id")
    suspend fun updateLastRun(id: Long, timestampMs: Long)

    @Query("DELETE FROM routines")
    suspend fun clearAll()
}
