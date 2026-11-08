package com.personal.pixelagent

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteQuery
import com.personal.pixelagent.agent.AgentToolExecutor
import com.personal.pixelagent.agent.TextChatOrchestrator
import com.personal.pixelagent.context.CommunicationContextEngine
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.MemoryEntity
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.ScreenThreadSnapshotEntity
import com.personal.pixelagent.data.local.SenderEngagementEntity
import com.personal.pixelagent.data.local.TraceLogEntity
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
import com.personal.pixelagent.network.EmbeddingProvider
import com.personal.pixelagent.network.GeminiInteractionsClient
import com.personal.pixelagent.network.GeminiVoicesClient
import com.personal.pixelagent.observability.TraceLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivateModeTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var fakeDao: InMemoryAgentDao
    private lateinit var settingsStore: SecureSettingsStore
    private lateinit var toolExecutor: AgentToolExecutor
    private lateinit var orchestrator: TextChatOrchestrator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()

        fakeDao = InMemoryAgentDao()
        settingsStore = SecureSettingsStore(InMemorySharedPreferences()).apply {
            saveApiKey("test-api-key")
        }

        val stubContext = ContextWrapper(null)
        val traceLogger = TraceLogger(fakeDao)
        val fakeEmbedder = EmbeddingProvider { FloatArray(8) { 0.25f } }
        val memoryRepo = MemoryRepository(fakeDao, fakeEmbedder, traceLogger)
        val interactionsClient = GeminiInteractionsClient(
            httpClient = httpClient,
            apiKeyProvider = { settingsStore.getApiKeyOrThrow() },
            baseUrl = server.url("/")
        )
        val voicesClient = GeminiVoicesClient(
            httpClient = httpClient,
            apiKeyProvider = { settingsStore.getApiKeyOrThrow() },
            baseUrl = server.url("/")
        )

        toolExecutor = AgentToolExecutor(
            appContext = stubContext,
            dao = fakeDao,
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            voicesClient = voicesClient,
            contextEngine = CommunicationContextEngine(stubContext, fakeDao),
            actionIntentCache = ActionIntentCache(fakeDao),
            channelsManager = NotificationChannelsManager(stubContext),
            zenModeController = ZenModeController(stubContext, settingsStore),
            traceLogger = traceLogger
        )

        orchestrator = TextChatOrchestrator(
            dao = fakeDao,
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            interactionsClient = interactionsClient,
            toolExecutor = toolExecutor,
            traceLogger = traceLogger
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `set_private_mode places rationale first in schema and marks NON_BLOCKING for Live WebSocket`() {
        val textTool = toolExecutor.buildInteractionsToolsJson()
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content == "set_private_mode" }
        assertNotNull("Expected set_private_mode in Interactions tools", textTool)

        val propKeys = textTool!!["parameters"]!!.jsonObject["properties"]!!.jsonObject.keys.toList()
        assertEquals("rationale", propKeys.first())
        assertTrue(propKeys.contains("enabled"))

        val requiredKeys = textTool["parameters"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("rationale", "enabled"), requiredKeys)

        val liveTool = toolExecutor.buildLiveNonBlockingDeclarationsJson()
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content == "set_private_mode" }
        assertNotNull("Expected set_private_mode in Live Voice tools", liveTool)
        assertEquals("NON_BLOCKING", liveTool!!["behavior"]?.jsonPrimitive?.content)
    }

    @Test
    fun `text conversation entering and exiting private mode permanently deletes entry request through exit request`() = runTest {
        // 1. Seed pre-private state that MUST survive Private Mode exit
        val preUserTurnId = fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "s1",
                modality = TurnModality.TEXT,
                role = TurnRole.USER,
                text = "Normal question from earlier morning"
            )
        )
        val preAssistantTurnId = fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "s1",
                modality = TurnModality.TEXT,
                role = TurnRole.ASSISTANT,
                text = "Normal answer from earlier morning"
            )
        )
        val durableMemoryId = fakeDao.insertMemory(
            MemoryEntity(
                category = MemoryCategory.STANDING_DIRECTIVE,
                content = "Always allow urgent family alerts",
                sourceSummary = "Baseline user directive",
                embeddingBlob = null
            )
        )
        fakeDao.insertTraceLog(
            TraceLogEntity(
                subsystem = "MEMORY",
                eventType = "SAVE_MEMORY",
                reasoningRationale = "Pre-private memory audit",
                decisionPayload = "id=$durableMemoryId",
                errorStack = null
            )
        )

        assertFalse(settingsStore.isPrivateModeActive())

        // 2. Turn A: User asks to enter Private Mode -> Model calls set_private_mode(enabled = true)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_enter_1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "fc_pm_on",
                      "name": "set_private_mode",
                      "arguments": {
                        "rationale": "User requested entering private mode.",
                        "enabled": true
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_enter_2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{"type": "text", "text": "Private Mode is active. Everything from your entry request until you exit will be permanently deleted."}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val enterResult = orchestrator.sendUserMessage("Please go into private mode")
        assertFalse(enterResult.exitedPrivateMode)
        assertTrue(settingsStore.isPrivateModeActive())
        assertTrue(fakeDao.turnsSnapshot().size >= 5) // 2 pre-turns + entry user + receipt + assistant reply

        // 3. Turn B: Private conversation + temporary memory + background TRIAGE trace
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_mid_1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "fc_temp_mem",
                      "name": "remember_preference_or_fact",
                      "arguments": {
                        "rationale": "User asked to note something temporarily inside private session.",
                        "category": "ACTIVE_EXPECTATION",
                        "memory_statement": "Confidential note during private session"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_mid_2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{"type": "text", "text": "Noted temporarily for this private session."}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        orchestrator.sendUserMessage("Confidential discussion topic inside private mode")
        assertEquals(2, fakeDao.getActiveMemories().size)

        // Simulate an unrelated background notification triage trace arriving while Private Mode is active
        fakeDao.insertTraceLog(
            TraceLogEntity(
                subsystem = "TRIAGE",
                eventType = "NOTIFICATION_SUMMARY",
                reasoningRationale = "Routine background app update.",
                decisionPayload = "tier=SUMMARY",
                errorStack = null
            )
        )

        // 4. Turn C: User asks to exit Private Mode -> Model calls set_private_mode(enabled = false)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_exit_1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "fc_pm_off",
                      "name": "set_private_mode",
                      "arguments": {
                        "rationale": "User requested exiting private mode and erasing private history.",
                        "enabled": false
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "pm_exit_2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{"type": "text", "text": "Private Mode exited and conversation deleted."}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val exitResult = orchestrator.sendUserMessage("Exit private mode now")
        assertTrue(exitResult.exitedPrivateMode)
        assertFalse(settingsStore.isPrivateModeActive())
        assertTrue(fakeDao.walCheckpointCount >= 1)

        // Verify ONLY pre-private turns remain (entry request, private turns, exit request, and exit reply are ALL gone)
        val remainingTurns = fakeDao.turnsSnapshot()
        assertEquals(2, remainingTurns.size)
        assertEquals(listOf(preUserTurnId, preAssistantTurnId), remainingTurns.map { it.id })
        assertTrue(remainingTurns.none { it.text.contains("private", ignoreCase = true) })
        assertTrue(remainingTurns.none { it.text.contains("Confidential", ignoreCase = true) })

        // Verify temporary memory created during Private Mode was permanently deleted while pre-private memory survived
        val remainingMemories = fakeDao.getActiveMemories()
        assertEquals(1, remainingMemories.size)
        assertEquals(durableMemoryId, remainingMemories.single().id)

        // Verify private non-triage traces were wiped while pre-private trace and background TRIAGE trace survived
        val remainingTraces = fakeDao.tracesSnapshot()
        assertEquals(2, remainingTraces.size)
        assertTrue(remainingTraces.any { it.eventType == "SAVE_MEMORY" && it.decisionPayload == "id=$durableMemoryId" })
        assertTrue(remainingTraces.any { it.subsystem == "TRIAGE" })
        assertTrue(remainingTraces.none { it.subsystem == "PRIVACY" })
    }

    @Test
    fun `voice modality private mode tracks spoken entry turn id and notifies live voice callback`() = runTest {
        val preVoiceTurn = fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "v1",
                modality = TurnModality.VOICE,
                role = TurnRole.USER,
                text = "What is on my summary?"
            )
        )

        val observedCallbacks = mutableListOf<Pair<Boolean, PrivateSessionBounds?>>()
        toolExecutor.onPrivateModeChanged = { enabled, bounds ->
            observedCallbacks.add(enabled to bounds)
        }

        // User speaks entry command -> persisted right before ToolCall in LiveVoiceForegroundService
        val spokenEntryId = fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "v1",
                modality = TurnModality.VOICE,
                role = TurnRole.USER,
                text = "Switch into private mode"
            )
        )

        toolExecutor.executeTool(
            name = "set_private_mode",
            args = buildJsonObject {
                put("rationale", JsonPrimitive("Spoken request to activate private mode."))
                put("enabled", JsonPrimitive(true))
            },
            sessionId = "v1",
            modality = TurnModality.VOICE,
            triggeringTurnId = spokenEntryId
        )

        assertTrue(settingsStore.isPrivateModeActive())
        assertEquals(spokenEntryId, settingsStore.getPrivateSessionBounds()?.startTurnId)

        fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "v1",
                modality = TurnModality.VOICE,
                role = TurnRole.ASSISTANT,
                text = "Private mode is on."
            )
        )
        val spokenExitId = fakeDao.insertConversationTurn(
            ConversationTurnEntity(
                sessionId = "v1",
                modality = TurnModality.VOICE,
                role = TurnRole.USER,
                text = "Leave private mode"
            )
        )

        toolExecutor.executeTool(
            name = "set_private_mode",
            args = buildJsonObject {
                put("rationale", JsonPrimitive("Spoken request to leave private mode."))
                put("enabled", JsonPrimitive(false))
            },
            sessionId = "v1",
            modality = TurnModality.VOICE,
            triggeringTurnId = spokenExitId
        )

        assertFalse(settingsStore.isPrivateModeActive())
        assertEquals(2, observedCallbacks.size)
        assertEquals(true, observedCallbacks[0].first)
        assertEquals(false, observedCallbacks[1].first)
        assertEquals(spokenEntryId, observedCallbacks[1].second?.startTurnId)

        // Only preVoiceTurn survives; spokenEntryId through spokenExitId are permanently deleted
        val remaining = fakeDao.turnsSnapshot()
        assertEquals(1, remaining.size)
        assertEquals(preVoiceTurn, remaining.single().id)
    }

    @Test
    fun `rule management tools support list create update and delete with rationale-first schemas and undo receipts`() = runTest {
        val liveDecls = toolExecutor.buildLiveNonBlockingDeclarationsJson()
        for (mutatingToolName in listOf("remember_preference_or_fact", "update_memory", "delete_memory")) {
            val decl = liveDecls.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == mutatingToolName }
            assertEquals("NON_BLOCKING", decl["behavior"]?.jsonPrimitive?.content)
            val params = decl["parameters"]!!.jsonObject
            val propKeys = params["properties"]!!.jsonObject.keys.toList()
            val reqKeys = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("Expected rationale first in $mutatingToolName properties", "rationale", propKeys.first())
            assertEquals("Expected rationale first in $mutatingToolName required", "rationale", reqKeys.first())
        }

        val listDecl = liveDecls.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == "list_memories" }
        assertEquals("NON_BLOCKING", listDecl["behavior"]?.jsonPrimitive?.content)

        // 1. Create a scoped rule
        val createRes = toolExecutor.executeTool(
            name = "remember_preference_or_fact",
            args = buildJsonObject {
                put("rationale", JsonPrimitive("User specified a sender-scoped notification rule."))
                put("category", JsonPrimitive("STANDING_DIRECTIVE"))
                put("memory_statement", JsonPrimitive("Suppress automated status updates from Sender Alpha only."))
            },
            sessionId = "s-rules",
            modality = TurnModality.TEXT
        )
        assertEquals("true", createRes["ok"]!!.jsonPrimitive.content)
        val createdId = createRes["memory_id"]!!.jsonPrimitive.content.toLong()

        // 2. List active rules and verify ID + exact statement are returned
        val listRes = toolExecutor.executeTool(
            name = "list_memories",
            args = buildJsonObject {},
            sessionId = "s-rules",
            modality = TurnModality.TEXT
        )
        assertEquals("1", listRes["count"]!!.jsonPrimitive.content)
        val listedRule = listRes["memories"]!!.jsonArray.single().jsonObject
        assertEquals(createdId.toString(), listedRule["id"]!!.jsonPrimitive.content)
        assertTrue(listedRule["statement"]!!.jsonPrimitive.content.contains("Sender Alpha"))

        // 3. Edit the existing rule in place (without creating a conflicting duplicate)
        val updateRes = toolExecutor.executeTool(
            name = "update_memory",
            args = buildJsonObject {
                put("rationale", JsonPrimitive("User refined the existing rule scope rather than adding a second rule."))
                put("memory_id", JsonPrimitive(createdId))
                put("category", JsonPrimitive("STANDING_DIRECTIVE"))
                put("updated_memory_statement", JsonPrimitive("Send automated status updates from Sender Alpha to the quiet summary."))
            },
            sessionId = "s-rules",
            modality = TurnModality.TEXT
        )
        assertEquals("true", updateRes["ok"]!!.jsonPrimitive.content)
        val activeAfterUpdate = fakeDao.getActiveMemories()
        assertEquals(1, activeAfterUpdate.size)
        assertEquals(createdId, activeAfterUpdate.single().id)
        assertEquals(
            "Send automated status updates from Sender Alpha to the quiet summary.",
            activeAfterUpdate.single().content
        )

        val updateReceipt = fakeDao.turnsSnapshot().last { it.role == TurnRole.ACTION_RECEIPT }
        assertEquals(UndoActionType.RESTORE_MEMORY_CONTENT, updateReceipt.undoActionType)
        assertTrue(updateReceipt.undoPayload!!.contains("Suppress automated status updates from Sender Alpha only."))

        // 4. Delete the rule by ID and verify deactivation + undo receipt
        val deleteRes = toolExecutor.executeTool(
            name = "delete_memory",
            args = buildJsonObject {
                put("rationale", JsonPrimitive("User requested removing this standing rule."))
                put("memory_id", JsonPrimitive(createdId))
            },
            sessionId = "s-rules",
            modality = TurnModality.TEXT
        )
        assertEquals("true", deleteRes["ok"]!!.jsonPrimitive.content)
        assertEquals(createdId.toString(), deleteRes["deleted_memory_id"]!!.jsonPrimitive.content)
        assertTrue(fakeDao.getActiveMemories().isEmpty())
        val deactivatedEntity = fakeDao.getMemoryById(createdId)
        assertNotNull(deactivatedEntity)
        assertFalse(deactivatedEntity!!.active)

        val deleteReceipt = fakeDao.turnsSnapshot().last { it.role == TurnRole.ACTION_RECEIPT }
        assertEquals(UndoActionType.REACTIVATE_MEMORY, deleteReceipt.undoActionType)
        assertEquals(createdId.toString(), deleteReceipt.undoPayload)

        // 5. Verify reclassify_or_rescue_notification cannot silently spawn duplicate rules
        val reclassifyDecl = liveDecls.map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.content == "reclassify_or_rescue_notification" }
        val reclassifyProps = reclassifyDecl["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse(reclassifyProps.containsKey("learned_preference_statement"))
    }

    @Test
    fun `text chat orchestrator exposes active memory_ids and autonomously edits and deletes rules`() = runTest {
        val ruleId = fakeDao.insertMemory(
            MemoryEntity(
                category = MemoryCategory.STANDING_DIRECTIVE,
                content = "Hold automated calendar digests in summary.",
                sourceSummary = "Seeded rule"
            )
        )

        // Turn 1: User asks to refine the rule -> Agent calls update_memory on ruleId
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "inter_edit_1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call_upd_1",
                      "name": "update_memory",
                      "arguments": {
                        "rationale": "Editing existing rule #$ruleId in place rather than creating a second rule.",
                        "memory_id": $ruleId,
                        "category": "STANDING_DIRECTIVE",
                        "updated_memory_statement": "Hold automated calendar digests from Workspace Calendar only in summary."
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "inter_edit_2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{"type": "text", "text": "Updated rule #$ruleId so it only applies to Workspace Calendar."}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val editTurn = orchestrator.sendUserMessage("Narrow that calendar digest rule to Workspace Calendar only")
        assertTrue(editTurn.replyText.contains("#$ruleId"))

        val firstReqJson = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val sysInstr = firstReqJson["system_instruction"]!!.jsonPrimitive.content
        assertTrue(sysInstr.contains("[memory_id=$ruleId | category=STANDING_DIRECTIVE]"))
        server.takeRequest() // consume tool-followup request

        assertEquals(1, fakeDao.getActiveMemories().size)
        assertEquals(
            "Hold automated calendar digests from Workspace Calendar only in summary.",
            fakeDao.getActiveMemories().single().content
        )

        // Turn 2: User asks to delete the rule -> Agent calls delete_memory on ruleId
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "inter_del_1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call_del_1",
                      "name": "delete_memory",
                      "arguments": {
                        "rationale": "User asked to delete rule #$ruleId.",
                        "memory_id": $ruleId
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "inter_del_2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{"type": "text", "text": "Deleted rule #$ruleId."}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val delTurn = orchestrator.sendUserMessage("Delete that calendar rule")
        assertTrue(delTurn.replyText.contains("Deleted rule #$ruleId"))
        assertTrue(fakeDao.getActiveMemories().isEmpty())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class EditorImpl : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { staged[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                staged.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    private class InMemoryAgentDao : AgentDao {
        private var nextMemoryId = 1L
        private var nextTurnId = 1L
        private var nextTraceId = 1L
        private var nextNotifId = 1L

        private val memories = ArrayList<MemoryEntity>()
        private val turns = ArrayList<ConversationTurnEntity>()
        private val traces = ArrayList<TraceLogEntity>()
        private val notifications = ArrayList<NotificationRecordEntity>()

        var walCheckpointCount = 0

        fun turnsSnapshot(): List<ConversationTurnEntity> = turns.toList()
        fun tracesSnapshot(): List<TraceLogEntity> = traces.toList()

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = if (memory.id == 0L) nextMemoryId++ else memory.id
            memories.removeAll { it.id == id }
            memories.add(memory.copy(id = id))
            return id
        }

        override fun observeActiveMemories(): Flow<List<MemoryEntity>> =
            MutableStateFlow(memories.filter { it.active })

        override suspend fun getActiveMemories(): List<MemoryEntity> =
            memories.filter { it.active }.sortedByDescending { it.updatedAt }

        override suspend fun getMemoryById(memoryId: Long): MemoryEntity? =
            memories.firstOrNull { it.id == memoryId }

        override suspend fun updateMemoryById(
            memoryId: Long,
            category: MemoryCategory,
            content: String,
            sourceSummary: String,
            embeddingBlob: ByteArray?,
            now: Long
        ) {
            val idx = memories.indexOfFirst { it.id == memoryId }
            if (idx >= 0) {
                memories[idx] = memories[idx].copy(
                    category = category,
                    content = content,
                    sourceSummary = sourceSummary,
                    embeddingBlob = embeddingBlob,
                    updatedAt = now
                )
            }
        }

        override suspend fun deactivateMemory(memoryId: Long, now: Long) {
            val idx = memories.indexOfFirst { it.id == memoryId }
            if (idx >= 0) memories[idx] = memories[idx].copy(active = false, updatedAt = now)
        }

        override suspend fun reactivateMemory(memoryId: Long, now: Long) {
            val idx = memories.indexOfFirst { it.id == memoryId }
            if (idx >= 0) memories[idx] = memories[idx].copy(active = true, updatedAt = now)
        }

        override suspend fun insertNotificationRecord(record: NotificationRecordEntity): Long {
            val id = if (record.id == 0L) nextNotifId++ else record.id
            notifications.add(record.copy(id = id))
            return id
        }

        override suspend fun getNotificationById(id: Long): NotificationRecordEntity? =
            notifications.firstOrNull { it.id == id }

        override fun observeActiveDigestItems(): Flow<List<NotificationRecordEntity>> =
            MutableStateFlow(emptyList())

        override suspend fun getActiveDigestItems(): List<NotificationRecordEntity> = emptyList()

        override fun observeSpamRecords(): Flow<List<NotificationRecordEntity>> =
            MutableStateFlow(emptyList())

        override fun observeSpamCountSince(sinceMillis: Long): Flow<Int> = MutableStateFlow(0)

        override suspend fun clearActiveDigest() = Unit

        override suspend fun getUnseenNotificationsForPackage(packageName: String): List<NotificationRecordEntity> =
            notifications.filter { it.packageName == packageName && !it.clearedFromDigest }

        override suspend fun getAllUnseenNotifications(): List<NotificationRecordEntity> =
            notifications.filter { !it.clearedFromDigest }

        override suspend fun markNotificationsSeen(ids: List<Long>) {
            val idSet = ids.toSet()
            for (i in notifications.indices) {
                if (notifications[i].id in idSet) {
                    notifications[i] = notifications[i].copy(clearedFromDigest = true)
                }
            }
        }

        override suspend fun updateNotificationTier(id: Long, newTier: TriageTier, cleared: Boolean) = Unit

        override suspend fun purgeAllSpam() = Unit

        override suspend fun searchNotifications(
            searchQuery: String,
            tierFilter: TriageTier?,
            limit: Int
        ): List<NotificationRecordEntity> = emptyList()

        override suspend fun getRecentThreadNotifications(
            packageName: String,
            sender: String,
            shortcutId: String?,
            limit: Int
        ): List<NotificationRecordEntity> = emptyList()

        override suspend fun getSenderEngagement(senderKey: String): SenderEngagementEntity? = null

        override suspend fun upsertSenderEngagement(entity: SenderEngagementEntity) = Unit

        override suspend fun upsertScreenThreadSnapshot(snapshot: ScreenThreadSnapshotEntity) = Unit

        override suspend fun getRecentScreenSnapshotsForApp(
            packageName: String,
            limit: Int
        ): List<ScreenThreadSnapshotEntity> = emptyList()

        override suspend fun insertConversationTurn(turn: ConversationTurnEntity): Long {
            val id = if (turn.id == 0L) nextTurnId++ else turn.id
            turns.removeAll { it.id == id }
            turns.add(turn.copy(id = id))
            return id
        }

        override fun observeConversationTimeline(): Flow<List<ConversationTurnEntity>> =
            MutableStateFlow(turns.toList())

        override suspend fun getRecentDialogueTurns(limit: Int): List<ConversationTurnEntity> =
            turns.filter { it.role == TurnRole.USER || it.role == TurnRole.ASSISTANT }
                .sortedByDescending { it.id }
                .take(limit)

        override suspend fun getConversationTurnById(turnId: Long): ConversationTurnEntity? =
            turns.firstOrNull { it.id == turnId }

        override suspend fun markTurnUndone(turnId: Long) {
            val idx = turns.indexOfFirst { it.id == turnId }
            if (idx >= 0) turns[idx] = turns[idx].copy(undone = true)
        }

        override suspend fun insertTraceLog(trace: TraceLogEntity): Long {
            val id = if (trace.id == 0L) nextTraceId++ else trace.id
            traces.add(trace.copy(id = id))
            return id
        }

        override fun observeRecentTraces(): Flow<List<TraceLogEntity>> =
            MutableStateFlow(traces.toList())

        override suspend fun getMaxConversationTurnId(): Long? = turns.maxOfOrNull { it.id }

        override suspend fun getMaxMemoryId(): Long? = memories.maxOfOrNull { it.id }

        override suspend fun getMaxTraceId(): Long? = traces.maxOfOrNull { it.id }

        override suspend fun deleteConversationTurnsFrom(startTurnId: Long) {
            turns.removeAll { it.id >= startTurnId }
        }

        override suspend fun deleteMemoriesAfter(startMemoryId: Long) {
            memories.removeAll { it.id > startMemoryId }
        }

        override suspend fun deleteNonTriageTracesAfter(startTraceId: Long) {
            traces.removeAll { it.id > startTraceId && it.subsystem != "TRIAGE" }
        }

        override suspend fun executePragma(query: SupportSQLiteQuery): Int {
            if (query.sql.contains("wal_checkpoint", ignoreCase = true)) {
                walCheckpointCount += 1
            }
            return 0
        }
    }
}
