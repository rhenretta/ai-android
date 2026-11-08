package com.personal.pixelagent.network

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class AudioChunk(val pcm24kBytes: ByteArray) : LiveServerEvent
    data class InputTranscript(val text: String) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data class InteractionStatusChanged(val status: String) : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class ToolCall(val id: String, val name: String, val args: JsonObject) : LiveServerEvent
    data class Disconnected(val code: Int, val reason: String) : LiveServerEvent
    data class Error(val message: String, val throwable: Throwable) : LiveServerEvent
}

data class SeedDialogueTurn(
    val role: String, // "user" or "model"
    val text: String
)

/**
 * Bidirectional WebSocket client for Gemini 3.8 Live (`BidiGenerateContent`).
 * Supports non-blocking tool execution, real-time input/output transcripts, barge-in interruption,
 * and mid-session voice hot-swapping via `reconnectWithVoice`.
 */
class GeminiLiveWebSocketClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val scope: CoroutineScope,
    private val onEvent: suspend (LiveServerEvent) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupCompleted: Boolean = false

    private var lastModel: String = "gemini-3.8-live-extended-thinking"
    private var lastSystemInstruction: String = ""
    private var lastTools: JsonArray = JsonArray(emptyList())

    fun connect(
        model: String,
        voiceName: String,
        systemInstruction: String,
        nonBlockingFunctionDeclarations: JsonArray,
        seedTurns: List<SeedDialogueTurn> = emptyList(),
        triggerModelGreetingPrompt: String? = null
    ) {
        disconnect()

        lastModel = model
        lastSystemInstruction = systemInstruction
        lastTools = nonBlockingFunctionDeclarations
        setupCompleted = false

        val apiKey = apiKeyProvider()
        val wsUrl = "$LIVE_WS_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        val fullModelName = if (model.startsWith("models/")) model else "models/$model"
        val isExtendedThinking = fullModelName.contains("extended-thinking")

        val setupFrame = buildJsonObject {
            put(
                "setup",
                buildJsonObject {
                    put("model", JsonPrimitive(fullModelName))
                    put(
                        "systemInstruction",
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", JsonPrimitive(systemInstruction)) })
                                }
                            )
                        }
                    )
                    put(
                        "generationConfig",
                        buildJsonObject {
                            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                            put(
                                "speechConfig",
                                buildJsonObject {
                                    put(
                                        "voiceConfig",
                                        buildJsonObject {
                                            put(
                                                "prebuiltVoiceConfig",
                                                buildJsonObject {
                                                    put("voiceName", JsonPrimitive(voiceName))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            if (isExtendedThinking) {
                                put(
                                    "thinkingConfig",
                                    buildJsonObject {
                                        put("thinkingLevel", JsonPrimitive("LOW"))
                                    }
                                )
                            }
                        }
                    )
                    put("inputAudioTranscription", buildJsonObject {})
                    put("outputAudioTranscription", buildJsonObject {})
                    if (nonBlockingFunctionDeclarations.isNotEmpty()) {
                        put(
                            "tools",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("functionDeclarations", nonBlockingFunctionDeclarations)
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== this@GeminiLiveWebSocketClient.webSocket) return
                    webSocket.send(setupFrame.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== this@GeminiLiveWebSocketClient.webSocket) return
                    handleIncomingJson(webSocket, text, seedTurns, triggerModelGreetingPrompt)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (webSocket !== this@GeminiLiveWebSocketClient.webSocket) return
                    handleIncomingJson(webSocket, bytes.utf8(), seedTurns, triggerModelGreetingPrompt)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    if (webSocket === this@GeminiLiveWebSocketClient.webSocket) {
                        scope.launch { onEvent(LiveServerEvent.Disconnected(code, reason)) }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== this@GeminiLiveWebSocketClient.webSocket) return
                    val detail = response?.message?.takeIf { it.isNotBlank() } ?: (t.message ?: "WebSocket failure")
                    scope.launch { onEvent(LiveServerEvent.Error(detail, t)) }
                }
            }
        )
    }

    /**
     * Hot-swaps the active Live session to a newly selected voice while re-injecting
     * the ongoing conversation turns and immediately speaking confirmation in the new voice.
     */
    fun reconnectWithVoice(
        newVoiceName: String,
        recentTurns: List<SeedDialogueTurn>,
        updatedSystemInstruction: String = lastSystemInstruction
    ) {
        connect(
            model = lastModel,
            voiceName = newVoiceName,
            systemInstruction = updatedSystemInstruction,
            nonBlockingFunctionDeclarations = lastTools,
            seedTurns = recentTurns,
            triggerModelGreetingPrompt = "[Voice configuration updated to '$newVoiceName'. Confirm briefly in one natural spoken sentence using your new voice.]"
        )
    }

    /**
     * Silently replaces the active Live WebSocket session after exiting Private Mode so zero
     * private audio/dialogue remains in the remote context, without triggering a voice-switch greeting.
     */
    fun reconnectCleanAfterPrivateExit(
        voiceName: String,
        updatedSystemInstruction: String,
        prePrivateTurns: List<SeedDialogueTurn>
    ) {
        connect(
            model = lastModel,
            voiceName = voiceName,
            systemInstruction = updatedSystemInstruction,
            nonBlockingFunctionDeclarations = lastTools,
            seedTurns = prePrivateTurns,
            triggerModelGreetingPrompt = null
        )
    }

    fun sendPcm16kAudioChunk(pcmBytes: ByteArray, length: Int = pcmBytes.size) {
        val ws = webSocket ?: return
        if (!setupCompleted || length <= 0) return
        val slice = if (length == pcmBytes.size) pcmBytes else pcmBytes.copyOf(length)
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put(
                        "audio",
                        buildJsonObject {
                            put("data", JsonPrimitive(b64))
                            put("mimeType", JsonPrimitive("audio/pcm;rate=16000"))
                        }
                    )
                }
            )
        }
        ws.send(payload.toString())
    }

    fun sendAudioStreamEnd() {
        val ws = webSocket ?: return
        if (!setupCompleted) return
        val payload = buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put("audioStreamEnd", JsonPrimitive(true))
                }
            )
        }
        ws.send(payload.toString())
    }

    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!setupCompleted || text.isBlank()) return
        val payload = buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put("text", JsonPrimitive(text))
                }
            )
        }
        ws.send(payload.toString())
    }

    fun sendToolResponse(callId: String, functionName: String, outputJson: JsonObject) {
        val ws = webSocket ?: return
        val payload = buildJsonObject {
            put(
                "toolResponse",
                buildJsonObject {
                    put(
                        "functionResponses",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(callId))
                                    put("name", JsonPrimitive(functionName))
                                    put(
                                        "response",
                                        buildJsonObject {
                                            put("output", outputJson)
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        ws.send(payload.toString())
    }

    fun disconnect() {
        setupCompleted = false
        webSocket?.close(1000, "Session ended")
        webSocket = null
    }

    private fun handleIncomingJson(
        ws: WebSocket,
        rawText: String,
        seedTurns: List<SeedDialogueTurn>,
        triggerPrompt: String?
    ) {
        scope.launch {
            try {
                val root = json.parseToJsonElement(rawText).jsonObject

                if (root.containsKey("setupComplete")) {
                    setupCompleted = true
                    if (seedTurns.isNotEmpty() || !triggerPrompt.isNullOrBlank()) {
                        val turnsJson = buildJsonArray {
                            for (t in seedTurns) {
                                add(
                                    buildJsonObject {
                                        put("role", JsonPrimitive(t.role))
                                        put(
                                            "parts",
                                            buildJsonArray {
                                                add(buildJsonObject { put("text", JsonPrimitive(t.text)) })
                                            }
                                        )
                                    }
                                )
                            }
                            if (!triggerPrompt.isNullOrBlank()) {
                                add(
                                    buildJsonObject {
                                        put("role", JsonPrimitive("user"))
                                        put(
                                            "parts",
                                            buildJsonArray {
                                                add(buildJsonObject { put("text", JsonPrimitive(triggerPrompt)) })
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        val clientContentMsg = buildJsonObject {
                            put(
                                "clientContent",
                                buildJsonObject {
                                    put("turns", turnsJson)
                                    put("turnComplete", JsonPrimitive(!triggerPrompt.isNullOrBlank()))
                                }
                            )
                        }
                        ws.send(clientContentMsg.toString())
                    }
                    onEvent(LiveServerEvent.SetupComplete)
                }

                // Track extended-thinking lifecycle status
                val interactionStatus = root["interactionStatus"]?.jsonPrimitive?.content
                    ?: root["serverContent"]?.jsonObject?.get("interactionStatus")?.jsonPrimitive?.content
                if (!interactionStatus.isNullOrBlank()) {
                    onEvent(LiveServerEvent.InteractionStatusChanged(interactionStatus))
                }

                // Process ALL parts in serverContent simultaneously
                val serverContent = root["serverContent"]?.jsonObject
                if (serverContent != null) {
                    if (serverContent["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                        onEvent(LiveServerEvent.Interrupted)
                    }

                    val parts = serverContent["modelTurn"]?.jsonObject?.get("parts")?.jsonArray
                    if (parts != null) {
                        for (partEl in parts) {
                            val inlineData = partEl.jsonObject["inlineData"]?.jsonObject ?: continue
                            val mime = inlineData["mimeType"]?.jsonPrimitive?.content.orEmpty()
                            val b64 = inlineData["data"]?.jsonPrimitive?.content.orEmpty()
                            if (b64.isNotEmpty() && mime.startsWith("audio/pcm")) {
                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                onEvent(LiveServerEvent.AudioChunk(pcm))
                            }
                        }
                    }

                    serverContent["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() }
                        ?.let { onEvent(LiveServerEvent.InputTranscript(it)) }

                    serverContent["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() }
                        ?.let { onEvent(LiveServerEvent.OutputTranscript(it)) }

                    if (serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                        onEvent(LiveServerEvent.TurnComplete)
                    }
                }

                // Process non-blocking tool calls
                val functionCalls = root["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray
                if (functionCalls != null) {
                    for (fcEl in functionCalls) {
                        val fc = fcEl.jsonObject
                        val id = fc["id"]?.jsonPrimitive?.content.orEmpty()
                        val name = fc["name"]?.jsonPrimitive?.content.orEmpty()
                        val args = fc["args"]?.jsonObject ?: buildJsonObject {}
                        if (name.isNotEmpty()) {
                            onEvent(LiveServerEvent.ToolCall(id, name, args))
                        }
                    }
                }
            } catch (t: Throwable) {
                onEvent(LiveServerEvent.Error("Failed to parse Live WebSocket frame: ${t.message}", t))
            }
        }
    }

    companion object {
        const val LIVE_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }
}
