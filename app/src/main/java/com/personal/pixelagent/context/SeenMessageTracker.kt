package com.personal.pixelagent.context

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.gatekeeper.ActionIntentCache
import com.personal.pixelagent.gatekeeper.NotificationChannelsManager
import com.personal.pixelagent.observability.TraceLogger
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class VisibleWindowSnapshot(
    val packageName: String,
    val topHeaderTexts: List<String>,
    val allVisibleTexts: List<String>,
    val hasComposerInput: Boolean,
    val capturedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Watches whether held or breakthrough notifications have already been seen directly inside
 * their originating app (or marked read via Android's Telephony SMS/MMS provider) and
 * automatically clears Pixel Agent's status-bar alert and Live Summary entry.
 *
 * Also tracks whether a conversation thread is actively open on the unlocked screen right now
 * so incoming replies in that active thread do not trigger redundant heads-up alerts.
 */
class SeenMessageTracker(
    private val context: Context,
    private val dao: AgentDao,
    private val settingsStore: SecureSettingsStore,
    private val actionIntentCache: ActionIntentCache,
    private val channelsManager: NotificationChannelsManager,
    private val traceLogger: TraceLogger
) {
    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeWindow: VisibleWindowSnapshot? = null

    @Volatile
    private var telephonyObserverRegistered = false

    private var telephonyCheckJob: Job? = null

    private val telephonyObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            telephonyCheckJob?.cancel()
            telephonyCheckJob = trackerScope.launch {
                delay(300L)
                checkTelephonyReadState()
            }
        }
    }

    fun start() {
        if (telephonyObserverRegistered) return
        try {
            context.contentResolver.registerContentObserver(
                Telephony.MmsSms.CONTENT_URI,
                true,
                telephonyObserver
            )
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                telephonyObserver
            )
            telephonyObserverRegistered = true
        } catch (_: SecurityException) {
            // Will still auto-clear via ActiveScreenContextService; observer works whenever READ_SMS is active
        } catch (_: Throwable) {
            // Ignore non-telephony devices
        }
    }

    fun onWindowPackageSwitched(foregroundPackage: String) {
        val current = activeWindow
        if (current != null && current.packageName != foregroundPackage) {
            activeWindow = null
        }
    }

    suspend fun onActiveWindowInspected(window: VisibleWindowSnapshot) {
        if (!isDeviceInteractiveAndUnlocked()) {
            activeWindow = null
            return
        }
        activeWindow = window

        val unseen = dao.getUnseenNotificationsForPackage(window.packageName)
        if (unseen.isEmpty()) return

        val matched = unseen.filter { record -> isRecordSeenInWindow(record, window) }
        if (matched.isNotEmpty()) {
            dismissSeenNotifications(
                matched = matched,
                reason = "User opened conversation thread directly in ${matched.first().appName}; cleared seen notification automatically."
            )
        }
    }

    fun isConversationCurrentlyOpenOnScreen(
        packageName: String,
        sender: String,
        title: String,
        appName: String
    ): Boolean {
        return isIncomingPayloadAlreadyOpenOnScreen(
            packageName = packageName,
            sender = sender,
            title = title,
            appName = appName,
            activeWindow = activeWindow,
            isDeviceInteractiveAndUnlocked = isDeviceInteractiveAndUnlocked(),
            nowMillis = System.currentTimeMillis()
        )
    }

    suspend fun dismissSeenNotifications(
        matched: List<NotificationRecordEntity>,
        reason: String
    ) {
        if (matched.isEmpty()) return
        val ids = matched.map { it.id }
        dao.markNotificationsSeen(ids)

        var anySummaryCleared = false
        for (record in matched) {
            if (record.tier == TriageTier.URGENT || record.tier == TriageTier.PASSTHROUGH_ERROR) {
                channelsManager.cancelUrgentNotification(record.id)
            }
            if (record.tier == TriageTier.SUMMARY) {
                anySummaryCleared = true
            }
            val senderKey = CommunicationContextEngine.buildSenderKey(
                record.packageName,
                record.shortcutId,
                record.sender.ifBlank { record.title }
            )
            actionIntentCache.recordEngagement(
                senderKey = senderKey,
                packageName = record.packageName,
                displayName = record.sender.ifBlank { record.title },
                opened = true
            )
        }

        if (anySummaryCleared) {
            val remainingDigest = dao.getActiveDigestItems()
            channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = remainingDigest,
                gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
                degradedError = settingsStore.state.value.lastDegradedError
            )
            AgentVoiceWidgetProvider.requestRefresh(context)
        }

        if (!settingsStore.isPrivateModeActive()) {
            traceLogger.logDecision(
                subsystem = "GATEKEEPER",
                eventType = "AUTO_DISMISSED_SEEN_NOTIFICATION",
                reasoningRationale = reason,
                decisionPayload = "clearedIds=$ids app=${matched.first().appName} sender=${matched.first().sender.ifBlank { matched.first().title }}"
            )
        }
    }

    private suspend fun checkTelephonyReadState() {
        if (!hasPermission(Manifest.permission.READ_SMS)) return

        val defaultSmsPkg = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?: DEFAULT_GOOGLE_MESSAGES_PKG
        val candidates = dao.getUnseenNotificationsForPackage(defaultSmsPkg)
        if (candidates.isEmpty()) return

        val readConfirmed = mutableListOf<NotificationRecordEntity>()
        for (record in candidates) {
            val phoneTails = resolvePhoneTailsForRecord(record)
            if (phoneTails.isEmpty()) continue
            if (areMatchingInboxSmsMarkedRead(phoneTails, record.postedAt)) {
                readConfirmed.add(record)
            }
        }

        if (readConfirmed.isNotEmpty()) {
            dismissSeenNotifications(
                matched = readConfirmed,
                reason = "Underlying SMS thread was marked read in Android Telephony; cleared notification automatically."
            )
        }
    }

    private fun resolvePhoneTailsForRecord(record: NotificationRecordEntity): Set<String> {
        val tails = LinkedHashSet<String>()
        extractPhoneTail(record.sender)?.let { tails.add(it) }
        extractPhoneTail(record.title)?.let { tails.add(it) }
        if (tails.isNotEmpty() || !hasPermission(Manifest.permission.READ_CONTACTS)) {
            return tails
        }

        val nameLookup = record.sender.ifBlank { record.title }.trim()
        if (nameLookup.isEmpty()) return tails

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(nameLookup),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(1).orEmpty()
                    extractPhoneTail(number)?.let { tails.add(it) }
                }
            }
        }
        return tails
    }

    private fun areMatchingInboxSmsMarkedRead(phoneTails: Set<String>, postedAtMillis: Long): Boolean {
        val windowFloor = postedAtMillis - 120_000L
        var totalMatched = 0
        var unreadMatched = 0

        for (tail in phoneTails) {
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.READ),
                    "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.ADDRESS} LIKE ?",
                    arrayOf(
                        Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                        windowFloor.toString(),
                        "%$tail%"
                    ),
                    "${Telephony.Sms.DATE} DESC LIMIT 10"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        totalMatched++
                        val isRead = cursor.getInt(0) == 1
                        if (!isRead) {
                            unreadMatched++
                        }
                    }
                }
            }
        }
        return totalMatched > 0 && unreadMatched == 0
    }

    private fun isDeviceInteractiveAndUnlocked(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power?.isInteractive == true
        val locked = keyguard?.isKeyguardLocked == true
        return interactive && !locked
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val DEFAULT_GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"
        const val ACTIVE_WINDOW_MAX_AGE_MS = 45_000L

        fun isRecordSeenInWindow(
            record: NotificationRecordEntity,
            window: VisibleWindowSnapshot
        ): Boolean {
            if (record.clearedFromDigest) return false
            if (record.packageName != window.packageName) return false

            val isConversationNotif =
                record.category == Notification.CATEGORY_MESSAGE || !record.shortcutId.isNullOrBlank()

            return if (isConversationNotif) {
                // Require an open conversation composer so viewing an inbox list never prematurely clears unread threads
                if (!window.hasComposerInput) return false

                matchesTargetInHeaders(
                    sender = record.sender,
                    title = record.title,
                    appName = record.appName,
                    headerTexts = window.topHeaderTexts
                ) || (
                    matchesTargetInHeaders(
                        sender = record.sender,
                        title = record.title,
                        appName = record.appName,
                        headerTexts = window.allVisibleTexts
                    ) && matchesBodyBubble(record.body, window.allVisibleTexts)
                )
            } else {
                // Non-chat detail view (e.g. opened email or alert detail screen)
                val headerMatched = matchesTargetInHeaders(
                    sender = record.sender,
                    title = record.title,
                    appName = record.appName,
                    headerTexts = window.topHeaderTexts
                )
                headerMatched && (window.hasComposerInput || matchesBodyBubble(record.body, window.allVisibleTexts))
            }
        }

        fun isIncomingPayloadAlreadyOpenOnScreen(
            packageName: String,
            sender: String,
            title: String,
            appName: String,
            activeWindow: VisibleWindowSnapshot?,
            isDeviceInteractiveAndUnlocked: Boolean,
            nowMillis: Long
        ): Boolean {
            if (!isDeviceInteractiveAndUnlocked) return false
            val win = activeWindow ?: return false
            if (win.packageName != packageName) return false
            if (!win.hasComposerInput) return false
            if (nowMillis - win.capturedAtMillis > ACTIVE_WINDOW_MAX_AGE_MS) return false

            return matchesTargetInHeaders(
                sender = sender,
                title = title,
                appName = appName,
                headerTexts = win.topHeaderTexts
            )
        }

        fun matchesTargetInHeaders(
            sender: String,
            title: String,
            appName: String,
            headerTexts: List<String>
        ): Boolean {
            if (headerTexts.isEmpty()) return false
            val appNorm = normalizeText(appName)
            val candidates = buildList {
                val s = normalizeText(sender)
                if (s.length >= 2 && s != appNorm) add(s to sender)
                val t = normalizeText(title)
                if (t.length >= 2 && t != appNorm && t != s) add(t to title)
            }
            if (candidates.isEmpty()) return false

            for (rawHeader in headerTexts) {
                val headerNorm = normalizeText(rawHeader)
                if (headerNorm.length < 2) continue
                for ((candNorm, candRaw) in candidates) {
                    if (headerNorm == candNorm) return true
                    if (containsWholePhrase(headerNorm, candNorm)) return true
                    val headerTail = extractPhoneTail(rawHeader)
                    val candTail = extractPhoneTail(candRaw)
                    if (headerTail != null && headerTail == candTail) return true
                }
            }
            return false
        }

        fun matchesBodyBubble(body: String, visibleTexts: List<String>): Boolean {
            val fullNorm = normalizeText(body)
            val lastLineNorm = body.lineSequence()
                .map { normalizeText(it) }
                .lastOrNull { it.isNotEmpty() }
                .orEmpty()

            val probes = listOf(fullNorm, lastLineNorm).filter { it.isNotEmpty() }.distinct()
            if (probes.isEmpty()) return false

            for (rawVisible in visibleTexts) {
                val visNorm = normalizeText(rawVisible)
                if (visNorm.isEmpty()) continue
                for (probe in probes) {
                    if (visNorm == probe) return true
                    if (probe.length >= 10 && visNorm.contains(probe)) return true
                    if (visNorm.length >= 16 && probe.contains(visNorm)) return true
                }
            }
            return false
        }

        internal fun containsWholePhrase(haystackNorm: String, needleNorm: String): Boolean {
            if (needleNorm.isEmpty() || haystackNorm.length < needleNorm.length) return false
            var searchFrom = 0
            while (true) {
                val idx = haystackNorm.indexOf(needleNorm, searchFrom)
                if (idx < 0) return false
                val endIdx = idx + needleNorm.length
                val startBoundary = idx == 0 || !haystackNorm[idx - 1].isLetterOrDigit()
                val endBoundary = endIdx == haystackNorm.length || !haystackNorm[endIdx].isLetterOrDigit()
                if (startBoundary && endBoundary) return true
                searchFrom = idx + 1
            }
        }

        internal fun normalizeText(raw: String): String {
            val sb = StringBuilder(raw.length)
            var prevSpace = true
            for (ch in raw) {
                if (ch.isWhitespace()) {
                    if (!prevSpace) {
                        sb.append(' ')
                        prevSpace = true
                    }
                } else {
                    sb.append(ch.lowercaseChar())
                    prevSpace = false
                }
            }
            return sb.toString().trim()
        }

        private fun extractPhoneTail(raw: String): String? {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length >= 7) digits.takeLast(7) else null
        }
    }
}
