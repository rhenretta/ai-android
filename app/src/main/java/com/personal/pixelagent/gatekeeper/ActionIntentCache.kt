package com.personal.pixelagent.gatekeeper

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.LruCache
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.SenderEngagementEntity

data class CachedNotificationActions(
    val sbnKey: String,
    val packageName: String,
    val senderKey: String,
    val senderDisplay: String,
    val contentIntent: PendingIntent?,
    val replyAction: Notification.Action?,
    val remoteInputs: Array<RemoteInput>?
)

/**
 * Retains live `PendingIntent` and `RemoteInput` references in memory after raw notifications
 * are canceled from the status bar, allowing both re-emitted URGENT alerts and conversational
 * quick-replies / digest taps to open or reply directly inside the source app.
 */
class ActionIntentCache(private val dao: AgentDao) {

    private val bySbnKey = LruCache<String, CachedNotificationActions>(150)
    private val byRecordId = LruCache<Long, CachedNotificationActions>(150)

    fun captureFromNotification(
        sbnKey: String,
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        notification: Notification
    ): CachedNotificationActions {
        var foundReplyAction: Notification.Action? = null
        var foundRemoteInputs: Array<RemoteInput>? = null

        notification.actions?.forEach { action ->
            val inputs = action.remoteInputs
            if (!inputs.isNullOrEmpty() && foundReplyAction == null) {
                foundReplyAction = action
                foundRemoteInputs = inputs
            }
        }

        val entry = CachedNotificationActions(
            sbnKey = sbnKey,
            packageName = packageName,
            senderKey = senderKey,
            senderDisplay = senderDisplay,
            contentIntent = notification.contentIntent,
            replyAction = foundReplyAction,
            remoteInputs = foundRemoteInputs
        )
        synchronized(this) {
            bySbnKey.put(sbnKey, entry)
        }
        return entry
    }

    fun bindRecordId(recordId: Long, sbnKey: String) {
        synchronized(this) {
            bySbnKey.get(sbnKey)?.let { byRecordId.put(recordId, it) }
        }
    }

    fun getByRecordId(recordId: Long): CachedNotificationActions? =
        synchronized(this) { byRecordId.get(recordId) }

    suspend fun openNotificationTarget(
        context: Context,
        recordId: Long,
        fallbackPackageName: String,
        senderKey: String,
        senderDisplay: String
    ): Boolean {
        recordEngagement(senderKey, fallbackPackageName, senderDisplay, opened = true)
        val cached = getByRecordId(recordId)
        if (cached?.contentIntent != null) {
            try {
                cached.contentIntent.send()
                return true
            } catch (_: PendingIntent.CanceledException) {
                // Fall through to package launch intent if original PendingIntent expired
            }
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(fallbackPackageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launchIntent != null) {
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }

    suspend fun sendQuickReplyOrThrow(
        context: Context,
        recordId: Long,
        replyText: String
    ) {
        val cached = getByRecordId(recordId)
            ?: throw IllegalStateException("Notification #$recordId is no longer in the active action cache; open the app directly to reply.")
        val action = cached.replyAction
        val inputs = cached.remoteInputs
        if (action == null || inputs.isNullOrEmpty() || action.actionIntent == null) {
            throw IllegalStateException("The source app (${cached.packageName}) did not attach a quick-reply RemoteInput action to notification #$recordId.")
        }

        val bundle = Bundle()
        for (input in inputs) {
            bundle.putCharSequence(input.resultKey, replyText)
        }
        val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fillInIntent, bundle)

        try {
            action.actionIntent.send(context, 0, fillInIntent)
            recordEngagement(
                senderKey = cached.senderKey,
                packageName = cached.packageName,
                displayName = cached.senderDisplay,
                replied = true
            )
        } catch (e: PendingIntent.CanceledException) {
            throw IllegalStateException("The source app revoked its quick-reply token for notification #$recordId.", e)
        }
    }

    suspend fun recordEngagement(
        senderKey: String,
        packageName: String,
        displayName: String,
        opened: Boolean = false,
        replied: Boolean = false,
        promoted: Boolean = false,
        markedSpam: Boolean = false,
        inboundSeen: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getSenderEngagement(senderKey) ?: SenderEngagementEntity(
            senderKey = senderKey,
            packageName = packageName,
            displayName = displayName
        )
        dao.upsertSenderEngagement(
            existing.copy(
                displayName = displayName.ifBlank { existing.displayName },
                userOpenedCount = existing.userOpenedCount + (if (opened) 1 else 0),
                userRepliedCount = existing.userRepliedCount + (if (replied) 1 else 0),
                userPromotedCount = existing.userPromotedCount + (if (promoted) 1 else 0),
                userMarkedSpamCount = existing.userMarkedSpamCount + (if (markedSpam) 1 else 0),
                lastUserEngagedAt = if (opened || replied || promoted) now else existing.lastUserEngagedAt,
                lastInboundAt = if (inboundSeen) now else existing.lastInboundAt
            )
        )
    }
}
