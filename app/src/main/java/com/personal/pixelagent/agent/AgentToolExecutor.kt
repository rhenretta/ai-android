package com.personal.pixelagent.agent

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.personal.pixelagent.context.CommunicationContextEngine
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.local.TurnModality
import com.personal.pixelagent.data.local.TurnRole
import com.personal.pixelagent.data.local.UndoActionType
import com.personal.pixelagent.data.memory.MemoryRepository
import com.personal.pixelagent.data.settings.PrivateSessionBounds
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.gatekeeper.ActionIntentCache
import com.personal.pixelagent.gatekeeper.NotificationChannelsManager
import com.personal.pixelagent.gatekeeper.ZenModeController
import com.personal.pixelagent.network.GeminiVoicesClient
import com.personal.pixelagent.observability.TraceLogger
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared Rationale-First Tool Executor used identically by both synchronous Live Voice
 * (`gemini-3.8-live-extended-thinking` with `behavior = "NON_BLOCKING"`) and Text Chat
 * (`gemini-3.8-flash`). Every mutating or evaluative tool places `rationale` first in its schema,
 * logs to `TraceLogger`, and emits transparent inline Action Receipts in the conversation timeline.
 */
class AgentToolExecutor(
    private val appContext: Context,
    private val dao: AgentDao,
    private val settingsStore: SecureSettingsStore,
    private val memoryRepository: MemoryRepository,
    private val voicesClient: GeminiVoicesClient,
    private val contextEngine: CommunicationContextEngine,
    private val actionIntentCache: ActionIntentCache,
    private val channelsManager: NotificationChannelsManager,
    private val zenModeController: ZenModeController,
    private val traceLogger: TraceLogger
) {
    /** Optional callback registered by LiveVoiceForegroundService to hot-swap active voice mid-call. */
    @Volatile
    var onActiveVoiceSwitched: ((newVoiceName: String) -> Unit)? = null

    /** Optional callback registered by LiveVoiceForegroundService to mirror & scrub Private Mode state live. */
    @Volatile
    var onPrivateModeChanged: ((enabled: Boolean, purgedBounds: PrivateSessionBounds?) -> Unit)? = null

    @Volatile
    private var lastExitedPrivateBounds: PrivateSessionBounds? = null

    fun consumeLastExitedPrivateBounds(): PrivateSessionBounds? {
        val captured = lastExitedPrivateBounds
        lastExitedPrivateBounds = null
        return captured
    }

    fun buildInteractionsToolsJson(): JsonArray = buildToolArray(forLiveWebSocket = false)

    fun buildLiveNonBlockingDeclarationsJson(): JsonArray = buildToolArray(forLiveWebSocket = true)

    suspend fun executeTool(
        name: String,
        args: JsonObject,
        sessionId: String,
        modality: TurnModality,
        triggeringTurnId: Long? = null
    ): JsonObject {
        return try {
            when (name) {
                "set_private_mode" -> handleSetPrivateMode(args, sessionId, modality, triggeringTurnId)
                "list_memories" -> handleListMemories(args)
                "remember_preference_or_fact" -> handleRememberPreference(args, sessionId, modality)
                "update_memory" -> handleUpdateMemory(args, sessionId, modality)
                "delete_memory", "forget_memory" -> handleDeleteMemory(args, sessionId, modality)
                "query_notifications" -> handleQueryNotifications(args)
                "reclassify_or_rescue_notification" -> handleReclassifyNotification(args, sessionId, modality)
                "clear_digest_summary" -> handleClearDigest(args, sessionId, modality)
                "search_available_voices" -> handleSearchVoices(args)
                "switch_agent_voice" -> handleSwitchVoice(args, sessionId, modality)
                "lookup_contact_and_history" -> handleLookupContactHistory(args)
                "send_notification_quick_reply" -> handleQuickReply(args, sessionId, modality)
                "configure_gatekeeper_mode" -> handleConfigureGatekeeper(args, sessionId, modality)
                else -> throw IllegalArgumentException("Unknown tool '$name'")
            }
        } catch (t: Throwable) {
            traceLogger.logError(
                subsystem = "TOOLS",
                eventType = "TOOL_ERROR_$name",
                contextMessage = "Tool execution failed for $name with args=$args",
                throwable = t
            )
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("error", JsonPrimitive(t.message ?: t::class.java.simpleName))
            }
        }
    }

    private suspend fun handleSetPrivateMode(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality,
        triggeringTurnId: Long?
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("Missing required boolean parameter 'enabled'.")

        return if (enabled) {
            val bounds = enterPrivateMode(
                rationale = rationale,
                sessionId = sessionId,
                modality = modality,
                triggeringTurnId = triggeringTurnId
            )
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("private_mode_active", JsonPrimitive(true))
                put("start_turn_id", JsonPrimitive(bounds.startTurnId))
                put(
                    "message",
                    JsonPrimitive("Private Mode is now active. Everything from the entry request through the exit request will be permanently deleted when exiting.")
                )
            }
        } else {
            val purged = exitPrivateModeAndPurge(recordForCurrentTurn = true)
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("private_mode_active", JsonPrimitive(false))
                put("purged", JsonPrimitive(purged != null))
                put(
                    "message",
                    JsonPrimitive("Private Mode exited. All dialogue, temporary memories, and session traces from the entry request through this exit request are permanently deleted.")
                )
            }
        }
    }

    suspend fun enterPrivateMode(
        rationale: String,
        sessionId: String,
        modality: TurnModality,
        triggeringTurnId: Long? = null
    ): PrivateSessionBounds {
        val existing = settingsStore.getPrivateSessionBounds()
        if (existing != null) {
            return existing
        }

        val startTurnId = triggeringTurnId ?: ((dao.getMaxConversationTurnId() ?: 0L) + 1L)
        val startMemoryId = dao.getMaxMemoryId() ?: 0L
        val startTraceId = dao.getMaxTraceId() ?: 0L
        val bounds = PrivateSessionBounds(
            startTurnId = startTurnId,
            startMemoryId = startMemoryId,
            startTraceId = startTraceId
        )

        settingsStore.enterPrivateMode(bounds)

        // Logged AFTER startTraceId is captured so this trace is also permanently wiped on exit
        traceLogger.logDecision(
            subsystem = "PRIVACY",
            eventType = "ENTER_PRIVATE_MODE",
            reasoningRationale = rationale,
            decisionPayload = "startTurnId=$startTurnId startMemoryId=$startMemoryId"
        )

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Private Mode Active",
                receiptTitle = "Private Mode Active",
                receiptSubtitle = "Everything from your entry request until you exit will be permanently deleted"
            )
        )

        AgentVoiceWidgetProvider.requestRefresh(appContext)
        onPrivateModeChanged?.invoke(true, null)
        return bounds
    }

    suspend fun exitPrivateModeAndPurge(recordForCurrentTurn: Boolean = false): PrivateSessionBounds? {
        val bounds = settingsStore.exitPrivateModeAndConsumeBounds()
        if (bounds != null) {
            purgePrivateBounds(bounds)
            if (recordForCurrentTurn) {
                lastExitedPrivateBounds = bounds
            }
        }
        AgentVoiceWidgetProvider.requestRefresh(appContext)
        onPrivateModeChanged?.invoke(false, bounds)
        return bounds
    }

    suspend fun purgePrivateBounds(bounds: PrivateSessionBounds) {
        dao.deleteConversationTurnsFrom(bounds.startTurnId)
        dao.deleteMemoriesAfter(bounds.startMemoryId)
        dao.deleteNonTriageTracesAfter(bounds.startTraceId)
        runCatching {
            dao.executePragma(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
        }
    }

    private suspend fun handleRememberPreference(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val categoryRaw = args.optString("category") ?: "PREFERENCE"
        val category = runCatching { MemoryCategory.valueOf(categoryRaw.uppercase()) }
            .getOrDefault(MemoryCategory.PREFERENCE)
        val content = args.requireString("memory_statement")

        val memoryId = memoryRepository.saveMemory(
            rationale = rationale,
            category = category,
            content = content,
            sourceSummary = "Learned via ${modality.name.lowercase()} conversation"
        )

        val isPrivate = settingsStore.isPrivateModeActive()
        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = content,
                receiptTitle = when (category) {
                    MemoryCategory.STANDING_DIRECTIVE -> "Standing Rule Saved"
                    MemoryCategory.RELATIONSHIP -> "Relationship Context Saved"
                    MemoryCategory.ACTIVE_EXPECTATION -> "Active Expectation Saved"
                    MemoryCategory.PREFERENCE -> "Preference Saved"
                },
                receiptSubtitle = if (isPrivate) "$content (Temporary · Erased on Private Mode exit)" else content,
                undoActionType = UndoActionType.DEACTIVATE_MEMORY,
                undoPayload = memoryId.toString()
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("memory_id", JsonPrimitive(memoryId))
            put("category", JsonPrimitive(category.name))
            put("saved_statement", JsonPrimitive(content))
        }
    }

    private suspend fun handleListMemories(args: JsonObject): JsonObject {
        val categoryFilter = args.optString("category")
            ?.uppercase()
            ?.takeIf { it != "ALL" && it.isNotBlank() }
            ?.let { raw -> runCatching { MemoryCategory.valueOf(raw) }.getOrNull() }

        val allActive = memoryRepository.listActiveMemories()
        val filtered = if (categoryFilter != null) {
            allActive.filter { it.category == categoryFilter }
        } else {
            allActive
        }

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(filtered.size))
            put(
                "memories",
                buildJsonArray {
                    for (m in filtered) {
                        add(
                            buildJsonObject {
                                put("memory_id", JsonPrimitive(m.id))
                                put("id", JsonPrimitive(m.id))
                                put("category", JsonPrimitive(m.category.name))
                                put("statement", JsonPrimitive(m.content))
                                put("source_summary", JsonPrimitive(m.sourceSummary))
                            }
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleUpdateMemory(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val memoryId = args["memory_id"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("memory_id (integer) is required.")
        val updatedStatement = args.requireString("updated_memory_statement")
        val optionalCategory = args.optString("category")?.let { raw ->
            runCatching { MemoryCategory.valueOf(raw.uppercase()) }.getOrNull()
        }

        val (previous, updated) = memoryRepository.updateMemory(
            rationale = rationale,
            memoryId = memoryId,
            newContent = updatedStatement,
            newCategory = optionalCategory,
            sourceSummary = "Edited via ${modality.name.lowercase()} conversation"
        )

        val undoPayloadJson = buildJsonObject {
            put("id", JsonPrimitive(previous.id))
            put("category", JsonPrimitive(previous.category.name))
            put("content", JsonPrimitive(previous.content))
            put("source", JsonPrimitive(previous.sourceSummary))
        }.toString()

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = updated.content,
                receiptTitle = "Rule Updated (#${updated.id})",
                receiptSubtitle = "${updated.content} (was: ${previous.content})",
                undoActionType = UndoActionType.RESTORE_MEMORY_CONTENT,
                undoPayload = undoPayloadJson
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("memory_id", JsonPrimitive(updated.id))
            put("previous_statement", JsonPrimitive(previous.content))
            put("updated_statement", JsonPrimitive(updated.content))
            put("category", JsonPrimitive(updated.category.name))
        }
    }

    private suspend fun handleDeleteMemory(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val memoryId = args["memory_id"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("memory_id (integer) is required.")
        val removed = memoryRepository.deactivateMemory(rationale, memoryId)

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Deleted rule #${removed.id}",
                receiptTitle = "Rule Deleted (#${removed.id})",
                receiptSubtitle = removed.content,
                undoActionType = UndoActionType.REACTIVATE_MEMORY,
                undoPayload = removed.id.toString()
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("deleted_memory_id", JsonPrimitive(removed.id))
            put("deleted_statement", JsonPrimitive(removed.content))
        }
    }

    private suspend fun handleQueryNotifications(args: JsonObject): JsonObject {
        val query = args.optString("search_query").orEmpty()
        val tierFilter = args.optString("tier")?.uppercase()?.takeIf { it != "ALL" && it.isNotBlank() }?.let {
            runCatching { TriageTier.valueOf(it) }.getOrNull()
        }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)

        val records = dao.searchNotifications(query, tierFilter, limit)
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(records.size))
            put(
                "notifications",
                buildJsonArray {
                    for (r in records) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(r.id))
                                put("tier", JsonPrimitive(r.tier.name))
                                put("app_name", JsonPrimitive(r.appName))
                                put("sender", JsonPrimitive(r.sender))
                                put("title", JsonPrimitive(r.title))
                                put("body", JsonPrimitive(r.body))
                                put("posted_at_epoch_ms", JsonPrimitive(r.postedAt))
                                put("reasoning_rationale", JsonPrimitive(r.reasoningRationale))
                            }
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleReclassifyNotification(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val notificationId = args["notification_id"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("notification_id is required.")
        val targetTierRaw = args.requireString("new_tier").uppercase()
        val targetTier = TriageTier.valueOf(targetTierRaw)

        val existing = dao.getNotificationById(notificationId)
            ?: throw IllegalArgumentException("Notification #$notificationId not found.")
        val previousTier = existing.tier

        dao.updateNotificationTier(notificationId, targetTier, cleared = false)

        val senderKey = CommunicationContextEngine.buildSenderKey(
            existing.packageName,
            existing.shortcutId,
            existing.sender.ifBlank { existing.title }
        )
        actionIntentCache.recordEngagement(
            senderKey = senderKey,
            packageName = existing.packageName,
            displayName = existing.sender.ifBlank { existing.title },
            promoted = (targetTier == TriageTier.URGENT),
            markedSpam = (targetTier == TriageTier.SPAM)
        )

        if (targetTier == TriageTier.URGENT) {
            channelsManager.postUrgentBreakthrough(
                recordId = existing.id,
                appName = existing.appName,
                title = existing.title,
                body = existing.body,
                cachedActions = actionIntentCache.getByRecordId(existing.id)
            )
        }

        val activeDigest = dao.getActiveDigestItems()
        channelsManager.refreshLiveSummaryNotification(
            activeDigestItems = activeDigest,
            gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
            degradedError = settingsStore.state.value.lastDegradedError
        )
        AgentVoiceWidgetProvider.requestRefresh(appContext)

        traceLogger.logDecision(
            subsystem = "TOOLS",
            eventType = "RECLASSIFY_NOTIFICATION",
            reasoningRationale = rationale,
            decisionPayload = "id=$notificationId ${previousTier.name} -> ${targetTier.name}"
        )

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Moved '${existing.title}' to ${targetTier.name}",
                receiptTitle = "Notification Moved to ${targetTier.name}",
                receiptSubtitle = "${existing.appName}: ${existing.title}",
                undoActionType = UndoActionType.RESTORE_NOTIFICATION_TIER,
                undoPayload = "${existing.id}|${previousTier.name}"
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("notification_id", JsonPrimitive(notificationId))
            put("previous_tier", JsonPrimitive(previousTier.name))
            put("new_tier", JsonPrimitive(targetTier.name))
        }
    }

    private suspend fun handleClearDigest(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        dao.clearActiveDigest()
        channelsManager.refreshLiveSummaryNotification(
            activeDigestItems = emptyList(),
            gatekeeperEnabled = settingsStore.isGatekeeperEnabled(),
            degradedError = settingsStore.state.value.lastDegradedError
        )
        AgentVoiceWidgetProvider.requestRefresh(appContext)
        traceLogger.logDecision("TOOLS", "CLEAR_DIGEST", rationale, "Cleared active summary digest")

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Summary digest cleared",
                receiptTitle = "Summary Cleared",
                receiptSubtitle = "All held items marked as reviewed"
            )
        )
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    private suspend fun handleSearchVoices(args: JsonObject): JsonObject {
        val gender = args.optString("gender")
        val accent = args.optString("accent")
        val pitch = args.optString("pitch")
        val search = args.optString("search")

        val voices = voicesClient.listVoices(
            gender = gender,
            accent = accent,
            pitch = pitch,
            searchQuery = search,
            pageSize = 35
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("current_voice", JsonPrimitive(settingsStore.getVoiceName()))
            put("count", JsonPrimitive(voices.size))
            put(
                "voices",
                buildJsonArray {
                    for (v in voices) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(v.id))
                                put("display_name", JsonPrimitive(v.label))
                                v.gender?.let { put("gender", JsonPrimitive(it)) }
                                v.accent?.let { put("accent", JsonPrimitive(it)) }
                                v.pitch?.let { put("pitch", JsonPrimitive(it)) }
                                v.persona?.let { put("persona", JsonPrimitive(it)) }
                                v.description?.let { put("description", JsonPrimitive(it)) }
                            }
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleSwitchVoice(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val requestedVoiceId = args.requireString("voice_id")

        // Strict Configuration Fidelity: validate against Voices Catalog or fail fast (no silent fallback)
        val verifiedVoice = voicesClient.validateVoiceExistsOrThrow(requestedVoiceId)
        val previousVoice = settingsStore.setVoiceName(verifiedVoice.id)

        traceLogger.logDecision(
            subsystem = "VOICE_LIVE",
            eventType = "SWITCH_VOICE",
            reasoningRationale = rationale,
            decisionPayload = "$previousVoice -> ${verifiedVoice.id} (${verifiedVoice.persona ?: verifiedVoice.gender ?: "verified"})"
        )

        val descriptor = listOfNotNull(verifiedVoice.gender, verifiedVoice.persona)
            .joinToString(" · ")
            .ifBlank { verifiedVoice.description.orEmpty() }

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Switched voice to ${verifiedVoice.label}",
                receiptTitle = "Voice Switched to ${verifiedVoice.label}",
                receiptSubtitle = descriptor.ifBlank { "Active for Live Voice sessions" },
                undoActionType = UndoActionType.RESTORE_VOICE,
                undoPayload = previousVoice
            )
        )

        // Hot-swap active Live Voice WebSocket session if currently speaking/listening
        onActiveVoiceSwitched?.invoke(verifiedVoice.id)

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("previous_voice", JsonPrimitive(previousVoice))
            put("active_voice", JsonPrimitive(verifiedVoice.id))
            put("description", JsonPrimitive(verifiedVoice.description ?: descriptor))
        }
    }

    private suspend fun handleLookupContactHistory(args: JsonObject): JsonObject {
        val query = args.requireString("person_or_handle")
        val packageName = args.optString("package_name") ?: "com.google.android.apps.messaging"
        val dossier = contextEngine.buildDossier(
            packageName = packageName,
            senderRaw = query,
            titleRaw = query,
            shortcutId = null
        )
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("dossier", JsonPrimitive(dossier.formatForPrompt()))
        }
    }

    private suspend fun handleQuickReply(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val notificationId = args["notification_id"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("notification_id is required.")
        val replyText = args.requireString("reply_text")

        actionIntentCache.sendQuickReplyOrThrow(appContext, notificationId, replyText)
        val record = dao.getNotificationById(notificationId)

        traceLogger.logDecision(
            subsystem = "TOOLS",
            eventType = "QUICK_REPLY_SENT",
            reasoningRationale = rationale,
            decisionPayload = "id=$notificationId to=${record?.sender} text=$replyText"
        )

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Replied: $replyText",
                receiptTitle = "Reply Sent via ${record?.appName ?: "Notification"}",
                receiptSubtitle = "\"$replyText\""
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("notification_id", JsonPrimitive(notificationId))
            put("sent_text", JsonPrimitive(replyText))
        }
    }

    private suspend fun handleConfigureGatekeeper(
        args: JsonObject,
        sessionId: String,
        modality: TurnModality
    ): JsonObject {
        val rationale = args.requireString("rationale")
        val enabled = args["gatekeeper_enabled"]?.jsonPrimitive?.booleanOrNull
            ?: settingsStore.isGatekeeperEnabled()
        val sleepStrictCalls = args["sleep_starred_calls_only"]?.jsonPrimitive?.booleanOrNull
            ?: settingsStore.isSleepStrictCalls()

        settingsStore.setGatekeeperEnabled(enabled)
        settingsStore.setSleepStrictCalls(sleepStrictCalls)
        val synced = zenModeController.syncGatekeeperRule(enabled, sleepStrictCalls)

        val activeDigest = dao.getActiveDigestItems()
        channelsManager.refreshLiveSummaryNotification(
            activeDigestItems = activeDigest,
            gatekeeperEnabled = enabled,
            degradedError = settingsStore.state.value.lastDegradedError
        )
        AgentVoiceWidgetProvider.requestRefresh(appContext)

        traceLogger.logDecision(
            subsystem = "ZEN_POLICY",
            eventType = "CONFIGURE_GATEKEEPER",
            reasoningRationale = rationale,
            decisionPayload = "enabled=$enabled sleepStrictCalls=$sleepStrictCalls syncedWithOs=$synced"
        )

        dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = modality,
                role = TurnRole.ACTION_RECEIPT,
                text = "Gatekeeper updated",
                receiptTitle = if (enabled) "Gatekeeper Active" else "Gatekeeper Paused",
                receiptSubtitle = if (sleepStrictCalls) "Calls: Starred & Repeat Callers" else "Calls: Exempt (Pixel Call Screen)"
            )
        )

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("gatekeeper_enabled", JsonPrimitive(enabled))
            put("sleep_starred_calls_only", JsonPrimitive(sleepStrictCalls))
            put("zen_policy_synced", JsonPrimitive(synced))
        }
    }

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.requireString(key: String): String =
        optString(key) ?: throw IllegalArgumentException("Missing required string parameter '$key'.")

    private fun buildToolArray(forLiveWebSocket: Boolean): JsonArray {
        fun decl(
            name: String,
            description: String,
            properties: JsonObject,
            required: List<String>
        ): JsonObject = buildJsonObject {
            if (!forLiveWebSocket) {
                put("type", JsonPrimitive("function"))
            }
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            if (forLiveWebSocket) {
                put("behavior", JsonPrimitive("NON_BLOCKING"))
            }
            put(
                "parameters",
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", properties)
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
            )
        }

        val rationaleProp = buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Pre-decision epistemic reasoning explaining why this tool action is appropriate."))
        }

        val categoryEnumProp = buildJsonObject {
            put("type", JsonPrimitive("string"))
            put(
                "enum",
                buildJsonArray {
                    add(JsonPrimitive("STANDING_DIRECTIVE"))
                    add(JsonPrimitive("RELATIONSHIP"))
                    add(JsonPrimitive("PREFERENCE"))
                    add(JsonPrimitive("ACTIVE_EXPECTATION"))
                }
            )
        }

        return buildJsonArray {
            add(
                decl(
                    name = "list_memories",
                    description = "Lists all currently active rules, standing directives, relationships, preferences, and expectations with their exact numeric memory_id. Call this before editing or deleting rules if you want to verify current memory_id values.",
                    properties = buildJsonObject {
                        put(
                            "category",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("ALL"))
                                        add(JsonPrimitive("STANDING_DIRECTIVE"))
                                        add(JsonPrimitive("RELATIONSHIP"))
                                        add(JsonPrimitive("PREFERENCE"))
                                        add(JsonPrimitive("ACTIVE_EXPECTATION"))
                                    }
                                )
                                put("description", JsonPrimitive("Optional category filter; omit or pass ALL to return every active rule."))
                            }
                        )
                    },
                    required = emptyList()
                )
            )

            add(
                decl(
                    name = "remember_preference_or_fact",
                    description = "Creates a brand-new durable rule, notification preference, relationship fact, or active expectation ONLY when no existing rule covers it. Do NOT call this to modify, narrow, fix, or add exceptions to an existing rule—call update_memory (to edit an existing rule in place) or delete_memory (to remove a rule) instead.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put("category", categoryEnumProp)
                        put(
                            "memory_statement",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "description",
                                    JsonPrimitive("Self-contained factual or policy statement preserving exact sender/app/topic scope.")
                                )
                            }
                        )
                    },
                    required = listOf("rationale", "category", "memory_statement")
                )
            )

            add(
                decl(
                    name = "update_memory",
                    description = "Edits an existing rule, standing directive, preference, or relationship memory IN PLACE by its numeric memory_id and re-indexes it immediately. Always use this tool when refining, narrowing, clarifying, or correcting an existing rule instead of creating a second conflicting rule.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put(
                            "memory_id",
                            buildJsonObject {
                                put("type", JsonPrimitive("integer"))
                                put("description", JsonPrimitive("Numeric memory_id of the existing rule to edit (shown in Active Rules & Memories or returned by list_memories)."))
                            }
                        )
                        put(
                            "updated_memory_statement",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put("description", JsonPrimitive("Complete revised rule or memory statement with exact sender/app/topic scope."))
                            }
                        )
                        put("category", categoryEnumProp)
                    },
                    required = listOf("rationale", "memory_id", "updated_memory_statement")
                )
            )

            add(
                decl(
                    name = "delete_memory",
                    description = "Permanently deletes/deactivates an existing rule, directive, preference, or memory entry by its numeric memory_id. Always use this tool when the user asks to remove, forget, cancel, or stop using a rule, or to clean up a redundant/superseded rule.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put(
                            "memory_id",
                            buildJsonObject {
                                put("type", JsonPrimitive("integer"))
                                put("description", JsonPrimitive("Numeric memory_id of the rule to delete (shown in Active Rules & Memories or returned by list_memories)."))
                            }
                        )
                    },
                    required = listOf("rationale", "memory_id")
                )
            )

            add(
                decl(
                    name = "query_notifications",
                    description = "Searches recent URGENT, SUMMARY, or SPAM notifications and returns their contents plus the AI's stored reasoning_rationale.",
                    properties = buildJsonObject {
                        put("search_query", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put(
                            "tier",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("ALL"))
                                        add(JsonPrimitive("URGENT"))
                                        add(JsonPrimitive("SUMMARY"))
                                        add(JsonPrimitive("SPAM"))
                                    }
                                )
                            }
                        )
                        put("limit", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    },
                    required = emptyList()
                )
            )

            add(
                decl(
                    name = "reclassify_or_rescue_notification",
                    description = "Moves a notification to URGENT, SUMMARY, or SPAM (e.g. rescuing a false positive from Spam). Does NOT create or edit rules by itself: if an existing rule also needs to be edited or deleted (or a brand-new rule created), call update_memory, delete_memory, or remember_preference_or_fact explicitly.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put("notification_id", buildJsonObject { put("type", JsonPrimitive("integer")) })
                        put(
                            "new_tier",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("URGENT"))
                                        add(JsonPrimitive("SUMMARY"))
                                        add(JsonPrimitive("SPAM"))
                                    }
                                )
                            }
                        )
                    },
                    required = listOf("rationale", "notification_id", "new_tier")
                )
            )

            add(
                decl(
                    name = "clear_digest_summary",
                    description = "Marks all currently held items in the Live Summary notification as reviewed/cleared.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                    },
                    required = listOf("rationale")
                )
            )

            add(
                decl(
                    name = "search_available_voices",
                    description = "Lists and filters available Gemini voices (including gender, accent, pitch, persona, and vocal description) so you can choose a voice matching the user's request before calling switch_agent_voice.",
                    properties = buildJsonObject {
                        put("gender", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("female, male, or neutral")) })
                        put("accent", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("pitch", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("low, medium, or high")) })
                        put("search", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Free-text tone/persona search such as warm, breezy, upbeat, gentle, british")) })
                    },
                    required = emptyList()
                )
            )

            add(
                decl(
                    name = "switch_agent_voice",
                    description = "Reconfigures the assistant's spoken voice to a verified voice_id from search_available_voices and hot-swaps any active Live Voice session immediately.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put("voice_id", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Exact voice id returned by search_available_voices (e.g. Aoede, Sulafat, Kore, Zephyr, Puck, Charon).")) })
                    },
                    required = listOf("rationale", "voice_id")
                )
            )

            add(
                decl(
                    name = "lookup_contact_and_history",
                    description = "Looks up a contact's relationship details, recent SMS sent/received history, recent phone calls, nearby calendar events, and observed in-app chat threads.",
                    properties = buildJsonObject {
                        put("person_or_handle", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("person_or_handle")
                )
            )

            add(
                decl(
                    name = "send_notification_quick_reply",
                    description = "Sends a direct reply to a chat notification using its cached Android RemoteInput action.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put("notification_id", buildJsonObject { put("type", JsonPrimitive("integer")) })
                        put("reply_text", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("rationale", "notification_id", "reply_text")
                )
            )

            add(
                decl(
                    name = "configure_gatekeeper_mode",
                    description = "Enables/pauses the Do Not Disturb Gatekeeper or toggles strict sleep call filtering (starred & repeat callers only vs all calls).",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put("gatekeeper_enabled", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                        put("sleep_starred_calls_only", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    },
                    required = listOf("rationale")
                )
            )

            add(
                decl(
                    name = "set_private_mode",
                    description = "Enters or exits Private Mode when the user asks to go into private/incognito/off-the-record mode (enabled = true) or asks to exit/leave/end private mode (enabled = false). Exiting permanently deletes everything from the entry request through the exit request.",
                    properties = buildJsonObject {
                        put("rationale", rationaleProp)
                        put(
                            "enabled",
                            buildJsonObject {
                                put("type", JsonPrimitive("boolean"))
                                put(
                                    "description",
                                    JsonPrimitive("Set true to enter Private Mode; set false to exit Private Mode and permanently erase the private session.")
                                )
                            }
                        )
                    },
                    required = listOf("rationale", "enabled")
                )
            )
        }
    }
}
