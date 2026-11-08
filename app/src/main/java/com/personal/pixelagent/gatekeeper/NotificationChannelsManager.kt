package com.personal.pixelagent.gatekeeper

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.personal.pixelagent.R
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.ui.MainActivity
import com.personal.pixelagent.ui.VoiceOverlayActivity

class NotificationChannelsManager(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    fun ensureChannelsCreated() {
        val urgentChannel = NotificationChannel(
            CHANNEL_URGENT,
            "Important Breakthrough Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High-priority notifications vetted as important by your AI Agent; bypasses Gatekeeper silence."
            setBypassDnd(true)
            enableVibration(true)
        }

        val digestChannel = NotificationChannel(
            CHANNEL_LIVE_SUMMARY,
            "Live Summary Digest (Silent)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Single silent live rollup of non-important notifications held by your AI Agent."
            setBypassDnd(true)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val voiceChannel = NotificationChannel(
            CHANNEL_VOICE_FGS,
            "Active Voice Conversation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displayed while synchronous Gemini Live voice chat is active."
            setSound(null, null)
            enableVibration(false)
        }

        notificationManager.createNotificationChannels(
            listOf(urgentChannel, digestChannel, voiceChannel)
        )
    }

    fun postUrgentBreakthrough(
        recordId: Long,
        appName: String,
        title: String,
        body: String,
        cachedActions: CachedNotificationActions?,
        isErrorPassthrough: Boolean = false
    ) {
        ensureChannelsCreated()

        val fallbackIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fallbackPending = PendingIntent.getActivity(
            context,
            recordId.toInt(),
            fallbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headerPrefix = if (isErrorPassthrough) "$appName (Offline Pass-Through)" else appName
        val extrasBundle = Bundle().apply {
            putLong(EXTRA_PIXEL_RECORD_ID, recordId)
        }
        val builder = Notification.Builder(context, CHANNEL_URGENT)
            .setSmallIcon(R.drawable.ic_agent_shield)
            .setSubText(headerPrefix)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .addExtras(extrasBundle)
            .setContentIntent(cachedActions?.contentIntent ?: fallbackPending)

        // Preserve original app's inline RemoteInput quick-reply action if available
        cachedActions?.replyAction?.let { nativeReply ->
            builder.addAction(nativeReply)
        }

        val notificationId = URGENT_NOTIFICATION_BASE_ID + (recordId % 5000).toInt()
        notificationManager.notify(notificationId, builder.build())
    }

    fun cancelUrgentNotification(recordId: Long) {
        val notificationId = URGENT_NOTIFICATION_BASE_ID + (recordId % 5000).toInt()
        notificationManager.cancel(notificationId)
    }

    fun refreshLiveSummaryNotification(
        activeDigestItems: List<NotificationRecordEntity>,
        gatekeeperEnabled: Boolean,
        degradedError: String?
    ) {
        ensureChannelsCreated()

        if (!gatekeeperEnabled && activeDigestItems.isEmpty() && degradedError == null) {
            notificationManager.cancel(LIVE_SUMMARY_NOTIFICATION_ID)
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val voicePending = createBackgroundAllowedActivityPendingIntent(context, 102, voiceIntent)

        val clearIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CLEAR_DIGEST
        }
        val clearPending = PendingIntent.getBroadcast(
            context,
            103,
            clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headline: String
        val expandedText: String

        if (!degradedError.isNullOrBlank()) {
            headline = "Agent Degraded — Direct messages passing through"
            expandedText = buildString {
                appendLine("Error: $degradedError")
                if (activeDigestItems.isNotEmpty()) {
                    appendLine()
                    activeDigestItems.take(5).forEach { item ->
                        appendLine("• ${item.appName}: ${item.summaryLine ?: item.title}")
                    }
                }
            }.trim()
        } else if (activeDigestItems.isEmpty()) {
            headline = "All clear · Holding quiet watch"
            expandedText = "Non-important notifications will be summarized silently here."
        } else {
            val distinctApps = activeDigestItems.map { it.appName }.distinct().take(3).joinToString(", ")
            headline = "${activeDigestItems.size} held · $distinctApps"
            expandedText = buildString {
                activeDigestItems.take(6).forEach { item ->
                    val bullet = item.summaryLine?.takeIf { it.isNotBlank() }
                        ?: "${item.title}: ${item.body}".take(90)
                    appendLine("• ${item.appName} — $bullet")
                }
                if (activeDigestItems.size > 6) {
                    append("+${activeDigestItems.size - 6} more in workspace")
                }
            }.trim()
        }

        val builder = Notification.Builder(context, CHANNEL_LIVE_SUMMARY)
            .setSmallIcon(R.drawable.ic_agent_shield)
            .setContentTitle(headline)
            .setContentText(expandedText.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(expandedText))
            .setOngoing(gatekeeperEnabled)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Talk",
                    voicePending
                ).build()
            )

        if (activeDigestItems.isNotEmpty()) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Clear",
                    clearPending
                ).build()
            )
        }

        notificationManager.notify(LIVE_SUMMARY_NOTIFICATION_ID, builder.build())
    }

    fun buildVoiceForegroundNotification(
        statusText: String,
        voiceName: String,
        isPrivateMode: Boolean = false
    ): Notification {
        ensureChannelsCreated()
        val stopIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_STOP_VOICE
        }
        val stopPending = PendingIntent.getBroadcast(
            context,
            104,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openSheetIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openSheetPending = createBackgroundAllowedActivityPendingIntent(context, 105, openSheetIntent)

        val title = if (isPrivateMode) "Private Voice · $voiceName" else "Live Voice · $voiceName"
        val builder = Notification.Builder(context, CHANNEL_VOICE_FGS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSheetPending)
            .addAction(
                Notification.Action.Builder(null, "End Voice", stopPending).build()
            )
        if (isPrivateMode) {
            builder.setSubText("Private Mode · Erased on exit")
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_URGENT = "urgent_breakthrough"
        const val CHANNEL_LIVE_SUMMARY = "live_summary_digest"
        const val CHANNEL_VOICE_FGS = "voice_session_fgs"

        const val LIVE_SUMMARY_NOTIFICATION_ID = 4100
        const val VOICE_FGS_NOTIFICATION_ID = 4101
        const val EXTRA_PIXEL_RECORD_ID = "pixel_agent_record_id"
        private const val URGENT_NOTIFICATION_BASE_ID = 5000

        fun createBackgroundAllowedActivityPendingIntent(
            context: Context,
            requestCode: Int,
            intent: Intent
        ): PendingIntent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    options.toBundle()
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }
    }
}
