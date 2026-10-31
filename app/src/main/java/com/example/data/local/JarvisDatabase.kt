package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        MemoryFactEntity::class,
        ActivityLogEntity::class,
        RoutineEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun routineDao(): RoutineDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `triggerValue` TEXT NOT NULL,
                        `actionsDelimited` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `lastRunAtMs` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Clear legacy unencrypted rows when upgrading to v3 FieldCipher schema
                db.execSQL("DELETE FROM `chat_messages` WHERE `content` NOT LIKE 'ENCv1:%'")
                db.execSQL("DELETE FROM `memory_facts` WHERE `value` NOT LIKE 'ENCv1:%'")
                db.execSQL("DELETE FROM `activity_logs` WHERE `argumentsSummary` NOT LIKE 'ENCv1:%'")
            }
        }
    }
}
