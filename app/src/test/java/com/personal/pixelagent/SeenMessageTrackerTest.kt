package com.personal.pixelagent

import android.app.Notification
import com.personal.pixelagent.context.SeenMessageTracker
import com.personal.pixelagent.context.VisibleWindowSnapshot
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TriageTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenMessageTrackerTest {

    private val messagingPkg = "com.google.android.apps.messaging"

    private fun sampleMessageRecord(
        id: Long,
        sender: String,
        title: String = sender,
        body: String = "Are we still on for 6pm?",
        tier: TriageTier = TriageTier.URGENT,
        cleared: Boolean = false
    ) = NotificationRecordEntity(
        id = id,
        sbnKey = "0|$messagingPkg|$id|tag|10100",
        shortcutId = "thread_$id",
        packageName = messagingPkg,
        appName = "Messages",
        sender = sender,
        title = title,
        body = body,
        category = Notification.CATEGORY_MESSAGE,
        reasoningRationale = "Direct contact message.",
        tier = tier,
        clearedFromDigest = cleared
    )

    @Test
    fun `inbox list without composer input never prematurely clears unread message threads`() {
        val unreadAlpha = sampleMessageRecord(1L, sender = "Jordan Taylor", body = "See you soon!")
        val unreadBeta = sampleMessageRecord(2L, sender = "Casey Morgan", body = "Sent the document over")

        // User opens the top-level Messages inbox list: names and previews appear, but hasComposerInput is false
        val inboxListWindow = VisibleWindowSnapshot(
            packageName = messagingPkg,
            topHeaderTexts = listOf("Messages", "Search images & videos"),
            allVisibleTexts = listOf(
                "Messages",
                "Jordan Taylor",
                "See you soon!",
                "Casey Morgan",
                "Sent the document over"
            ),
            hasComposerInput = false
        )

        assertFalse(SeenMessageTracker.isRecordSeenInWindow(unreadAlpha, inboxListWindow))
        assertFalse(SeenMessageTracker.isRecordSeenInWindow(unreadBeta, inboxListWindow))
    }

    @Test
    fun `opening specific conversation clears only that sender and enforces word boundaries`() {
        val jordanRecord = sampleMessageRecord(1L, sender = "Jordan Taylor", body = "Running five minutes late")
        val caseyRecord = sampleMessageRecord(2L, sender = "Casey Morgan", body = "Sounds good")
        val shortPrefixRecord = sampleMessageRecord(3L, sender = "Al", body = "Ping")

        val openJordanThread = VisibleWindowSnapshot(
            packageName = messagingPkg,
            topHeaderTexts = listOf("Jordan Taylor • Mobile"),
            allVisibleTexts = listOf(
                "Jordan Taylor • Mobile",
                "Alex",
                "Running five minutes late",
                "RCS message"
            ),
            hasComposerInput = true
        )

        assertTrue(SeenMessageTracker.isRecordSeenInWindow(jordanRecord, openJordanThread))
        assertFalse(SeenMessageTracker.isRecordSeenInWindow(caseyRecord, openJordanThread))
        // "Al" must NOT match "Alex" inside visible text
        assertFalse(SeenMessageTracker.isRecordSeenInWindow(shortPrefixRecord, openJordanThread))
    }

    @Test
    fun `unsaved phone number conversation matches across formatting differences`() {
        val phoneRecord = sampleMessageRecord(
            id = 4L,
            sender = "+1 (415) 890-1234",
            title = "+1 (415) 890-1234",
            body = "Delivery arrived at front porch"
        )

        val openPhoneThread = VisibleWindowSnapshot(
            packageName = messagingPkg,
            topHeaderTexts = listOf("(415) 890-1234"),
            allVisibleTexts = listOf("(415) 890-1234", "Delivery arrived at front porch", "Text message"),
            hasComposerInput = true
        )

        assertTrue(SeenMessageTracker.isRecordSeenInWindow(phoneRecord, openPhoneThread))
    }

    @Test
    fun `active foreground thread suppresses redundant alert only when unlocked fresh and matching`() {
        val now = 1_700_000_000_000L
        val openThread = VisibleWindowSnapshot(
            packageName = messagingPkg,
            topHeaderTexts = listOf("Jordan Taylor"),
            allVisibleTexts = listOf("Jordan Taylor", "Earlier message", "RCS message"),
            hasComposerInput = true,
            capturedAtMillis = now - 3_000L
        )

        // Matching open thread on unlocked screen -> suppress redundant heads-up alert
        assertTrue(
            SeenMessageTracker.isIncomingPayloadAlreadyOpenOnScreen(
                packageName = messagingPkg,
                sender = "Jordan Taylor",
                title = "Jordan Taylor",
                appName = "Messages",
                activeWindow = openThread,
                isDeviceInteractiveAndUnlocked = true,
                nowMillis = now
            )
        )

        // Different contact while viewing Jordan's thread -> do NOT suppress!
        assertFalse(
            SeenMessageTracker.isIncomingPayloadAlreadyOpenOnScreen(
                packageName = messagingPkg,
                sender = "Casey Morgan",
                title = "Casey Morgan",
                appName = "Messages",
                activeWindow = openThread,
                isDeviceInteractiveAndUnlocked = true,
                nowMillis = now
            )
        )

        // Screen locked/off -> do NOT suppress!
        assertFalse(
            SeenMessageTracker.isIncomingPayloadAlreadyOpenOnScreen(
                packageName = messagingPkg,
                sender = "Jordan Taylor",
                title = "Jordan Taylor",
                appName = "Messages",
                activeWindow = openThread,
                isDeviceInteractiveAndUnlocked = false,
                nowMillis = now
            )
        )

        // Stale window snapshot (>45s without foreground refresh) -> do NOT suppress!
        assertFalse(
            SeenMessageTracker.isIncomingPayloadAlreadyOpenOnScreen(
                packageName = messagingPkg,
                sender = "Jordan Taylor",
                title = "Jordan Taylor",
                appName = "Messages",
                activeWindow = openThread,
                isDeviceInteractiveAndUnlocked = true,
                nowMillis = now + 60_000L
            )
        )
    }
}
