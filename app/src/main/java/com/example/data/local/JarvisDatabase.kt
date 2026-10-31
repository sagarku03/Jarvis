package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        MemoryItemEntity::class,
        ActivityLogEntity::class,
        RoutineEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun routineDao(): RoutineDao
}
