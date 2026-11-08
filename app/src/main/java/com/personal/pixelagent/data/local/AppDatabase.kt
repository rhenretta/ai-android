package com.personal.pixelagent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        SenderEngagementEntity::class,
        ScreenThreadSnapshotEntity::class,
        NotificationRecordEntity::class,
        ConversationTurnEntity::class,
        TraceLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pixel_agent.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(
                        object : Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) {
                                super.onOpen(db)
                                // Overwrite deleted SQLite pages with zeros on disk for true Private Mode erasure
                                db.query("PRAGMA secure_delete = ON").close()
                            }
                        }
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
