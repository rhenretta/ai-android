package com.personal.pixelagent.gatekeeper

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.context.CommunicationContextEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GatekeeperNotificationListener : NotificationListenerService() {

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var coalescer: BurstCoalescer<CapturedNotificationPayload>

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as PixelAgentApp
        coalescer = BurstCoalescer(
            scope = listenerScope,
            windowMillis = 150L
        ) { _, latestPayload ->
            app.container.triageEngine.evaluateAndRoute(latestPayload)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = applicationContext as? PixelAgentApp ?: return
        listenerScope.launch {
            app.container.zenModeController.syncGatekeeperRule()
            val activeDigest = app.container.database.agentDao().getActiveDigestItems()
            app.container.channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = activeDigest,
                gatekeeperEnabled = app.container.settingsStore.isGatekeeperEnabled(),
                degradedError = app.container.settingsStore.state.value.lastDegradedError
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val app = applicationContext as? PixelAgentApp ?: return

        // If user paused Gatekeeper or has not configured an API key yet, leave Android notifications untouched
        if (!app.container.settingsStore.isGatekeeperEnabled() || !app.container.settingsStore.hasApiKey()) return

        // Structural OS boundary checks (never intercept own notifications, calls, alarms, media, or ongoing services)
        if (shouldBypassStructurally(sbn)) return

        val notification = sbn.notification
        val extras = notification.extras ?: Bundle.EMPTY

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        val threadLines = extractMessagingStyleLines(extras)
        if (title.isBlank() && text.isBlank() && threadLines.isEmpty()) return

        val sender = extractSenderLabel(extras, title)
        val appName = resolveAppName(sbn.packageName)
        val shortcutId = notification.shortcutId
        val senderKey = CommunicationContextEngine.buildSenderKey(
            sbn.packageName,
            shortcutId,
            sender.ifBlank { title }
        )

        // 1. Cache PendingIntent + RemoteInput actions BEFORE canceling the notification
        app.container.actionIntentCache.captureFromNotification(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            senderKey = senderKey,
            senderDisplay = sender.ifBlank { title },
            notification = notification
        )

        // 2. Dismiss the raw notification so unvetted clutter never piles up in the shade
        cancelNotification(sbn.key)

        // 3. Submit into 800ms per-thread burst coalescer for rationale-first AI triage
        val payload = CapturedNotificationPayload(
            sbnKey = sbn.key,
            shortcutId = shortcutId,
            packageName = sbn.packageName,
            appName = appName,
            category = notification.category,
            isConversationShortcut = !shortcutId.isNullOrBlank(),
            sender = sender,
            title = title.ifBlank { appName },
            body = text.ifBlank { threadLines.lastOrNull().orEmpty() },
            messagingThreadLines = threadLines,
            postedAt = sbn.postTime
        )

        listenerScope.launch {
            coalescer.submit(sbn.key, payload)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        if (sbn == null) return
        if (sbn.packageName == applicationContext.packageName) {
            val extras = sbn.notification.extras
            val explicitRecordId = extras?.getLong(NotificationChannelsManager.EXTRA_PIXEL_RECORD_ID, -1L) ?: -1L
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val app = applicationContext as? PixelAgentApp ?: return

            if (explicitRecordId > 0L || reason == REASON_CLICK) {
                listenerScope.launch {
                    val dao = app.container.database.agentDao()
                    val matched = if (explicitRecordId > 0L) {
                        dao.getNotificationById(explicitRecordId)
                    } else {
                        dao.searchNotifications(title, null, limit = 1).firstOrNull()
                    }
                    if (matched != null) {
                        dao.markNotificationsSeen(listOf(matched.id))
                        if (reason == REASON_CLICK) {
                            val senderKey = CommunicationContextEngine.buildSenderKey(
                                matched.packageName,
                                matched.shortcutId,
                                matched.sender.ifBlank { matched.title }
                            )
                            app.container.actionIntentCache.recordEngagement(
                                senderKey = senderKey,
                                packageName = matched.packageName,
                                displayName = matched.sender.ifBlank { subText },
                                opened = true
                            )
                        }
                    }
                }
            }
        }
    }

    private fun shouldBypassStructurally(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == applicationContext.packageName) return true
        if (sbn.isOngoing) return true

        val notif = sbn.notification
        if ((notif.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return true
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val text = notif.extras?.getCharSequence(Notification.EXTRA_TEXT)
            if (text.isNullOrBlank()) return true
        }

        return when (notif.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM -> true
            else -> false
        }
    }

    private fun extractSenderLabel(extras: Bundle, fallbackTitle: String): String {
        val person: Person? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)
        }
        return person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
    }

    private fun extractMessagingStyleLines(extras: Bundle): List<String> {
        val result = ArrayList<String>()
        val historic = getParcelableArrayCompat(extras, Notification.EXTRA_HISTORIC_MESSAGES)
        val current = getParcelableArrayCompat(extras, Notification.EXTRA_MESSAGES)

        val allBundles = buildList {
            historic?.forEach { if (it is Bundle) add(it) }
            current?.forEach { if (it is Bundle) add(it) }
        }

        for (b in allBundles.takeLast(10)) {
            val senderPerson: Person? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                b.getParcelable("sender_person", Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                b.getParcelable("sender_person")
            }
            val senderName = senderPerson?.name?.toString()
                ?: b.getCharSequence("sender")?.toString()
                ?: "Me/User"
            val msgText = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (msgText.isNotEmpty()) {
                result.add("$senderName: $msgText")
            }
        }
        return result
    }

    private fun getParcelableArrayCompat(extras: Bundle, key: String): Array<out Parcelable>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(key, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(key)
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            packageName
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listenerScope.cancel()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, GatekeeperNotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString())
        }
    }
}
