package com.personal.pixelagent.agent

import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.TurnModality
import com.personal.pixelagent.data.local.TurnRole
import com.personal.pixelagent.data.memory.MemoryRepository
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.network.GeminiInteractionsClient
import com.personal.pixelagent.observability.TraceLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatTurnResult(
    val replyText: String,
    val exitedPrivateMode: Boolean
)

class TextChatOrchestrator(
    private val dao: AgentDao,
    private val settingsStore: SecureSettingsStore,
    private val memoryRepository: MemoryRepository,
    private val interactionsClient: GeminiInteractionsClient,
    private val toolExecutor: AgentToolExecutor,
    private val traceLogger: TraceLogger
) {
    private val sessionId = UUID.randomUUID().toString()
    private val timeFmt = SimpleDateFormat("EEEE, MMM d, yyyy h:mm a z", Locale.US)

    suspend fun sendUserMessage(rawMessage: String): ChatTurnResult {
        val message = rawMessage.trim()
        require(message.isNotEmpty()) { "Message cannot be empty." }

        // Clear any stale turn-scoped exit marker
        toolExecutor.consumeLastExitedPrivateBounds()

        val userTurnId = dao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = TurnModality.TEXT,
                role = TurnRole.USER,
                text = message
            )
        )

        val priorTurns = dao.getRecentDialogueTurns(limit = 14)
            .filter { it.id != userTurnId }
            .reversed()
            .map { turn ->
                when (turn.role) {
                    TurnRole.USER -> GeminiInteractionsClient.buildUserTextStep(turn.text)
                    else -> GeminiInteractionsClient.buildModelTextStep(turn.text)
                }
            }

        val systemInstruction = buildAssistantSystemInstruction(
            dao = dao,
            settingsStore = settingsStore,
            memoryRepository = memoryRepository,
            queryHint = message,
            formattedNow = timeFmt.format(Date())
        )

        try {
            val replyText = interactionsClient.runToolConversationTurn(
                model = settingsStore.getChatModel(),
                systemInstruction = systemInstruction,
                priorDialogueSteps = priorTurns,
                userMessage = message,
                tools = toolExecutor.buildInteractionsToolsJson(),
                toolHandler = { fc ->
                    toolExecutor.executeTool(
                        name = fc.name,
                        args = fc.arguments,
                        sessionId = sessionId,
                        modality = TurnModality.TEXT,
                        triggeringTurnId = userTurnId
                    )
                }
            ).trim().ifBlank { "Done." }

            val exitedBounds = toolExecutor.consumeLastExitedPrivateBounds()
            if (exitedBounds != null) {
                // Permanently erase everything from the entry request through this exit request (and do not persist the exit reply)
                toolExecutor.purgePrivateBounds(exitedBounds)
                return ChatTurnResult(replyText = replyText, exitedPrivateMode = true)
            }

            dao.insertConversationTurn(
                ConversationTurnEntity(
                    sessionId = sessionId,
                    modality = TurnModality.TEXT,
                    role = TurnRole.ASSISTANT,
                    text = replyText
                )
            )
            return ChatTurnResult(replyText = replyText, exitedPrivateMode = false)
        } catch (t: Throwable) {
            val exitedBoundsOnErr = toolExecutor.consumeLastExitedPrivateBounds()
            if (exitedBoundsOnErr != null) {
                toolExecutor.purgePrivateBounds(exitedBoundsOnErr)
            } else if (!settingsStore.isPrivateModeActive()) {
                traceLogger.logError(
                    subsystem = "TEXT_CHAT",
                    eventType = "CHAT_TURN_ERROR",
                    contextMessage = "Failed to process text chat turn",
                    throwable = t
                )
            }
            throw t
        }
    }

    companion object {
        suspend fun buildAssistantSystemInstruction(
            dao: AgentDao,
            settingsStore: SecureSettingsStore,
            memoryRepository: MemoryRepository,
            queryHint: String,
            formattedNow: String
        ): String {
            val activeRules = runCatching { memoryRepository.listActiveMemories() }
                .getOrDefault(emptyList())
            val memories = if (activeRules.isEmpty()) {
                "Currently 0 active rules or custom memories."
            } else {
                activeRules.joinToString("\n") { m ->
                    "- [memory_id=${m.id} | category=${m.category.name}] ${m.content}"
                }
            }

            val digestItems = dao.getActiveDigestItems()
            val digestPreview = if (digestItems.isEmpty()) {
                "Currently 0 held items in the Live Summary."
            } else {
                digestItems.take(10).joinToString("\n") {
                    "- [id=${it.id}] ${it.appName} (${it.sender}): ${it.title} — ${it.body.take(120)} | Rationale: ${it.reasoningRationale}"
                }
            }

            val isPrivateMode = settingsStore.isPrivateModeActive()

            return """
You are the user's personal AI Assistant living directly on their Pixel 7 Pro.
Current Local Time: $formattedNow
Current Spoken Voice Persona: ${settingsStore.getVoiceName()}
Gatekeeper Enabled: ${settingsStore.isGatekeeperEnabled()} | Sleep Starred-Calls-Only: ${settingsStore.isSleepStrictCalls()}
Notification Triage Model: ${settingsStore.getTriageModel()} (thinking_level=${settingsStore.getTriageThinkingLevel()})
Private Mode Active: $isPrivateMode

## Core Responsibilities
1. **Autonomous Rule Lifecycle — Inspect, Create, Edit & Delete (`list_memories`, `update_memory`, `delete_memory`, `remember_preference_or_fact`)**:
   - You directly manage all notification rules and user memories via tool calls—never ask the user to edit or delete rules manually.
   - **Always Check Existing Rules First**: Review `## Active Rules & Memories` below (or call `list_memories` to fetch the latest live list, especially mid-conversation after prior edits) before touching rules.
   - **Edit Existing Rules In Place (`update_memory`)**: Whenever the user refines, narrows, clarifies, or changes an existing rule—or when rescuing/discussing a misclassified notification caused by an existing rule—call `update_memory` with that rule's `memory_id` and revised `updated_memory_statement`. **NEVER** create a second counter-rule or exception rule on top of an existing rule when editing the existing rule in place resolves it cleanly.
   - **Delete Rules (`delete_memory`)**: Whenever the user asks to delete, remove, forget, or stop applying a rule—or when cleaning up a duplicate or superseded rule—call `delete_memory` with its `memory_id`.
   - **Create New Rules (`remember_preference_or_fact`)**: Only call `remember_preference_or_fact` when adding a genuinely new rule, preference, relationship, or expectation that is not already covered by an existing `memory_id`, preserving exact sender/app/topic scope. (Exception: While `Private Mode Active: true`, do NOT save memories unless explicitly requested, as any memory created during Private Mode is erased on exit.)
   - When confirming a rule edit or deletion, briefly mention the rule (`#<memory_id>`) and what changed.
2. **Private Mode (`set_private_mode`)**: Whenever the user asks in natural language (via text or voice) to enter private mode, incognito mode, or speak off the record, call `set_private_mode` with `enabled = true` and clearly confirm that Private Mode is active and everything from their request to enter through their request to exit will be permanently deleted upon exit. Whenever the user asks to exit, leave, or end private mode, call `set_private_mode` with `enabled = false` and briefly confirm that Private Mode has ended and the session has been permanently deleted.
3. **Full Transparency & Rescue**: You have access to `query_notifications` (including your own `reasoning_rationale` for every URGENT, SUMMARY, and SPAM decision), `reclassify_or_rescue_notification`, and `lookup_contact_and_history` (Contacts, SMS history, Call Logs, Calendar, and observed 3rd-party chat threads). Remember that `reclassify_or_rescue_notification` only moves the notification itself—if an existing rule needs to be edited or deleted as well, also call `update_memory` or `delete_memory`.
4. **Dynamic Self-Configuration of Voice**: If the user asks to change your voice or asks for a type/style of voice, first call `search_available_voices` to inspect real options in Gemini's voice catalog, pick the best match for their description, and call `switch_agent_voice` with that exact `voice_id`.
5. **Concise, Calm Communication**: Keep answers natural, direct, and concise—never cluttered.

## Active Held Summary Items
$digestPreview

## Active Rules & Memories
$memories
            """.trimIndent()
        }
    }
}
