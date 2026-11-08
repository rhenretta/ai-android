package com.personal.pixelagent.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.personal.pixelagent.data.local.AgentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ContactMatchInfo(
    val displayName: String,
    val starred: Boolean,
    val relations: List<String>,
    val notesOrOrg: String?
)

data class SmsEngagementInfo(
    val sentByUserCount: Int,
    val receivedCount: Int,
    val lastUserSentTimestamp: Long?,
    val recentTurnsFormatted: List<String>
)

data class CallEngagementInfo(
    val outgoingCount: Int,
    val incomingAnsweredCount: Int,
    val recentMissedCount: Int,
    val calledWithinLast15Minutes: Boolean,
    val summaryLines: List<String>
)

data class CommunicationDossier(
    val contactMatch: ContactMatchInfo?,
    val smsEngagement: SmsEngagementInfo?,
    val callEngagement: CallEngagementInfo?,
    val upcomingCalendarLines: List<String>,
    val crossAppEngagementSummary: String,
    val recentAppThreadLines: List<String>,
    val activeScreenThreadLines: List<String>
) {
    fun formatForPrompt(): String = buildString {
        appendLine("### On-Device Relationship & Engagement Context")
        if (contactMatch != null) {
            appendLine(
                "- Saved Contact: ${contactMatch.displayName} | Starred/Favorite: ${contactMatch.starred}" +
                    (if (contactMatch.relations.isNotEmpty()) " | Relationships: ${contactMatch.relations.joinToString()}" else "") +
                    (if (!contactMatch.notesOrOrg.isNullOrBlank()) " | Details: ${contactMatch.notesOrOrg}" else "")
            )
        } else {
            appendLine("- Saved Contact: Not matched in Contacts (or unresolvable handle)")
        }

        if (smsEngagement != null) {
            appendLine(
                "- SMS/RCS History (recent window): User sent ${smsEngagement.sentByUserCount} msgs, received ${smsEngagement.receivedCount} msgs."
            )
            smsEngagement.recentTurnsFormatted.forEach { appendLine("    $it") }
        }

        if (callEngagement != null && (callEngagement.outgoingCount > 0 || callEngagement.incomingAnsweredCount > 0 || callEngagement.recentMissedCount > 0)) {
            appendLine(
                "- Recent Call Log (7d): Outgoing=${callEngagement.outgoingCount}, Answered=${callEngagement.incomingAnsweredCount}, Missed=${callEngagement.recentMissedCount}, CalledWithin15m=${callEngagement.calledWithinLast15Minutes}"
            )
            callEngagement.summaryLines.forEach { appendLine("    $it") }
        }

        appendLine("- App Thread Engagement: $crossAppEngagementSummary")
        if (recentAppThreadLines.isNotEmpty()) {
            appendLine("- Prior Notifications in This Thread:")
            recentAppThreadLines.forEach { appendLine("    $it") }
        }
        if (activeScreenThreadLines.isNotEmpty()) {
            appendLine("- Recent In-App Conversation Observed on Screen (includes user's in-app replies):")
            activeScreenThreadLines.forEach { appendLine("    $it") }
        }
        if (upcomingCalendarLines.isNotEmpty()) {
            appendLine("- Nearby Calendar Context (-1h to +6h):")
            upcomingCalendarLines.forEach { appendLine("    $it") }
        }
    }.trim()
}

