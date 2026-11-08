package com.personal.pixelagent.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory {
    @SerialName("STANDING_DIRECTIVE") STANDING_DIRECTIVE,
    @SerialName("RELATIONSHIP") RELATIONSHIP,
    @SerialName("PREFERENCE") PREFERENCE,
    @SerialName("ACTIVE_EXPECTATION") ACTIVE_EXPECTATION
}

@Serializable
enum class TriageTier {
    @SerialName("URGENT") URGENT,
    @SerialName("SUMMARY") SUMMARY,
    @SerialName("SPAM") SPAM,
    @SerialName("PASSTHROUGH_ERROR") PASSTHROUGH_ERROR
}

enum class TurnModality {
    TEXT,
    VOICE
}

enum class TurnRole {
    USER,
    ASSISTANT,
    ACTION_RECEIPT
}

enum class UndoActionType {
    NONE,
    DEACTIVATE_MEMORY,
    REACTIVATE_MEMORY,
    RESTORE_MEMORY_CONTENT,
    RESTORE_VOICE,
    RESTORE_NOTIFICATION_TIER
}

@Entity(
    tableName = "memories",
    indices = [Index("active"), Index("category")]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: MemoryCategory,
    val content: String,
    val sourceSummary: String,
    val embeddingBlob: ByteArray? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sender_engagement",
    indices = [Index("packageName"), Index("lastUserEngagedAt")]
)
data class SenderEngagementEntity(
    @PrimaryKey val senderKey: String,
    val packageName: String,
    val displayName: String,
    val userOpenedCount: Int = 0,
    val userRepliedCount: Int = 0,
    val userPromotedCount: Int = 0,
    val userMarkedSpamCount: Int = 0,
    val lastUserEngagedAt: Long = 0L,
    val lastInboundAt: Long = 0L
)

@Entity(
    tableName = "screen_thread_snapshots",
    indices = [Index("packageName"), Index("capturedAt")]
)
data class ScreenThreadSnapshotEntity(
    @PrimaryKey val threadKey: String,
    val packageName: String,
    val appLabel: String,
    val headerTitle: String,
    val transcriptSnippet: String,
    val capturedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notification_records",
    indices = [Index("tier"), Index("clearedFromDigest"), Index("postedAt"), Index("sbnKey")]
)
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sbnKey: String,
    val shortcutId: String? = null,
    val packageName: String,
    val appName: String,
    val sender: String,
    val title: String,
    val body: String,
    val threadMessagesJson: String = "[]",
    val category: String? = null,
    val postedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "reasoning_rationale") val reasoningRationale: String,
    val tier: TriageTier,
    val summaryLine: String? = null,
    val clearedFromDigest: Boolean = false,
    val errorContext: String? = null
)

@Entity(
    tableName = "conversation_turns",
    indices = [Index("timestamp")]
)
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val modality: TurnModality,
    val role: TurnRole,
    val text: String,
    val receiptTitle: String? = null,
    val receiptSubtitle: String? = null,
    val undoActionType: UndoActionType = UndoActionType.NONE,
    val undoPayload: String? = null,
    val undone: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "trace_logs",
    indices = [Index("timestamp"), Index("subsystem")]
)
data class TraceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val subsystem: String,
    val eventType: String,
    @ColumnInfo(name = "reasoning_rationale") val reasoningRationale: String,
    val decisionPayload: String,
    val errorStack: String? = null
)
