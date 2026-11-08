package com.personal.pixelagent.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.context.ActiveScreenContextService
import com.personal.pixelagent.context.CommunicationContextEngine
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.MemoryEntity
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TraceLogEntity
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.local.TurnModality
import com.personal.pixelagent.data.local.UndoActionType
import com.personal.pixelagent.data.settings.AgentSettingsSnapshot
import com.personal.pixelagent.gatekeeper.GatekeeperNotificationListener
import com.personal.pixelagent.network.GeminiVoicesClient
import com.personal.pixelagent.voice.LiveVoiceForegroundService
import com.personal.pixelagent.voice.LiveVoiceSessionState
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.UUID

data class PermissionReadinessState(
    val listenerEnabled: Boolean = false,
    val dndPolicyGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val smsGranted: Boolean = false,
    val callLogGranted: Boolean = false,
    val calendarGranted: Boolean = false
) {
    val coreGatekeeperReady: Boolean
        get() = listenerEnabled && dndPolicyGranted
}

private data class TransientUiFlags(
    val isSendingText: Boolean = false,
    val isValidatingKey: Boolean = false,
    val bannerError: String? = null,
    val ephemeralPrivateNotice: String? = null
)

data class MainUiState(
    val settings: AgentSettingsSnapshot,
    val permissions: PermissionReadinessState,
    val digestItems: List<NotificationRecordEntity>,
    val spamRecords: List<NotificationRecordEntity>,
    val spamCountToday: Int,
    val timeline: List<ConversationTurnEntity>,
    val activeMemories: List<MemoryEntity>,
    val recentTraces: List<TraceLogEntity>,
    val voiceSession: LiveVoiceSessionState,
    val isSendingText: Boolean,
    val isValidatingKey: Boolean,
    val bannerError: String?,
    val ephemeralPrivateNotice: String?
) {
    /** Setup card vanishes permanently once API key + Notification Listener + DND Policy are active. */
    val showSetupCard: Boolean
        get() = !settings.hasApiKey || !permissions.coreGatekeeperReady
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PixelAgentApp = application as PixelAgentApp
    private val container = app.container
    private val dao = container.database.agentDao()

    private val permissionsFlow = MutableStateFlow(readPermissionsSnapshot())
    private val transientFlags = MutableStateFlow(TransientUiFlags())
    private var ephemeralNoticeJob: Job? = null

    private val startOfTodayMillis: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val coreDataFlow = combine(
        container.settingsStore.state,
        permissionsFlow,
        dao.observeActiveDigestItems(),
        dao.observeSpamRecords(),
        dao.observeSpamCountSince(startOfTodayMillis)
    ) { settings, perms, digest, spamList, spamToday ->
        CoreSlice(settings, perms, digest, spamList, spamToday)
    }

    private val streamDataFlow = combine(
        dao.observeConversationTimeline(),
        dao.observeActiveMemories(),
        dao.observeRecentTraces(),
        LiveVoiceForegroundService.sessionState,
        transientFlags
    ) { timeline, memories, traces, voice, flags ->
        StreamSlice(timeline, memories, traces, voice, flags)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreDataFlow,
        streamDataFlow
    ) { core, stream ->
        MainUiState(
            settings = core.settings,
            permissions = core.perms,
            digestItems = core.digest,
            spamRecords = core.spamList,
            spamCountToday = core.spamToday,
            timeline = stream.timeline,
            activeMemories = stream.memories,
            recentTraces = stream.traces,
            voiceSession = stream.voice,
            isSendingText = stream.flags.isSendingText,
            isValidatingKey = stream.flags.isValidatingKey,
            bannerError = stream.flags.bannerError ?: core.settings.lastDegradedError,
            ephemeralPrivateNotice = stream.flags.ephemeralPrivateNotice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            settings = container.settingsStore.state.value,
            permissions = permissionsFlow.value,
            digestItems = emptyList(),
            spamRecords = emptyList(),
            spamCountToday = 0,
            timeline = emptyList(),
            activeMemories = emptyList(),
            recentTraces = emptyList(),
            voiceSession = LiveVoiceForegroundService.sessionState.value,
            isSendingText = false,
            isValidatingKey = false,
            bannerError = container.settingsStore.state.value.lastDegradedError,
            ephemeralPrivateNotice = null
        )
    )

    fun refreshPermissions() {
        val updated = readPermissionsSnapshot()
        permissionsFlow.value = updated
        if (updated.dndPolicyGranted) {
            viewModelScope.launch {
                container.zenModeController.syncGatekeeperRule()
                AgentVoiceWidgetProvider.requestRefresh(app)
            }
        }
    }

    fun dismissBannerError() {
        transientFlags.update { it.copy(bannerError = null) }
        container.settingsStore.setLastDegradedError(null)
        viewModelScope.launch {
            val activeDigest = dao.getActiveDigestItems()
            container.channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = activeDigest,
                gatekeeperEnabled = container.settingsStore.isGatekeeperEnabled(),
                degradedError = null
            )
            AgentVoiceWidgetProvider.requestRefresh(app)
        }
    }

    fun validateAndSaveApiKey(rawKey: String, onSuccess: () -> Unit = {}) {
        val candidate = rawKey.trim()
        if (candidate.isEmpty()) {
            transientFlags.update { it.copy(bannerError = "Clipboard or input did not contain a Gemini API key.") }
            return
        }

        viewModelScope.launch {
            transientFlags.update { it.copy(isValidatingKey = true, bannerError = null) }
            try {
                // Fail-fast live verification against Gemini Voices endpoint before persisting
                val probeClient = GeminiVoicesClient(
                    httpClient = container.httpClient,
                    apiKeyProvider = { candidate }
                )
                probeClient.listVoices(pageSize = 5)

                container.settingsStore.saveApiKey(candidate)
                container.zenModeController.syncGatekeeperRule()
                val activeDigest = dao.getActiveDigestItems()
                container.channelsManager.refreshLiveSummaryNotification(
                    activeDigestItems = activeDigest,
                    gatekeeperEnabled = container.settingsStore.isGatekeeperEnabled(),
                    degradedError = null
                )
                AgentVoiceWidgetProvider.requestRefresh(app)
                transientFlags.update { it.copy(isValidatingKey = false, bannerError = null) }
                onSuccess()
            } catch (t: Throwable) {
                container.traceLogger.logError(
                    subsystem = "SETTINGS",
                    eventType = "API_KEY_VALIDATION_FAILED",
                    contextMessage = "Rejected invalid or unreachable Gemini API key",
                    throwable = t
                )
                transientFlags.update {
                    it.copy(
                        isValidatingKey = false,
                        bannerError = "API key verification failed: ${t.message ?: t::class.java.simpleName}"
                    )
                }
            }
        }
    }

    fun sendTextMessage(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || transientFlags.value.isSendingText) return

        viewModelScope.launch {
            transientFlags.update { it.copy(isSendingText = true, bannerError = null) }
            try {
                val result = container.chatOrchestrator.sendUserMessage(trimmed)
                transientFlags.update { it.copy(isSendingText = false) }
                if (result.exitedPrivateMode) {
                    showEphemeralPrivateNotice("Exited Private Mode · Conversation permanently deleted")
                }
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(
                        isSendingText = false,
                        bannerError = t.message ?: "Failed to reach Gemini API."
                    )
                }
            }
        }
    }

    fun togglePrivateMode() {
        viewModelScope.launch {
            if (container.settingsStore.isPrivateModeActive()) {
                container.toolExecutor.exitPrivateModeAndPurge()
                showEphemeralPrivateNotice("Exited Private Mode · Conversation permanently deleted")
            } else {
                ephemeralNoticeJob?.cancel()
                transientFlags.update { it.copy(ephemeralPrivateNotice = null) }
                container.toolExecutor.enterPrivateMode(
                    rationale = "User enabled Private Mode from workspace controls.",
                    sessionId = UUID.randomUUID().toString(),
                    modality = TurnModality.TEXT,
                    triggeringTurnId = null
                )
            }
        }
    }

    fun exitPrivateModeAndWipe() {
        viewModelScope.launch {
            container.toolExecutor.exitPrivateModeAndPurge()
            showEphemeralPrivateNotice("Exited Private Mode · Conversation permanently deleted")
        }
    }

    private fun showEphemeralPrivateNotice(message: String) {
        ephemeralNoticeJob?.cancel()
        ephemeralNoticeJob = viewModelScope.launch {
            transientFlags.update { it.copy(ephemeralPrivateNotice = message) }
            delay(4_500L)
            transientFlags.update { it.copy(ephemeralPrivateNotice = null) }
        }
    }

    fun toggleGatekeeper() {
        val perms = readPermissionsSnapshot()
        permissionsFlow.value = perms
        if (!perms.dndPolicyGranted) {
            transientFlags.update {
                it.copy(bannerError = "Grant Modes / Do Not Disturb access first so Gatekeeper can silence unvetted alerts.")
            }
            return
        }

        viewModelScope.launch {
            val nextEnabled = !container.settingsStore.isGatekeeperEnabled()
            container.settingsStore.setGatekeeperEnabled(nextEnabled)
            container.zenModeController.syncGatekeeperRule(enabled = nextEnabled)
            val activeDigest = dao.getActiveDigestItems()
            container.channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = activeDigest,
                gatekeeperEnabled = nextEnabled,
                degradedError = container.settingsStore.state.value.lastDegradedError
            )
            AgentVoiceWidgetProvider.requestRefresh(app)
        }
    }

    fun setSleepStrictCalls(strict: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setSleepStrictCalls(strict)
            container.zenModeController.syncGatekeeperRule(sleepStrictCalls = strict)
            container.traceLogger.logDecision(
                subsystem = "ZEN_POLICY",
                eventType = "SLEEP_CALL_POLICY_CHANGED",
                reasoningRationale = "User updated sleep phone call filter from Controls sheet.",
                decisionPayload = "sleepStrictCalls=$strict"
            )
        }
    }

    fun setTriageModel(modelId: String) {
        viewModelScope.launch {
            container.settingsStore.setTriageModel(modelId)
            container.traceLogger.logDecision(
                subsystem = "SETTINGS",
                eventType = "TRIAGE_MODEL_CHANGED",
                reasoningRationale = "User selected notification triage model in Controls sheet.",
                decisionPayload = "triageModel=$modelId"
            )
        }
    }

    fun setTriageThinkingLevel(level: String) {
        viewModelScope.launch {
            try {
                container.settingsStore.setTriageThinkingLevel(level)
                container.traceLogger.logDecision(
                    subsystem = "SETTINGS",
                    eventType = "TRIAGE_THINKING_LEVEL_CHANGED",
                    reasoningRationale = "User selected notification triage thinking level in Controls sheet.",
                    decisionPayload = "thinkingLevel=$level"
                )
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = t.message ?: "Failed to update thinking level.")
                }
            }
        }
    }

    fun openNotificationItem(record: NotificationRecordEntity) {
        viewModelScope.launch {
            val senderKey = CommunicationContextEngine.buildSenderKey(
                record.packageName,
                record.shortcutId,
                record.sender.ifBlank { record.title }
            )
            val opened = container.actionIntentCache.openNotificationTarget(
                context = app,
                recordId = record.id,
                fallbackPackageName = record.packageName,
                senderKey = senderKey,
                senderDisplay = record.sender.ifBlank { record.title }
            )
            if (!opened) {
                transientFlags.update {
                    it.copy(bannerError = "Could not launch ${record.appName}; the notification intent is no longer active.")
                }
            }
        }
    }

    fun teachFromNotification(
        record: NotificationRecordEntity,
        targetTier: TriageTier,
        learnedRuleStatement: String
    ) {
        viewModelScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val rationale = "User explicitly taught Triage Gatekeeper from inline notification card (${record.tier.name} -> ${targetTier.name})."
            val reclassifyRes = container.toolExecutor.executeTool(
                name = "reclassify_or_rescue_notification",
                args = buildJsonObject {
                    put("rationale", JsonPrimitive(rationale))
                    put("notification_id", JsonPrimitive(record.id))
                    put("new_tier", JsonPrimitive(targetTier.name))
                },
                sessionId = sessionId,
                modality = TurnModality.TEXT
            )
            val err = reclassifyRes["error"]?.toString()?.trim('"')
            if (!err.isNullOrBlank()) {
                transientFlags.update { it.copy(bannerError = err) }
                return@launch
            }

            if (learnedRuleStatement.isNotBlank()) {
                val memRes = container.toolExecutor.executeTool(
                    name = "remember_preference_or_fact",
                    args = buildJsonObject {
                        put("rationale", JsonPrimitive(rationale))
                        put("category", JsonPrimitive(MemoryCategory.PREFERENCE.name))
                        put("memory_statement", JsonPrimitive(learnedRuleStatement))
                    },
                    sessionId = sessionId,
                    modality = TurnModality.TEXT
                )
                val memErr = memRes["error"]?.toString()?.trim('"')
                if (!memErr.isNullOrBlank()) {
                    transientFlags.update { it.copy(bannerError = memErr) }
                }
            }
        }
    }

    fun clearDigest() {
        viewModelScope.launch {
            dao.clearActiveDigest()
            container.channelsManager.refreshLiveSummaryNotification(
                activeDigestItems = emptyList(),
                gatekeeperEnabled = container.settingsStore.isGatekeeperEnabled(),
                degradedError = container.settingsStore.state.value.lastDegradedError
            )
            AgentVoiceWidgetProvider.requestRefresh(app)
        }
    }

    fun purgeAllSpam() {
        viewModelScope.launch {
            dao.purgeAllSpam()
            container.traceLogger.logDecision(
                subsystem = "SPAM_VAULT",
                eventType = "PURGE_SPAM",
                reasoningRationale = "User emptied disregarded spam vault.",
                decisionPayload = "purged_all_spam=true"
            )
        }
    }

    fun createMemoryFromUi(category: MemoryCategory, content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                container.memoryRepository.saveMemory(
                    rationale = "User created rule directly in Rules & Memories sheet.",
                    category = category,
                    content = clean,
                    sourceSummary = "Added manually in Rules & Memories"
                )
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = "Could not save rule: ${t.message ?: t::class.java.simpleName}")
                }
            }
        }
    }

    fun updateMemoryFromUi(memoryId: Long, category: MemoryCategory, updatedContent: String) {
        val clean = updatedContent.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                container.memoryRepository.updateMemory(
                    rationale = "User edited rule #$memoryId in Rules & Memories sheet.",
                    memoryId = memoryId,
                    newContent = clean,
                    newCategory = category,
                    sourceSummary = "Edited in Rules & Memories"
                )
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = "Could not update rule: ${t.message ?: t::class.java.simpleName}")
                }
            }
        }
    }

    fun deactivateMemory(memoryId: Long) {
        viewModelScope.launch {
            try {
                container.memoryRepository.deactivateMemory(
                    rationale = "User deleted rule #$memoryId from Rules & Memories sheet.",
                    memoryId = memoryId
                )
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = "Could not delete rule: ${t.message ?: t::class.java.simpleName}")
                }
            }
        }
    }

    fun switchVoiceFromControls(voiceId: String) {
        viewModelScope.launch {
            try {
                val verified = container.voicesClient.validateVoiceExistsOrThrow(voiceId)
                val previous = container.settingsStore.setVoiceName(verified.id)
                container.toolExecutor.onActiveVoiceSwitched?.invoke(verified.id)
                AgentVoiceWidgetProvider.requestRefresh(app)
                container.traceLogger.logDecision(
                    subsystem = "VOICE_LIVE",
                    eventType = "MANUAL_VOICE_SELECT",
                    reasoningRationale = "User selected voice persona '${verified.id}' in Controls sheet.",
                    decisionPayload = "$previous -> ${verified.id}"
                )
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = t.message ?: "Failed to switch voice.")
                }
            }
        }
    }

    fun undoActionReceipt(turn: ConversationTurnEntity) {
        if (turn.undone || turn.undoActionType == UndoActionType.NONE) return
        viewModelScope.launch {
            try {
                when (turn.undoActionType) {
                    UndoActionType.DEACTIVATE_MEMORY -> {
                        val memoryId = turn.undoPayload?.toLongOrNull() ?: return@launch
                        container.memoryRepository.deactivateMemory(
                            rationale = "User tapped Undo on inline Action Receipt #${turn.id}.",
                            memoryId = memoryId
                        )
                    }
                    UndoActionType.REACTIVATE_MEMORY -> {
                        val memoryId = turn.undoPayload?.toLongOrNull() ?: return@launch
                        container.memoryRepository.reactivateMemory(
                            rationale = "User tapped Undo on rule deletion receipt #${turn.id}.",
                            memoryId = memoryId
                        )
                    }
                    UndoActionType.RESTORE_MEMORY_CONTENT -> {
                        val payloadJson = turn.undoPayload?.takeIf { it.isNotBlank() } ?: return@launch
                        val obj = Json.parseToJsonElement(payloadJson).jsonObject
                        val memoryId = obj["id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val prevCategory = obj["category"]?.jsonPrimitive?.content
                            ?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() }
                        val prevContent = obj["content"]?.jsonPrimitive?.content ?: return@launch
                        val prevSource = obj["source"]?.jsonPrimitive?.content ?: "Restored via Undo"
                        container.memoryRepository.updateMemory(
                            rationale = "User tapped Undo on rule edit receipt #${turn.id}.",
                            memoryId = memoryId,
                            newContent = prevContent,
                            newCategory = prevCategory,
                            sourceSummary = prevSource
                        )
                    }
                    UndoActionType.RESTORE_VOICE -> {
                        val prevVoice = turn.undoPayload?.takeIf { it.isNotBlank() } ?: return@launch
                        container.settingsStore.setVoiceName(prevVoice)
                        container.toolExecutor.onActiveVoiceSwitched?.invoke(prevVoice)
                        AgentVoiceWidgetProvider.requestRefresh(app)
                    }
                    UndoActionType.RESTORE_NOTIFICATION_TIER -> {
                        val parts = turn.undoPayload?.split("|") ?: return@launch
                        if (parts.size == 2) {
                            val recordId = parts[0].toLongOrNull() ?: return@launch
                            val prevTier = runCatching { TriageTier.valueOf(parts[1]) }.getOrNull() ?: return@launch
                            dao.updateNotificationTier(recordId, prevTier, cleared = false)
                            val activeDigest = dao.getActiveDigestItems()
                            container.channelsManager.refreshLiveSummaryNotification(
                                activeDigestItems = activeDigest,
                                gatekeeperEnabled = container.settingsStore.isGatekeeperEnabled(),
                                degradedError = container.settingsStore.state.value.lastDegradedError
                            )
                            AgentVoiceWidgetProvider.requestRefresh(app)
                        }
                    }
                    UndoActionType.NONE -> Unit
                }
                dao.markTurnUndone(turn.id)
            } catch (t: Throwable) {
                transientFlags.update {
                    it.copy(bannerError = "Undo failed: ${t.message ?: t::class.java.simpleName}")
                }
            }
        }
    }

    private fun readPermissionsSnapshot(): PermissionReadinessState {
        fun granted(perm: String): Boolean =
            ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED

        return PermissionReadinessState(
            listenerEnabled = GatekeeperNotificationListener.isEnabled(app),
            dndPolicyGranted = container.zenModeController.hasPolicyAccess(),
            accessibilityEnabled = ActiveScreenContextService.isEnabled(app),
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
            contactsGranted = granted(Manifest.permission.READ_CONTACTS),
            smsGranted = granted(Manifest.permission.READ_SMS),
            callLogGranted = granted(Manifest.permission.READ_CALL_LOG),
            calendarGranted = granted(Manifest.permission.READ_CALENDAR)
        )
    }

    private data class CoreSlice(
        val settings: AgentSettingsSnapshot,
        val perms: PermissionReadinessState,
        val digest: List<NotificationRecordEntity>,
        val spamList: List<NotificationRecordEntity>,
        val spamToday: Int
    )

    private data class StreamSlice(
        val timeline: List<ConversationTurnEntity>,
        val memories: List<MemoryEntity>,
        val traces: List<TraceLogEntity>,
        val voice: LiveVoiceSessionState,
        val flags: TransientUiFlags
    )
}
