package com.personal.pixelagent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.TypeConverter
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

class RoomTypeConverters {
    @TypeConverter
    fun fromMemoryCategory(value: MemoryCategory): String = value.name

    @TypeConverter
    fun toMemoryCategory(value: String): MemoryCategory = MemoryCategory.valueOf(value)

    @TypeConverter
    fun fromTriageTier(value: TriageTier): String = value.name

    @TypeConverter
    fun toTriageTier(value: String): TriageTier = TriageTier.valueOf(value)

    @TypeConverter
    fun fromTurnModality(value: TurnModality): String = value.name

    @TypeConverter
    fun toTurnModality(value: String): TurnModality = TurnModality.valueOf(value)

    @TypeConverter
    fun fromTurnRole(value: TurnRole): String = value.name

    @TypeConverter
    fun toTurnRole(value: String): TurnRole = TurnRole.valueOf(value)

    @TypeConverter
    fun fromUndoActionType(value: UndoActionType): String = value.name

    @TypeConverter
    fun toUndoActionType(value: String): UndoActionType = UndoActionType.valueOf(value)
}

@Dao
interface AgentDao {

    // ==================== MEMORIES ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun getMemoryById(memoryId: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE active = 1 ORDER BY updatedAt DESC")
    fun observeActiveMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE active = 1 ORDER BY updatedAt DESC")
    suspend fun getActiveMemories(): List<MemoryEntity>

