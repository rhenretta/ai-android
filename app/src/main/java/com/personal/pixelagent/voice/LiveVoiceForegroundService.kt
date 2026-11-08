package com.personal.pixelagent.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.agent.TextChatOrchestrator
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.TurnModality
import com.personal.pixelagent.data.local.TurnRole
import com.personal.pixelagent.data.settings.PrivateSessionBounds
import com.personal.pixelagent.gatekeeper.NotificationChannelsManager
import com.personal.pixelagent.network.GeminiLiveWebSocketClient
import com.personal.pixelagent.network.LiveServerEvent
import com.personal.pixelagent.network.SeedDialogueTurn
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class VoiceSessionPhase {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    MUTED,
    ERROR
}

data class LiveVoiceSessionState(
    val phase: VoiceSessionPhase = VoiceSessionPhase.IDLE,
    val activeVoiceName: String = "Kore",
    val isPrivateMode: Boolean = false,
    val rollingTranscript: String = "",
    val latestActionReceipt: String? = null,
    val rmsLevel: Float = 0.08f,
    val isMuted: Boolean = false,
    val errorMessage: String? = null
) {
    val isActive: Boolean
        get() = phase != VoiceSessionPhase.IDLE && phase != VoiceSessionPhase.ERROR
}

class LiveVoiceForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioController: AudioDuplexController? = null
    private var wsClient: GeminiLiveWebSocketClient? = null
    private var rmsObserverJob: Job? = null

    private var sessionId: String = UUID.randomUUID().toString()
    private val userTranscriptBuffer = StringBuilder()
    private val modelTranscriptBuffer = StringBuilder()
    private val inSessionTurns = ArrayList<SeedDialogueTurn>()
    private val clockFmt = SimpleDateFormat("EEEE, MMM d, yyyy h:mm a z", Locale.US)

    private var currentExchangeUserTurnId: Long? = null
    private var currentExchangeInMemoryIndex: Int? = null
    private var privateModeInMemoryStartIndex: Int = 0
    private var isExecutingToolCall: Boolean = false
    private var suppressPersistenceForPrivateExit: Boolean = false
    private var pendingExitBoundsToFinalize: PrivateSessionBounds? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownSession()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMuteInternal()
                return START_NOT_STICKY
            }
            ACTION_EXIT_PRIVATE -> {
                val app = applicationContext as PixelAgentApp
                serviceScope.launch {
                    app.container.toolExecutor.exitPrivateModeAndPurge()
                }
                return START_NOT_STICKY
            }
        }

        if (_sessionState.value.isActive) {
            return START_NOT_STICKY
        }

        val app = applicationContext as PixelAgentApp
        val voiceName = app.container.settingsStore.getVoiceName()
        val isPrivate = app.container.settingsStore.isPrivateModeActive()

        // Start foreground immediately within Android 14+ microphone FGS window
        try {
            val notification = app.container.channelsManager.buildVoiceForegroundNotification(
                statusText = if (isPrivate) "Private Mode · Connecting..." else "Connecting synchronous voice...",
                voiceName = voiceName,
                isPrivateMode = isPrivate
            )
            startForeground(
                NotificationChannelsManager.VOICE_FGS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (t: Throwable) {
            serviceScope.launch {
                app.container.traceLogger.logError(
                    subsystem = "VOICE_LIVE",
                    eventType = "FGS_START_DENIED",
                    contextMessage = "Failed to start microphone foreground service",
                    throwable = t
                )
            }
            _sessionState.update {
                LiveVoiceSessionState(
                    phase = VoiceSessionPhase.ERROR,
                    activeVoiceName = voiceName,
                    isPrivateMode = isPrivate,
                    errorMessage = t.message ?: "Microphone foreground service blocked."
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startLiveSession(app, voiceName)
        return START_NOT_STICKY
    }

    private fun startLiveSession(app: PixelAgentApp, initialVoiceName: String) {
        sessionId = UUID.randomUUID().toString()
        inSessionTurns.clear()
        userTranscriptBuffer.clear()
        modelTranscriptBuffer.clear()
        currentExchangeUserTurnId = null
        currentExchangeInMemoryIndex = null
        isExecutingToolCall = false
        suppressPersistenceForPrivateExit = false
        pendingExitBoundsToFinalize = null

        val existingPrivateBounds = app.container.settingsStore.getPrivateSessionBounds()
        val initiallyPrivate = existingPrivateBounds != null

        _sessionState.value = LiveVoiceSessionState(
            phase = VoiceSessionPhase.CONNECTING,
            activeVoiceName = initialVoiceName,
            isPrivateMode = initiallyPrivate,
            rollingTranscript = if (initiallyPrivate) "Private Mode · Connecting..." else "Connecting..."
        )
        AgentVoiceWidgetProvider.requestRefresh(this)

        serviceScope.launch {
            try {
                val duplex = AudioDuplexController(
                    context = applicationContext,
                    scope = serviceScope,
                    onMicPcm16kChunk = { bytes, len ->
                        wsClient?.sendPcm16kAudioChunk(bytes, len)
                    }
                )
                duplex.start()
                audioController = duplex

                rmsObserverJob?.cancel()
                rmsObserverJob = serviceScope.launch {
                    duplex.rmsLevel.collect { level ->
                        _sessionState.update { it.copy(rmsLevel = level) }
                    }
                }

                // Register live voice hot-swap hook so `switch_agent_voice` immediately reconnects in the new voice
                app.container.toolExecutor.onActiveVoiceSwitched = { newVoice ->
                    if (!suppressPersistenceForPrivateExit) {
                        flushTranscriptBuffersToDatabase(app)
                    }
                    _sessionState.update {
                        it.copy(
                            phase = VoiceSessionPhase.CONNECTING,
                            activeVoiceName = newVoice,
                            latestActionReceipt = "Switched voice to $newVoice",
                            rollingTranscript = "Switching voice to $newVoice..."
                        )
                    }
                    updateForegroundNotification(
                        app = app,
                        status = "Voice switched to $newVoice",
                        voiceName = newVoice,
                        isPrivateMode = _sessionState.value.isPrivateMode
                    )
                    AgentVoiceWidgetProvider.requestRefresh(this@LiveVoiceForegroundService)
                    serviceScope.launch {
                        val refreshedInstruction = TextChatOrchestrator.buildAssistantSystemInstruction(
                            dao = app.container.database.agentDao(),
                            settingsStore = app.container.settingsStore,
                            memoryRepository = app.container.memoryRepository,
                            queryHint = "voice assistant session",
                            formattedNow = clockFmt.format(Date())
                        )
                        wsClient?.reconnectWithVoice(
                            newVoiceName = newVoice,
                            recentTurns = inSessionTurns.takeLast(10),
                            updatedSystemInstruction = refreshedInstruction
                        )
                    }
                }

                // Register Private Mode hook so entering/exiting via voice or UI updates & scrubs live state
                app.container.toolExecutor.onPrivateModeChanged = { enabled, bounds ->
                    val voice = _sessionState.value.activeVoiceName
                    if (enabled) {
                        suppressPersistenceForPrivateExit = false
                        pendingExitBoundsToFinalize = null
                        privateModeInMemoryStartIndex = currentExchangeInMemoryIndex ?: inSessionTurns.size
                        _sessionState.update {
                            it.copy(
                                isPrivateMode = true,
                                latestActionReceipt = "Private Mode Active · Erased on exit"
                            )
                        }
                        updateForegroundNotification(
                            app = app,
                            status = "Private Mode · Listening...",
                            voiceName = voice,
                            isPrivateMode = true
                        )
                    } else {
                        userTranscriptBuffer.clear()
                        modelTranscriptBuffer.clear()
                        while (inSessionTurns.size > privateModeInMemoryStartIndex) {
                            inSessionTurns.removeAt(inSessionTurns.lastIndex)
                        }
                        currentExchangeUserTurnId = null
                        currentExchangeInMemoryIndex = null

                        _sessionState.update {
                            it.copy(
                                isPrivateMode = false,
                                rollingTranscript = "Exited Private Mode · Session erased",
                                latestActionReceipt = "Private session permanently deleted"
                            )
                        }
                        updateForegroundNotification(
                            app = app,
                            status = "Listening...",
                            voiceName = voice,
                            isPrivateMode = false
                        )

                        if (isExecutingToolCall) {
                            // Spoken exit: allow brief spoken confirmation without persisting, then scrub WS on turn completion
                            suppressPersistenceForPrivateExit = true
                            pendingExitBoundsToFinalize = bounds
                        } else {
                            // Manual 1-tap Exit & Wipe button while voice session is open: flush audio and scrub WS immediately
                            audioController?.flushPlaybackImmediately()
                            suppressPersistenceForPrivateExit = false
                            pendingExitBoundsToFinalize = null
                            _sessionState.update {
                                it.copy(
                                    phase = VoiceSessionPhase.CONNECTING,
                                    rollingTranscript = "Listening..."
                                )
                            }
                            serviceScope.launch {
                                val refreshedInstruction = TextChatOrchestrator.buildAssistantSystemInstruction(
                                    dao = app.container.database.agentDao(),
                                    settingsStore = app.container.settingsStore,
                                    memoryRepository = app.container.memoryRepository,
                                    queryHint = "voice assistant session",
                                    formattedNow = clockFmt.format(Date())
                                )
                                wsClient?.reconnectCleanAfterPrivateExit(
                                    voiceName = voice,
                                    updatedSystemInstruction = refreshedInstruction,
                                    prePrivateTurns = inSessionTurns.takeLast(8)
                                )
                            }
                        }
                    }
                    AgentVoiceWidgetProvider.requestRefresh(this@LiveVoiceForegroundService)
                }

                val systemInstruction = TextChatOrchestrator.buildAssistantSystemInstruction(
                    dao = app.container.database.agentDao(),
                    settingsStore = app.container.settingsStore,
                    memoryRepository = app.container.memoryRepository,
                    queryHint = "voice assistant session",
                    formattedNow = clockFmt.format(Date())
                )

                val recentDbEntities = app.container.database.agentDao()
                    .getRecentDialogueTurns(limit = 6)
                    .reversed()

                privateModeInMemoryStartIndex = if (existingPrivateBounds != null) {
                    recentDbEntities.count { it.id < existingPrivateBounds.startTurnId }
                } else {
                    recentDbEntities.size
                }

                val priorDbTurns = recentDbEntities.map { turn ->
                    SeedDialogueTurn(
                        role = if (turn.role == TurnRole.USER) "user" else "model",
                        text = turn.text
                    )
                }
                inSessionTurns.addAll(priorDbTurns)

                val liveClient = GeminiLiveWebSocketClient(
                    httpClient = app.container.httpClient,
                    apiKeyProvider = { app.container.settingsStore.getApiKeyOrThrow() },
                    scope = serviceScope,
                    onEvent = { event -> handleLiveEvent(app, event) }
                )
                wsClient = liveClient

                liveClient.connect(
                    model = app.container.settingsStore.getLiveVoiceModel(),
                    voiceName = initialVoiceName,
                    systemInstruction = systemInstruction,
                    nonBlockingFunctionDeclarations = app.container.toolExecutor.buildLiveNonBlockingDeclarationsJson(),
                    seedTurns = priorDbTurns
                )
            } catch (t: Throwable) {
                if (!app.container.settingsStore.isPrivateModeActive()) {
                    app.container.traceLogger.logError(
                        subsystem = "VOICE_LIVE",
                        eventType = "SESSION_INIT_ERROR",
                        contextMessage = "Failed to initialize Gemini Live session",
                        throwable = t
                    )
                }
                _sessionState.update {
                    it.copy(
                        phase = VoiceSessionPhase.ERROR,
                        errorMessage = t.message ?: t::class.java.simpleName
                    )
                }
                audioController?.stop()
                AgentVoiceWidgetProvider.requestRefresh(this@LiveVoiceForegroundService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun handleLiveEvent(app: PixelAgentApp, event: LiveServerEvent) {
        when (event) {
            LiveServerEvent.SetupComplete -> {
                val voice = _sessionState.value.activeVoiceName
                val isPrivate = _sessionState.value.isPrivateMode
                _sessionState.update {
                    it.copy(
                        phase = if (it.isMuted) VoiceSessionPhase.MUTED else VoiceSessionPhase.LISTENING,
                        rollingTranscript = if (
                            it.rollingTranscript.startsWith("Connecting") ||
                            it.rollingTranscript.startsWith("Private Mode · Connecting") ||
                            it.rollingTranscript.startsWith("Switching")
                        ) {
                            if (isPrivate) "Private Mode · Listening..." else "Listening..."
                        } else it.rollingTranscript
                    )
                }
                updateForegroundNotification(
                    app = app,
                    status = if (isPrivate) "Private Mode · Listening..." else "Listening...",
                    voiceName = voice,
                    isPrivateMode = isPrivate
                )
                AgentVoiceWidgetProvider.requestRefresh(this)
            }

            is LiveServerEvent.AudioChunk -> {
                audioController?.enqueueSpeakerPcm24k(event.pcm24kBytes)
                if (_sessionState.value.phase != VoiceSessionPhase.SPEAKING && !_sessionState.value.isMuted) {
                    _sessionState.update { it.copy(phase = VoiceSessionPhase.SPEAKING) }
                }
            }

            is LiveServerEvent.InputTranscript -> {
                if (suppressPersistenceForPrivateExit) {
                    return
                }
                // Flush any completed prior model speech before accumulating new user speech
                if (modelTranscriptBuffer.isNotEmpty()) {
                    persistAndClearModelTranscript(app)
                    currentExchangeUserTurnId = null
                    currentExchangeInMemoryIndex = null
                }
                userTranscriptBuffer.append(event.text)
                _sessionState.update {
                    it.copy(rollingTranscript = userTranscriptBuffer.toString().trim().takeLast(180))
                }
            }

            is LiveServerEvent.OutputTranscript -> {
                if (suppressPersistenceForPrivateExit) {
                    // Show transient spoken confirmation without persisting to memory or SQLite
                    _sessionState.update {
                        it.copy(rollingTranscript = "Exited Private Mode · Session erased")
                    }
                    return
                }
                if (userTranscriptBuffer.isNotEmpty()) {
                    persistAndClearUserTranscript(app)
                }
                modelTranscriptBuffer.append(event.text)
                _sessionState.update {
                    it.copy(rollingTranscript = modelTranscriptBuffer.toString().trim().takeLast(180))
                }
            }

            LiveServerEvent.Interrupted -> {
                audioController?.flushPlaybackImmediately()
                if (suppressPersistenceForPrivateExit) {
                    completeVoiceExchange(app)
                    return
                }
                if (modelTranscriptBuffer.isNotEmpty()) {
                    persistAndClearModelTranscript(app)
                }
                _sessionState.update {
                    it.copy(phase = if (it.isMuted) VoiceSessionPhase.MUTED else VoiceSessionPhase.LISTENING)
                }
            }

            is LiveServerEvent.InteractionStatusChanged -> {
                when (event.status) {
                    "IN_PROGRESS" -> {
                        if (_sessionState.value.phase != VoiceSessionPhase.SPEAKING) {
                            _sessionState.update { it.copy(phase = VoiceSessionPhase.THINKING) }
                        }
                    }
                    "IDLE" -> {
                        val reconnected = completeVoiceExchange(app)
                        if (!reconnected) {
                            _sessionState.update {
                                it.copy(phase = if (it.isMuted) VoiceSessionPhase.MUTED else VoiceSessionPhase.LISTENING)
                            }
                        }
                    }
                }
            }

            LiveServerEvent.TurnComplete -> {
                val reconnected = completeVoiceExchange(app)
                if (!reconnected && _sessionState.value.phase == VoiceSessionPhase.SPEAKING) {
                    _sessionState.update {
                        it.copy(phase = if (it.isMuted) VoiceSessionPhase.MUTED else VoiceSessionPhase.LISTENING)
                    }
                }
            }

            is LiveServerEvent.ToolCall -> {
                _sessionState.update { it.copy(phase = VoiceSessionPhase.THINKING) }
                if (userTranscriptBuffer.isNotEmpty() && !suppressPersistenceForPrivateExit) {
                    persistAndClearUserTranscript(app)
                }
                isExecutingToolCall = true
                val output = try {
                    app.container.toolExecutor.executeTool(
                        name = event.name,
                        args = event.args,
                        sessionId = sessionId,
                        modality = TurnModality.VOICE,
                        triggeringTurnId = currentExchangeUserTurnId
                    )
                } finally {
                    isExecutingToolCall = false
                }
                wsClient?.sendToolResponse(event.id, event.name, output)
                if (event.name != "set_private_mode") {
                    val receiptLabel = when (event.name) {
                        "update_memory" -> {
                            val id = output["memory_id"]?.toString()?.trim('"').orEmpty()
                            if (id.isNotEmpty()) "Edited rule #$id" else "Rule edited"
                        }
                        "delete_memory", "forget_memory" -> {
                            val id = output["deleted_memory_id"]?.toString()?.trim('"').orEmpty()
                            if (id.isNotEmpty()) "Deleted rule #$id" else "Rule deleted"
                        }
                        "remember_preference_or_fact" -> {
                            val id = output["memory_id"]?.toString()?.trim('"').orEmpty()
                            if (id.isNotEmpty()) "Saved new rule #$id" else "Saved new rule"
                        }
                        "list_memories" -> {
                            val count = output["count"]?.toString()?.trim('"').orEmpty()
                            if (count.isNotEmpty()) "Checked $count active rules" else "Checked active rules"
                        }
                        else -> "Ran ${event.name.replace('_', ' ')}"
                    }
                    _sessionState.update {
                        it.copy(latestActionReceipt = receiptLabel)
                    }
                }
            }

            is LiveServerEvent.Disconnected -> {
                // Ignore clean intentional disconnect during hot-swap or private-exit reconnect
                if (_sessionState.value.phase != VoiceSessionPhase.CONNECTING) {
                    shutdownSession()
                }
            }

            is LiveServerEvent.Error -> {
                if (!app.container.settingsStore.isPrivateModeActive() && !suppressPersistenceForPrivateExit) {
                    app.container.traceLogger.logError(
                        subsystem = "VOICE_LIVE",
                        eventType = "LIVE_WS_ERROR",
                        contextMessage = event.message,
                        throwable = event.throwable
                    )
                }
                _sessionState.update {
                    it.copy(
                        phase = VoiceSessionPhase.ERROR,
                        errorMessage = event.message
                    )
                }
                audioController?.stop()
                AgentVoiceWidgetProvider.requestRefresh(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun completeVoiceExchange(app: PixelAgentApp): Boolean {
        currentExchangeUserTurnId = null
        currentExchangeInMemoryIndex = null

        if (suppressPersistenceForPrivateExit) {
            userTranscriptBuffer.clear()
            modelTranscriptBuffer.clear()
            val bounds = pendingExitBoundsToFinalize
            suppressPersistenceForPrivateExit = false
            pendingExitBoundsToFinalize = null
            if (bounds != null) {
                app.container.toolExecutor.purgePrivateBounds(bounds)
            }
            val voice = _sessionState.value.activeVoiceName
            _sessionState.update {
                it.copy(
                    phase = VoiceSessionPhase.CONNECTING,
                    isPrivateMode = false,
                    rollingTranscript = "Listening...",
                    latestActionReceipt = "Private session permanently deleted"
                )
            }
            val refreshedInstruction = TextChatOrchestrator.buildAssistantSystemInstruction(
                dao = app.container.database.agentDao(),
                settingsStore = app.container.settingsStore,
                memoryRepository = app.container.memoryRepository,
                queryHint = "voice assistant session",
                formattedNow = clockFmt.format(Date())
            )
            wsClient?.reconnectCleanAfterPrivateExit(
                voiceName = voice,
                updatedSystemInstruction = refreshedInstruction,
                prePrivateTurns = inSessionTurns.takeLast(8)
            )
            return true
        }

        if (userTranscriptBuffer.isNotEmpty()) persistAndClearUserTranscript(app)
        if (modelTranscriptBuffer.isNotEmpty()) persistAndClearModelTranscript(app)
        return false
    }

    private fun toggleMuteInternal() {
        val controller = audioController ?: return
        val newMuted = !controller.isMuted
        controller.isMuted = newMuted
        if (newMuted) {
            wsClient?.sendAudioStreamEnd()
        }
        _sessionState.update {
            it.copy(
                isMuted = newMuted,
                phase = if (newMuted) VoiceSessionPhase.MUTED else VoiceSessionPhase.LISTENING
            )
        }
    }

    private fun flushTranscriptBuffersToDatabase(app: PixelAgentApp) {
        serviceScope.launch {
            if (suppressPersistenceForPrivateExit) {
                userTranscriptBuffer.clear()
                modelTranscriptBuffer.clear()
                return@launch
            }
            if (userTranscriptBuffer.isNotEmpty()) persistAndClearUserTranscript(app)
            if (modelTranscriptBuffer.isNotEmpty()) persistAndClearModelTranscript(app)
        }
    }

    private suspend fun persistAndClearUserTranscript(app: PixelAgentApp): Long? {
        val text = userTranscriptBuffer.toString().trim()
        userTranscriptBuffer.clear()
        if (text.isEmpty() || suppressPersistenceForPrivateExit) return null

        if (currentExchangeInMemoryIndex == null) {
            currentExchangeInMemoryIndex = inSessionTurns.size
        }
        inSessionTurns.add(SeedDialogueTurn("user", text))
        val insertedId = app.container.database.agentDao().insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = TurnModality.VOICE,
                role = TurnRole.USER,
                text = text
            )
        )
        if (currentExchangeUserTurnId == null) {
            currentExchangeUserTurnId = insertedId
        }
        return insertedId
    }

    private suspend fun persistAndClearModelTranscript(app: PixelAgentApp): Long? {
        val text = modelTranscriptBuffer.toString().trim()
        modelTranscriptBuffer.clear()
        if (text.isEmpty() || suppressPersistenceForPrivateExit) return null

        inSessionTurns.add(SeedDialogueTurn("model", text))
        return app.container.database.agentDao().insertConversationTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                modality = TurnModality.VOICE,
                role = TurnRole.ASSISTANT,
                text = text
            )
        )
    }

    private fun updateForegroundNotification(
        app: PixelAgentApp,
        status: String,
        voiceName: String,
        isPrivateMode: Boolean
    ) {
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        mgr.notify(
            NotificationChannelsManager.VOICE_FGS_NOTIFICATION_ID,
            app.container.channelsManager.buildVoiceForegroundNotification(
                statusText = status,
                voiceName = voiceName,
                isPrivateMode = isPrivateMode
            )
        )
    }

    private fun shutdownSession() {
        val app = applicationContext as? PixelAgentApp
        app?.container?.toolExecutor?.onActiveVoiceSwitched = null
        app?.container?.toolExecutor?.onPrivateModeChanged = null
        if (suppressPersistenceForPrivateExit) {
            userTranscriptBuffer.clear()
            modelTranscriptBuffer.clear()
            val bounds = pendingExitBoundsToFinalize
            suppressPersistenceForPrivateExit = false
            pendingExitBoundsToFinalize = null
            if (app != null && bounds != null) {
                app.applicationScope.launch {
                    app.container.toolExecutor.purgePrivateBounds(bounds)
                }
            }
        } else if (app != null) {
            flushTranscriptBuffersToDatabase(app)
        }
        rmsObserverJob?.cancel()
        audioController?.stop()
        audioController = null
        wsClient?.disconnect()
        wsClient = null
        _sessionState.value = LiveVoiceSessionState(
            phase = VoiceSessionPhase.IDLE,
            activeVoiceName = app?.container?.settingsStore?.getVoiceName() ?: "Kore",
            isPrivateMode = app?.container?.settingsStore?.isPrivateModeActive() ?: false
        )
        AgentVoiceWidgetProvider.requestRefresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioController?.stop()
        wsClient?.disconnect()
        serviceScope.cancel()
    }

    companion object {
        private const val ACTION_START = "com.personal.pixelagent.VOICE_START"
        private const val ACTION_STOP = "com.personal.pixelagent.VOICE_STOP"
        private const val ACTION_TOGGLE_MUTE = "com.personal.pixelagent.VOICE_TOGGLE_MUTE"
        private const val ACTION_EXIT_PRIVATE = "com.personal.pixelagent.VOICE_EXIT_PRIVATE"

        private val _sessionState = MutableStateFlow(LiveVoiceSessionState())
        val sessionState: StateFlow<LiveVoiceSessionState> = _sessionState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, LiveVoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveVoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleMute(context: Context) {
            val intent = Intent(context, LiveVoiceForegroundService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            }
            context.startService(intent)
        }

        fun exitPrivateMode(context: Context) {
            val app = context.applicationContext as? PixelAgentApp ?: return
            app.applicationScope.launch {
                app.container.toolExecutor.exitPrivateModeAndPurge()
            }
        }
    }
}