class CommunicationContextEngine(
    private val context: Context,
    private val dao: AgentDao
) {
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d h:mm a", Locale.US)

    private fun formatEpoch(epochMillis: Long): String =
        timeFmt.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    suspend fun buildDossier(
        packageName: String,
        senderRaw: String,
        titleRaw: String,
        shortcutId: String?
    ): CommunicationDossier = withContext(Dispatchers.IO) {
        coroutineScope {
            val queryIdentifier = senderRaw.ifBlank { titleRaw }.trim()
            val contact = lookupContact(queryIdentifier)
            val lookupTarget = contact?.displayName ?: queryIdentifier
            val senderKey = buildSenderKey(packageName, shortcutId, queryIdentifier)

            // Execute all local providers and SQLite reads concurrently across CPU cores
            val smsDeferred = async { lookupSmsEngagement(queryIdentifier, contact?.displayName) }
            val callsDeferred = async { lookupRecentCalls(queryIdentifier, contact?.displayName) }
            val calendarDeferred = async { lookupNearbyCalendar(lookupTarget) }
            val engagementDeferred = async { dao.getSenderEngagement(senderKey) }
            val priorThreadDeferred = async {
                dao.getRecentThreadNotifications(
                    packageName = packageName,
                    sender = senderRaw,
                    shortcutId = shortcutId,
                    limit = 6
                ).map { rec ->
                    "[${formatEpoch(rec.postedAt)} | ${rec.tier.name}] ${rec.title}: ${rec.body.take(140)}"
                }
            }
            val snapshotsDeferred = async {
                dao.getRecentScreenSnapshotsForApp(packageName, limit = 3)
                    .filter { snap ->
                        queryIdentifier.isBlank() ||
                            snap.headerTitle.contains(queryIdentifier, ignoreCase = true) ||
                            (contact != null && snap.headerTitle.contains(contact.displayName, ignoreCase = true)) ||
                            snap.transcriptSnippet.contains(queryIdentifier, ignoreCase = true)
                    }
                    .map { snap ->
                        "[${formatEpoch(snap.capturedAt)} in ${snap.appLabel} '${snap.headerTitle}']: ${snap.transcriptSnippet.take(320)}"
                    }
            }

            val engagement = engagementDeferred.await()
            val crossAppSummary = if (engagement != null) {
                "User opened=${engagement.userOpenedCount}, quick-replied=${engagement.userRepliedCount}, promoted=${engagement.userPromotedCount}, markedSpam=${engagement.userMarkedSpamCount}"
            } else {
                "No prior user interactions recorded for this app thread yet."
            }

            CommunicationDossier(
                contactMatch = contact,
                smsEngagement = smsDeferred.await(),
                callEngagement = callsDeferred.await(),
                upcomingCalendarLines = calendarDeferred.await(),
                crossAppEngagementSummary = crossAppSummary,
                recentAppThreadLines = priorThreadDeferred.await(),
                activeScreenThreadLines = snapshotsDeferred.await()
            )
        }
    }

    private fun hasPerm(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun lookupContact(identifier: String): ContactMatchInfo? {
        if (identifier.isBlank() || !hasPerm(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val resolver = context.contentResolver
            val looksLikePhone = identifier.count { it.isDigit() } >= 7
            val uri: Uri = if (looksLikePhone) {
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(identifier))
            } else {
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(identifier))
            }

            val projection = if (looksLikePhone) {
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.STARRED,
                    ContactsContract.PhoneLookup.LOOKUP_KEY
                )
            } else {
                arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.STARRED,
                    ContactsContract.Contacts.LOOKUP_KEY
                )
            }

            var displayName: String? = null
            var starred = false
            var lookupKey: String? = null

            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(0)
                    starred = cursor.getInt(1) == 1
                    lookupKey = cursor.getString(2)
                }
            }

            val resolvedName = displayName ?: return null
            val relations = mutableListOf<String>()
            val extraNotes = mutableListOf<String>()

            if (!lookupKey.isNullOrBlank()) {
                resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.Data.DATA1,
                        ContactsContract.Data.DATA2,
                        ContactsContract.Data.DATA3
                    ),
                    "${ContactsContract.Data.LOOKUP_KEY} = ?",
                    arrayOf(lookupKey),
                    null
                )?.use { dataCursor ->
                    while (dataCursor.moveToNext()) {
                        when (dataCursor.getString(0)) {
                            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                                val relName = dataCursor.getString(1).orEmpty()
                                val relType = dataCursor.getInt(2)
                                val relLabel = ContactsContract.CommonDataKinds.Relation
                                    .getTypeLabel(context.resources, relType, dataCursor.getString(3))
                                    .toString()
                                relations.add("$relLabel ($relName)".trim())
                            }
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                dataCursor.getString(1)?.takeIf { it.isNotBlank() }?.let { extraNotes.add("Org: $it") }
                            }
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                                dataCursor.getString(1)?.takeIf { it.isNotBlank() }?.let { extraNotes.add("Note: ${it.take(80)}") }
                            }
                        }
                    }
                }
            }

            ContactMatchInfo(
                displayName = resolvedName,
                starred = starred,
                relations = relations,
                notesOrOrg = extraNotes.joinToString("; ").ifBlank { null }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookupSmsEngagement(identifier: String, contactName: String?): SmsEngagementInfo? {
        if (!hasPerm(Manifest.permission.READ_SMS)) return null
        val digits = identifier.filter { it.isDigit() }.takeLast(7)
        if (digits.length < 7 && contactName.isNullOrBlank()) return null

        return try {
            var sentCount = 0
            var inboxCount = 0
            var lastSentAt: Long? = null
            val recent = mutableListOf<String>()

            val selection = if (digits.length >= 7) {
                "${Telephony.Sms.ADDRESS} LIKE ?"
            } else {
                null
            }
            val args = if (digits.length >= 7) arrayOf("%$digits%") else null
            if (selection == null) return null

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.TYPE, Telephony.Sms.BODY, Telephony.Sms.DATE),
                selection,
                args,
                "${Telephony.Sms.DATE} DESC LIMIT 20"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(0)
                    val body = cursor.getString(1).orEmpty().replace('\n', ' ').take(100)
                    val date = cursor.getLong(2)
                    if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        sentCount++
                        if (lastSentAt == null) lastSentAt = date
                        if (recent.size < 5) recent.add("[${formatEpoch(date)} YOU -> THEM]: $body")
                    } else if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                        inboxCount++
                        if (recent.size < 5) recent.add("[${formatEpoch(date)} THEM -> YOU]: $body")
                    }
                }
            }

            if (sentCount == 0 && inboxCount == 0) null
            else SmsEngagementInfo(
                sentByUserCount = sentCount,
                receivedCount = inboxCount,
                lastUserSentTimestamp = lastSentAt,
                recentTurnsFormatted = recent.reversed()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookupRecentCalls(identifier: String, contactName: String?): CallEngagementInfo? {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) return null
        val digits = identifier.filter { it.isDigit() }.takeLast(7)
        val since = System.currentTimeMillis() - 7L * 24L * 3600_000L
        val fifteenMinAgo = System.currentTimeMillis() - 15L * 60_000L

        return try {
            var outgoing = 0
            var answered = 0
            var missed = 0
            var within15m = false
            val lines = mutableListOf<String>()

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC LIMIT 60"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val num = cursor.getString(0).orEmpty()
                    val cachedName = cursor.getString(1).orEmpty()
                    val numDigits = num.filter { it.isDigit() }
                    val matches = (digits.length >= 7 && numDigits.endsWith(digits)) ||
                        (!contactName.isNullOrBlank() && cachedName.equals(contactName, ignoreCase = true)) ||
                        (identifier.length >= 3 && cachedName.equals(identifier, ignoreCase = true))
                    if (!matches) continue

                    val type = cursor.getInt(2)
                    val date = cursor.getLong(3)
                    val dur = cursor.getLong(4)
                    if (date >= fifteenMinAgo) within15m = true

                    val label = when (type) {
                        CallLog.Calls.OUTGOING_TYPE -> {
                            outgoing++
                            "Outgoing (${dur}s)"
                        }
                        CallLog.Calls.INCOMING_TYPE -> {
                            answered++
                            "Incoming (${dur}s)"
                        }
                        CallLog.Calls.MISSED_TYPE -> {
                            missed++
                            "MISSED CALL"
                        }
                        else -> "Call"
                    }
                    if (lines.size < 4) {
                        lines.add("[${formatEpoch(date)}] $label")
                    }
                }
            }

            if (outgoing == 0 && answered == 0 && missed == 0) null
            else CallEngagementInfo(outgoing, answered, missed, within15m, lines)
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookupNearbyCalendar(senderOrTitle: String): List<String> {
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) return emptyList()
        val now = System.currentTimeMillis()
        val start = now - 3600_000L
        val end = now + 6L * 3600_000L
        return try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(builder, start)
            android.content.ContentUris.appendId(builder, end)

            val results = mutableListOf<String>()
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < 4) {
                    val title = cursor.getString(0).orEmpty()
                    val begin = cursor.getLong(1)
                    if (title.isNotBlank()) {
                        val matchTag = if (senderOrTitle.length >= 3 && title.contains(senderOrTitle, ignoreCase = true)) " [MATCHES SENDER]" else ""
                        results.add("${formatEpoch(begin)}: $title$matchTag")
                    }
                }
            }
            results
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        fun buildSenderKey(packageName: String, shortcutId: String?, sender: String): String {
            val idPart = shortcutId?.takeIf { it.isNotBlank() } ?: sender.trim().lowercase()
            return "$packageName|$idPart"
        }
    }
}
