package com.personal.pixelagent

import android.app.Application
import android.content.Context
import com.personal.pixelagent.agent.AgentToolExecutor
import com.personal.pixelagent.agent.TextChatOrchestrator
import com.personal.pixelagent.context.CommunicationContextEngine
import com.personal.pixelagent.context.SeenMessageTracker
import com.personal.pixelagent.data.local.AppDatabase
import com.personal.pixelagent.data.memory.MemoryRepository
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.gatekeeper.ActionIntentCache
import com.personal.pixelagent.gatekeeper.NotificationChannelsManager
import com.personal.pixelagent.gatekeeper.NotificationTriageEngine
import com.personal.pixelagent.gatekeeper.ZenModeController
import com.personal.pixelagent.network.GeminiEmbeddingClient
import com.personal.pixelagent.network.GeminiInteractionsClient
import com.personal.pixelagent.network.GeminiVoicesClient
import com.personal.pixelagent.observability.TraceLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val settingsStore: SecureSettingsStore = SecureSettingsStore(appContext)
    val traceLogger: TraceLogger = TraceLogger(database.agentDao())

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 25, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val embeddingClient: GeminiEmbeddingClient = GeminiEmbeddingClient(
        httpClient = httpClient,
        apiKeyProvider = { settingsStore.getApiKeyOrThrow() }
    )

    val interactionsClient: GeminiInteractionsClient = GeminiInteractionsClient(
        httpClient = httpClient,
        apiKeyProvider = { settingsStore.getApiKeyOrThrow() }
    )

    val voicesClient: GeminiVoicesClient = GeminiVoicesClient(
        httpClient = httpClient,
        apiKeyProvider = { settingsStore.getApiKeyOrThrow() }
    )

    val memoryRepository: MemoryRepository = MemoryRepository(
        dao = database.agentDao(),
        embeddingClient = embeddingClient,
        traceLogger = traceLogger
    )

    val contextEngine: CommunicationContextEngine = CommunicationContextEngine(
        context = appContext,
        dao = database.agentDao()
    )

    val actionIntentCache: ActionIntentCache = ActionIntentCache(database.agentDao())

    val channelsManager: NotificationChannelsManager = NotificationChannelsManager(appContext).also {
        it.ensureChannelsCreated()
    }

    val zenModeController: ZenModeController = ZenModeController(appContext, settingsStore)

    val seenMessageTracker: SeenMessageTracker = SeenMessageTracker(
        context = appContext,
        dao = database.agentDao(),
        settingsStore = settingsStore,
        actionIntentCache = actionIntentCache,
        channelsManager = channelsManager,
        traceLogger = traceLogger
    )

    val triageEngine: NotificationTriageEngine = NotificationTriageEngine(
        context = appContext,
        dao = database.agentDao(),
        settingsStore = settingsStore,
        contextEngine = contextEngine,
        seenMessageTracker = seenMessageTracker,
        memoryRepository = memoryRepository,
        interactionsClient = interactionsClient,
        actionIntentCache = actionIntentCache,
        channelsManager = channelsManager,
        traceLogger = traceLogger
    )

    val toolExecutor: AgentToolExecutor = AgentToolExecutor(
        appContext = appContext,
        dao = database.agentDao(),
        settingsStore = settingsStore,
        memoryRepository = memoryRepository,
        voicesClient = voicesClient,
        contextEngine = contextEngine,
        actionIntentCache = actionIntentCache,
        channelsManager = channelsManager,
        zenModeController = zenModeController,
        traceLogger = traceLogger
    )

    val chatOrchestrator: TextChatOrchestrator = TextChatOrchestrator(
        dao = database.agentDao(),
        settingsStore = settingsStore,
        memoryRepository = memoryRepository,
        interactionsClient = interactionsClient,
        toolExecutor = toolExecutor,
        traceLogger = traceLogger
    )
}

class PixelAgentApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.seenMessageTracker.start()
    }
}
