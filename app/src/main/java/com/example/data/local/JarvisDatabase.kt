package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryFactEntity::class,
        MemoryFactFtsEntity::class,
        SummaryEntity::class,
        ActivityLogEntity::class,
        RoutineEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
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
                db.execSQL("DELETE FROM `chat_messages` WHERE `content` NOT LIKE 'ENCv1:%'")
                db.execSQL("DELETE FROM `memory_facts` WHERE `value` NOT LIKE 'ENCv1:%'")
                db.execSQL("DELETE FROM `activity_logs` WHERE `argumentsSummary` NOT LIKE 'ENCv1:%'")
            }
        }

        /**
         * Migrates v3 (`chat_messages`, `memory_facts` v3, `activity_logs`, `routines`) to v4:
         * - Creates `conversations`, `messages`, `summaries`, upgraded `memory_facts`, and `memory_facts_fts`.
         * - Decrypts any legacy `ENCv1:` FieldCipher rows from v3 into plain SQLCipher-backed columns so FTS4 works.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                // 1. Create conversations table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `title` TEXT NOT NULL,
                        `summary` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `conversations` (`id`, `startedAt`, `endedAt`, `title`, `summary`) VALUES (1, $now, NULL, 'Initial Conversation', NULL)"
                )

                // 2. Create messages table and migrate legacy chat_messages
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `toolName` TEXT,
                        `toolArgsJson` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")

                runCatching {
                    db.query("SELECT `id`, `role`, `content`, `toolName`, `timestampMs` FROM `chat_messages` ORDER BY `id` ASC").use { cursor ->
                        while (cursor.moveToNext()) {
                            val role = cursor.getString(1).orEmpty()
                            val rawContent = cursor.getString(2).orEmpty()
                            val decryptedContent = decryptIfLegacyEncrypted(rawContent)
                            val toolName = if (cursor.isNull(3)) null else cursor.getString(3)
                            val ts = cursor.getLong(4)
                            db.execSQL(
                                "INSERT INTO `messages` (`conversationId`, `role`, `content`, `toolName`, `toolArgsJson`, `timestamp`) VALUES (?, ?, ?, ?, NULL, ?)",
                                arrayOf<Any?>(1L, role, decryptedContent, toolName, ts)
                            )
                        }
                    }
                    db.execSQL("DROP TABLE IF EXISTS `chat_messages`")
                }

                // 3. Upgrade memory_facts table and migrate existing rows
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_facts_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `supersededById` INTEGER
                    )
                    """.trimIndent()
                )

                runCatching {
                    db.query("SELECT `id`, `category`, `key`, `value`, `updatedAtMs` FROM `memory_facts` ORDER BY `id` ASC").use { cursor ->
                        while (cursor.moveToNext()) {
                            val cat = cursor.getString(1).orEmpty().ifEmpty { "NOTE" }
                            val key = cursor.getString(2).orEmpty()
                            val rawVal = cursor.getString(3).orEmpty()
                            val plainVal = decryptIfLegacyEncrypted(rawVal)
                            val ts = cursor.getLong(4)
                            val pinned = if (cat == "PROFILE" || cat == "CONTACT") 1 else 0
                            db.execSQL(
                                """
                                INSERT INTO `memory_facts_new`
                                (`category`, `key`, `value`, `source`, `confidence`, `importance`, `pinned`, `createdAt`, `updatedAt`, `lastUsedAt`, `useCount`, `supersededById`)
                                VALUES (?, ?, ?, 'USER_EXPLICIT', 1.0, 3, ?, ?, ?, ?, 0, NULL)
                                """.trimIndent(),
                                arrayOf<Any?>(cat, key, plainVal, pinned, ts, ts, ts)
                            )
                        }
                    }
                }

                db.execSQL("DROP TABLE IF EXISTS `memory_facts`")
                db.execSQL("ALTER TABLE `memory_facts_new` RENAME TO `memory_facts`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_category_key` ON `memory_facts` (`category`, `key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_supersededById` ON `memory_facts` (`supersededById`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_pinned_importance_lastUsedAt` ON `memory_facts` (`pinned`, `importance`, `lastUsedAt`)")

                // 4. Create FTS4 virtual table and rebuild index
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `memory_facts_fts` USING FTS4(`key`, `value`, content=`memory_facts`)"
                )
                db.execSQL("INSERT INTO `memory_facts_fts`(`memory_facts_fts`) VALUES('rebuild')")

                // 5. Create summaries table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `summaries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `fromMessageId` INTEGER NOT NULL,
                        `toMessageId` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_summaries_conversationId` ON `summaries` (`conversationId`)")

                // 6. Decrypt any legacy ENCv1: values in activity_logs
                runCatching {
                    val updates = mutableListOf<Pair<Long, String>>()
                    db.query("SELECT `id`, `argumentsSummary` FROM `activity_logs` WHERE `argumentsSummary` LIKE 'ENCv1:%'").use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val raw = cursor.getString(1).orEmpty()
                            updates.add(id to decryptIfLegacyEncrypted(raw))
                        }
                    }
                    for ((id, plain) in updates) {
                        db.execSQL(
                            "UPDATE `activity_logs` SET `argumentsSummary` = ? WHERE `id` = ?",
                            arrayOf<Any?>(plain, id)
                        )
                    }
                }
            }

            private fun decryptIfLegacyEncrypted(value: String): String {
                if (!value.startsWith("ENCv1:")) return value
                return runCatching { FieldCipher.decrypt(value) }.getOrDefault(value.removePrefix("ENCv1:"))
            }
        }
    }
}
