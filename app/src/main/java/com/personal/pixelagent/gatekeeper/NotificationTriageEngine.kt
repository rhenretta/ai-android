package com.personal.pixelagent.gatekeeper

import android.app.Notification
import android.content.Context
import com.personal.pixelagent.agent.TriageContract
import com.personal.pixelagent.agent.TriageVerdict
import com.personal.pixelagent.context.CommunicationContextEngine
import com.personal.pixelagent.context.SeenMessageTracker
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.memory.MemoryRepository
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.network.GeminiInteractionsClient
import com.personal.pixelagent.observability.TraceLogger
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CapturedNotificationPayload(
    val sbnKey: String,
    val shortcutId: String?,
    val packageName: String,
    val appName: String,
    val category: String?,
    val isConversationShortcut: Boolean,
    val sender: String,
    val title: String,
    val body: String,
    val messagingThreadLines: List<String>,
    val postedAt: Long
)

class NotificationTriageEngine(
    private val context: Context,
    private val dao: AgentDao,
    private val settingsStore: SecureSettingsStore,
    private val contextEngine: CommunicationContextEngine,
    private val seenMessageTracker: SeenMessageTracker,
    private val memoryRepository: MemoryRepository,
    private val interactionsClient: GeminiInteractionsClient,
    private val actionIntentCache: ActionIntentCache,
    private val channelsManager: NotificationChannelsManager,
    private val traceLogger: TraceLogger
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val clockFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy h:mm a z", Locale.US)

    suspend fun evaluateAndRoute(payload: CapturedNotificationPayload) {
        val startedNanos = System.nanoTime()
        val senderKey = CommunicationContextEngine.buildSenderKey(
            payload.packageName,
            payload.shortcutId,
            payload.sender.ifBlank { payload.title }
        )

        // Fast-path: If user already has this exact conversation open on the unlocked screen, skip buzzing & API call
        if (
            seenMessageTracker.isConversationCurrentlyOpenOnScreen(
                packageName = payload.packageName,
                sender = payload.sender,
                title = payload.title,
                appName = payload.appName
            )
        ) {
            actionIntentCache.recordEngagement(
                senderKey = senderKey,
                packageName = payload.packageName,
                displayName = payload.sender.ifBlank { payload.title },
                inboundSeen = true,
                opened = true
            )
            val rationale = "Conversation thread is already open in the foreground; suppressed redundant alert."
            val recordId = dao.insertNotificationRecord(
                NotificationRecordEntity(
                    sbnKey = payload.sbnKey,
                    shortcutId = payload.shortcutId,
                    packageName = payload.packageName,
                    appName = payload.appName,
                    sender = payload.sender,
                    title = payload.title,
                    body = payload.body,
                    threadMessagesJson = json.encodeToString(payload.messagingThreadLines),
                    category = payload.category,
                    postedAt = payload.postedAt,
                    reasoningRationale = rationale,
                    tier = TriageTier.URGENT,
                    summaryLine = "${payload.title}: ${payload.body}".take(85),
                    clearedFromDigest = true
                )
            )
            actionIntentCache.bindRecordId(recordId, payload.sbnKey)
            if (!settingsStore.isPrivateModeActive()) {
                traceLogger.logDecision(
                    subsystem = "GATEKEEPER",
                    eventType = "SUPPRESSED_ALREADY_OPEN_THREAD",
                    reasoningRationale = rationale,
                    decisionPayload = "id=$recordId app=${payload.appName} title=${payload.title}"
                )
            }
            return
        }

        actionIntentCache.recordEngagement(
            senderKey = senderKey,
            packageName = payload.packageName,
            displayName = payload.sender.ifBlank { payload.title },
            inboundSeen = true
        )

        try {
            val (dossier, memoryContext) = coroutineScope {
                val dossierJob = async {
                    contextEngine.buildDossier(
                        packageName = payload.packageName,
                        senderRaw = payload.sender,
                        titleRaw = payload.title,
                        shortcutId = payload.shortcutId
                    )
                }
                val memJob = async {
                    val queryForMemory = "${payload.appName} ${payload.sender} ${payload.title} ${payload.body}".trim()
                    memoryRepository.retrieveContext(queryForMemory, topK = 8)
                }
                dossierJob.await() to memJob.await()
            }

            val formattedLocalTime = clockFmt.format(
                Instant.ofEpochMilli(payload.postedAt).atZone(ZoneId.systemDefault())
            )
            val prompt = TriageContract.buildTriagePrompt(
                localTimeFormatted = formattedLocalTime,
                appName = payload.appName,
                packageName = payload.packageName,
                category = payload.category,
                sender = payload.sender,
                title = payload.title,
                body = payload.body,
                messagingThreadLines = payload.messagingThreadLines,
                communicationDossierText = dossier.formatForPrompt(),
                memoryContextText = memoryContext.formatForPrompt()
            )

            val triageModel = settingsStore.getTriageModel()
            val triageThinkingLevel = settingsStore.getTriageThinkingLevel()
            val rawVerdictJson = interactionsClient.generateStructuredJson(
                model = triageModel,
                systemInstruction = TriageContract.BASELINE_SYSTEM_INSTRUCTION,
                userPrompt = prompt,
                jsonSchema = TriageContract.JSON_SCHEMA,
                thinkingLevel = triageThinkingLevel
            )

            val verdict = json.decodeFromString(TriageVerdict.serializer(), rawVerdictJson)
            if (verdict.rationale.isBlank()) {
                throw IllegalStateException("Triage model violated Rationale-First contract (blank rationale).")
            }

            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L

            // Clear any prior degraded error state upon successful triage
            val wasDegraded = settingsStore.state.value.lastDegradedError != null
            if (wasDegraded) {
                settingsStore.setLastDegradedError(null)
            }

            traceLogger.logDecision(
                subsystem = "TRIAGE",
                eventType = "NOTIFICATION_VERDICT",
                reasoningRationale = verdict.rationale,
                decisionPayload = "tier=${verdict.tier.name} latency=${elapsedMs}ms model=$triageModel thinking=$triageThinkingLevel app=${payload.appName} title=${payload.title}"
            )

            val resolvedTier = if (verdict.tier == TriageTier.PASSTHROUGH_ERROR) TriageTier.URGENT else verdict.tier
            val openedMidFlight = seenMessageTracker.isConversationCurrentlyOpenOnScreen(
                packageName = payload.packageName,
                sender = payload.sender,
                title = payload.title,
                appName = payload.appName
            )

            val recordId = dao.insertNotificationRecord(
                NotificationRecordEntity(
                    sbnKey = payload.sbnKey,
                    shortcutId = payload.shortcutId,
                    packageName = payload.packageName,
                    appName = payload.appName,
                    sender = payload.sender,
                    title = payload.title,
                    body = payload.body,
                    threadMessagesJson = json.encodeToString(payload.messagingThreadLines),
                    category = payload.category,
                    postedAt = payload.postedAt,
                    reasoningRationale = verdict.rationale,
                    tier = resolvedTier,
                    summaryLine = verdict.summaryBullet ?: "${payload.title}: ${payload.body}".take(85),
                    clearedFromDigest = openedMidFlight
                )
            )
            actionIntentCache.bindRecordId(recordId, payload.sbnKey)

            if (openedMidFlight) {
                actionIntentCache.recordEngagement(
                    senderKey = senderKey,
                    packageName = payload.packageName,
                    displayName = payload.sender.ifBlank { payload.title },
                    opened = true
                )
                return
            }

            when (resolvedTier) {
                TriageTier.URGENT -> {
                    val cached = actionIntentCache.getByRecordId(recordId)
                    channelsManager.postUrgentBreakthrough(
                        recordId = recordId,
                        appName = payload.appName,
                        title = verdict.urgentTitle?.takeIf { it.isNotBlank() } ?: payload.title,
                        body = verdict.urgentBody?.takeIf { it.isNotBlank() } ?: payload.body,
                        cachedActions = cached,
                        isErrorPassthrough = false
                    )
                    if (wasDegraded) {
                        val activeDigest = dao.getActiveDigestItems()
                        channelsManager.refreshLiveSummaryNotification(
                            activeDigestItems = activeDigest,
                            gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
                            degradedError = null
                        )
                    }
                }
                TriageTier.SUMMARY -> {
                    val activeDigest = dao.getActiveDigestItems()
                    channelsManager.refreshLiveSummaryNotification(
                        activeDigestItems = activeDigest,
                        gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
                        degradedError = null
                    )
                }
                TriageTier.SPAM, TriageTier.PASSTHROUGH_ERROR -> {
                    if (wasDegraded) {
                        val activeDigest = dao.getActiveDigestItems()
                        channelsManager.refreshLiveSummaryNotification(
                            activeDigestItems = activeDigest,
                            gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
                            degradedError = null
                        )
                    }
                }
            }

            AgentVoiceWidgetProvider.requestRefresh(context)
        } catch (t: Throwable) {
            // Explicit Fail-Fast & Fail-Open Visibility: NEVER conceal errors or drop human alerts silently
            val errorMsg = t.message ?: t::class.java.simpleName
            settingsStore.setLastDegradedError(errorMsg)
            traceLogger.logError(
                subsystem = "TRIAGE",
                eventType = "TRIAGE_FAILURE_FAIL_OPEN",
                contextMessage = "Failed to triage notification from ${payload.appName} (${payload.title})",
                throwable = t
            )

            val isDirectCommunication = payload.category == Notification.CATEGORY_MESSAGE || payload.isConversationShortcut
            val fallbackTier = if (isDirectCommunication) TriageTier.PASSTHROUGH_ERROR else TriageTier.SUMMARY

            val recordId = dao.insertNotificationRecord(
                NotificationRecordEntity(
                    sbnKey = payload.sbnKey,
                    shortcutId = payload.shortcutId,
                    packageName = payload.packageName,
                    appName = payload.appName,
                    sender = payload.sender,
                    title = payload.title,
                    body = payload.body,
                    threadMessagesJson = json.encodeToString(payload.messagingThreadLines),
                    category = payload.category,
                    postedAt = payload.postedAt,
                    reasoningRationale = "Triage API unavailable ($errorMsg). Explicit fail-open policy applied.",
                    tier = fallbackTier,
                    summaryLine = "[Untriaged] ${payload.title}: ${payload.body}".take(85),
                    clearedFromDigest = false,
                    errorContext = errorMsg
                )
            )
            actionIntentCache.bindRecordId(recordId, payload.sbnKey)

            if (isDirectCommunication) {
                channelsManager.postUrgentBreakthrough(
                    recordId = recordId,
                    appName = payload.appName,
                    title = payload.title,
                    body = payload.body,
                    cachedActions = actionIntentCache.getByRecordId(recordId),
                    isErrorPassthrough = true
                )
            }

            val activeDigest = dao.getActiveDigestItems()
            channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = activeDigest,
                gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
                degradedError = errorMsg
            )
            AgentVoiceWidgetProvider.requestRefresh(context)
        }
    }
}