    @Query(
        """
        UPDATE memories
        SET category = :category,
            content = :content,
            sourceSummary = :sourceSummary,
            embeddingBlob = :embeddingBlob,
            active = 1,
            updatedAt = :now
        WHERE id = :memoryId
        """
    )
    suspend fun updateMemoryById(
        memoryId: Long,
        category: MemoryCategory,
        content: String,
        sourceSummary: String,
        embeddingBlob: ByteArray?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE memories SET active = 0, updatedAt = :now WHERE id = :memoryId")
    suspend fun deactivateMemory(memoryId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET active = 1, updatedAt = :now WHERE id = :memoryId")
    suspend fun reactivateMemory(memoryId: Long, now: Long = System.currentTimeMillis())

    // ==================== NOTIFICATIONS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationRecord(record: NotificationRecordEntity): Long

    @Query("SELECT * FROM notification_records WHERE id = :id LIMIT 1")
    suspend fun getNotificationById(id: Long): NotificationRecordEntity?

    @Query(
        """
        SELECT * FROM notification_records
        WHERE tier = 'SUMMARY' AND clearedFromDigest = 0
        ORDER BY postedAt DESC
        """
    )
    fun observeActiveDigestItems(): Flow<List<NotificationRecordEntity>>

    @Query(
        """
        SELECT * FROM notification_records
        WHERE tier = 'SUMMARY' AND clearedFromDigest = 0
        ORDER BY postedAt DESC
        """
    )
    suspend fun getActiveDigestItems(): List<NotificationRecordEntity>

    @Query(
        """
        SELECT * FROM notification_records
        WHERE tier = 'SPAM'
        ORDER BY postedAt DESC
        LIMIT 200
        """
    )
    fun observeSpamRecords(): Flow<List<NotificationRecordEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM notification_records
        WHERE tier = 'SPAM' AND postedAt >= :sinceMillis
        """
    )
    fun observeSpamCountSince(sinceMillis: Long): Flow<Int>

    @Query("UPDATE notification_records SET clearedFromDigest = 1 WHERE tier = 'SUMMARY' AND clearedFromDigest = 0")
    suspend fun clearActiveDigest()

    @Query(
        """
        SELECT * FROM notification_records
        WHERE packageName = :packageName
          AND clearedFromDigest = 0
          AND tier IN ('URGENT', 'PASSTHROUGH_ERROR', 'SUMMARY')
        ORDER BY postedAt DESC
        LIMIT 50
        """
    )
    suspend fun getUnseenNotificationsForPackage(packageName: String): List<NotificationRecordEntity>

    @Query(
        """
        SELECT * FROM notification_records
        WHERE clearedFromDigest = 0
          AND tier IN ('URGENT', 'PASSTHROUGH_ERROR', 'SUMMARY')
        ORDER BY postedAt DESC
        LIMIT 100
        """
    )
    suspend fun getAllUnseenNotifications(): List<NotificationRecordEntity>

    @Query("UPDATE notification_records SET clearedFromDigest = 1 WHERE id IN (:ids)")
    suspend fun markNotificationsSeen(ids: List<Long>)

    @Query("UPDATE notification_records SET tier = :newTier, clearedFromDigest = :cleared WHERE id = :id")
    suspend fun updateNotificationTier(id: Long, newTier: TriageTier, cleared: Boolean = false)

    @Query("DELETE FROM notification_records WHERE tier = 'SPAM'")
    suspend fun purgeAllSpam()

    @Query(
        """
        SELECT * FROM notification_records
        WHERE (:tierFilter IS NULL OR tier = :tierFilter)
          AND (
              :searchQuery = '' OR
              title LIKE '%' || :searchQuery || '%' OR
              body LIKE '%' || :searchQuery || '%' OR
              sender LIKE '%' || :searchQuery || '%' OR
              appName LIKE '%' || :searchQuery || '%'
          )
        ORDER BY postedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchNotifications(
        searchQuery: String,
        tierFilter: TriageTier?,
        limit: Int = 25
    ): List<NotificationRecordEntity>

    @Query(
        """
        SELECT * FROM notification_records
        WHERE packageName = :packageName
          AND (sender = :sender OR (:shortcutId IS NOT NULL AND shortcutId = :shortcutId))
        ORDER BY postedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentThreadNotifications(
        packageName: String,
        sender: String,
        shortcutId: String?,
        limit: Int = 8
    ): List<NotificationRecordEntity>

    // ==================== SENDER ENGAGEMENT & 3RD-PARTY SCREEN THREADS ====================
    @Query("SELECT * FROM sender_engagement WHERE senderKey = :senderKey LIMIT 1")
    suspend fun getSenderEngagement(senderKey: String): SenderEngagementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenderEngagement(entity: SenderEngagementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScreenThreadSnapshot(snapshot: ScreenThreadSnapshotEntity)

    @Query(
        """
        SELECT * FROM screen_thread_snapshots
        WHERE packageName = :packageName
        ORDER BY capturedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentScreenSnapshotsForApp(
        packageName: String,
        limit: Int = 5
    ): List<ScreenThreadSnapshotEntity>

    // ==================== CONVERSATION TURNS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationTurn(turn: ConversationTurnEntity): Long

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp ASC LIMIT 250")
    fun observeConversationTimeline(): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM conversation_turns WHERE role IN ('USER', 'ASSISTANT') ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentDialogueTurns(limit: Int = 16): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns WHERE id = :turnId LIMIT 1")
    suspend fun getConversationTurnById(turnId: Long): ConversationTurnEntity?

    @Query("UPDATE conversation_turns SET undone = 1 WHERE id = :turnId")
    suspend fun markTurnUndone(turnId: Long)

    // ==================== EPISTEMIC TRACE LOGS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraceLog(trace: TraceLogEntity): Long

    @Query("SELECT * FROM trace_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentTraces(): Flow<List<TraceLogEntity>>

    // ==================== PRIVATE MODE BOUNDARIES & PERMANENT ERASURE ====================
    @Query("SELECT MAX(id) FROM conversation_turns")
    suspend fun getMaxConversationTurnId(): Long?

    @Query("SELECT MAX(id) FROM memories")
    suspend fun getMaxMemoryId(): Long?

    @Query("SELECT MAX(id) FROM trace_logs")
    suspend fun getMaxTraceId(): Long?

    @Query("DELETE FROM conversation_turns WHERE id >= :startTurnId")
    suspend fun deleteConversationTurnsFrom(startTurnId: Long)

    @Query("DELETE FROM memories WHERE id > :startMemoryId")
    suspend fun deleteMemoriesAfter(startMemoryId: Long)

    @Query("DELETE FROM trace_logs WHERE id > :startTraceId AND subsystem != 'TRIAGE'")
    suspend fun deleteNonTriageTracesAfter(startTraceId: Long)

    @RawQuery
    suspend fun executePragma(query: SupportSQLiteQuery): Int
}
