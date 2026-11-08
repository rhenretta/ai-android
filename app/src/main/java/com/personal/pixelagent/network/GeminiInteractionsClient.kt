package com.personal.pixelagent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class InteractionFunctionCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

data class InteractionTurnResponse(
    val id: String,
    val status: String,
    val rawSteps: List<JsonObject>,
    val outputText: String,
    val functionCalls: List<InteractionFunctionCall>,
    val thoughtSummaries: List<String>
)

/**
 * Direct Kotlin client for the Gemini Interactions REST API (`POST /v1beta/interactions`,
 * `Api-Revision: 2026-05-20`), enforcing `store = false` so personal notification payloads
 * and conversations remain strictly on-Pixel.
 */
class GeminiInteractionsClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val baseUrl: HttpUrl = "https://generativelanguage.googleapis.com".toHttpUrl()
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val fastTriageClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun generateStructuredJson(
        model: String,
        systemInstruction: String,
        userPrompt: String,
        jsonSchema: JsonObject,
        thinkingLevel: String = "low"
    ): String {
        val inputSteps = listOf(buildUserTextStep(userPrompt))
        val responseFormat = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("mime_type", JsonPrimitive("application/json"))
            put("schema", jsonSchema)
        }
        val generationConfig = buildJsonObject {
            put("thinking_level", JsonPrimitive(thinkingLevel))
            put("thinking_summaries", JsonPrimitive("none"))
        }

        val response = executeInteraction(
            model = model,
            systemInstruction = systemInstruction,
            inputSteps = inputSteps,
            responseFormat = responseFormat,
            tools = null,
            generationConfig = generationConfig,
            useFastTimeoutClient = true
        )

        if (response.outputText.isBlank()) {
            throw GeminiApiException(
                statusCode = 200,
                endpoint = "/v1beta/interactions",
                responseBody = response.rawSteps.toString(),
                message = "Gemini structured output returned empty model_output text."
            )
        }
        return response.outputText
    }

    suspend fun runToolConversationTurn(
        model: String,
        systemInstruction: String,
        priorDialogueSteps: List<JsonObject>,
        userMessage: String,
        tools: JsonArray,
        toolHandler: suspend (InteractionFunctionCall) -> JsonObject,
        maxToolRounds: Int = 5
    ): String {
        val stepHistory = ArrayList<JsonObject>(priorDialogueSteps.size + 8)
        stepHistory.addAll(priorDialogueSteps)
        stepHistory.add(buildUserTextStep(userMessage))

        val chatGenConfig = buildJsonObject {
            put("thinking_level", JsonPrimitive("medium"))
        }

        var rounds = 0
        while (rounds < maxToolRounds) {
            rounds++
            val turn = executeInteraction(
                model = model,
                systemInstruction = systemInstruction,
                inputSteps = stepHistory,
                responseFormat = null,
                tools = tools,
                generationConfig = chatGenConfig
            )

            // Preserve all returned steps (including `thought` signatures and `function_call` steps) verbatim
            stepHistory.addAll(turn.rawSteps)

            if (turn.functionCalls.isEmpty()) {
                return turn.outputText
            }

            for (call in turn.functionCalls) {
                val resultJson = toolHandler(call)
                stepHistory.add(
                    buildFunctionResultStep(
                        callId = call.id,
                        name = call.name,
                        resultPayload = resultJson
                    )
                )
            }
        }

        throw IllegalStateException("Exceeded maximum tool call rounds ($maxToolRounds) without final response.")
    }

    suspend fun executeInteraction(
        model: String,
        systemInstruction: String?,
        inputSteps: List<JsonObject>,
        responseFormat: JsonObject?,
        tools: JsonArray?,
        generationConfig: JsonObject?,
        useFastTimeoutClient: Boolean = false
    ): InteractionTurnResponse = withContext(Dispatchers.IO) {
        val client = if (useFastTimeoutClient) fastTriageClient else httpClient
        val apiKey = apiKeyProvider()
        val url = baseUrl.newBuilder()
            .addPathSegments("v1beta/interactions")
            .build()

        val bodyObj = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("store", JsonPrimitive(false))
            if (!systemInstruction.isNullOrBlank()) {
                put("system_instruction", JsonPrimitive(systemInstruction))
            }
            put("input", JsonArray(inputSteps))
            if (responseFormat != null) {
                put("response_format", responseFormat)
            }
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
            }
            if (generationConfig != null) {
                put("generation_config", generationConfig)
            }
        }

        fun buildRequest(): Request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Api-Revision", API_REVISION)
            .header("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val rawResponse = try {
            client.newCall(buildRequest()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GeminiApiException(response.code, url.encodedPath, raw)
                }
                raw
            }
        } catch (_: SocketTimeoutException) {
            // Evict half-dead pooled HTTP/2 sockets (common across mobile/VPN transitions) and retry once on a fresh socket
            httpClient.connectionPool.evictAll()
            client.newCall(buildRequest()).execute().use { retryResponse ->
                val raw = retryResponse.body?.string().orEmpty()
                if (!retryResponse.isSuccessful) {
                    throw GeminiApiException(retryResponse.code, url.encodedPath, raw)
                }
                raw
            }
        }

        parseInteractionResponse(rawResponse)
    }

    private fun parseInteractionResponse(rawJson: String): InteractionTurnResponse {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val id = root["id"]?.jsonPrimitive?.content.orEmpty()
        val status = root["status"]?.jsonPrimitive?.content ?: "completed"
        val stepsArray = root["steps"]?.jsonArray ?: JsonArray(emptyList())

        val rawSteps = mutableListOf<JsonObject>()
        val textParts = mutableListOf<String>()
        val functionCalls = mutableListOf<InteractionFunctionCall>()
        val thoughtSummaries = mutableListOf<String>()

        for (element: JsonElement in stepsArray) {
            val step = element.jsonObject
            rawSteps.add(step)
            when (step["type"]?.jsonPrimitive?.content) {
                "model_output" -> {
                    val contentArr = step["content"]?.jsonArray ?: continue
                    for (partEl in contentArr) {
                        val part = partEl.jsonObject
                        if (part["type"]?.jsonPrimitive?.content == "text") {
                            part["text"]?.jsonPrimitive?.content?.let { textParts.add(it) }
                        }
                    }
                }
                "function_call" -> {
                    val fcId = step["id"]?.jsonPrimitive?.content.orEmpty()
                    val fcName = step["name"]?.jsonPrimitive?.content.orEmpty()
                    val fcArgs = step["arguments"]?.jsonObject ?: buildJsonObject {}
                    if (fcName.isNotEmpty()) {
                        functionCalls.add(InteractionFunctionCall(fcId, fcName, fcArgs))
                    }
                }
                "thought" -> {
                    val summaryArr = step["summary"]?.jsonArray
                    summaryArr?.forEach { sEl ->
                        sEl.jsonObject["text"]?.jsonPrimitive?.content?.let { thoughtSummaries.add(it) }
                    }
                }
            }
        }

        return InteractionTurnResponse(
            id = id,
            status = status,
            rawSteps = rawSteps,
            outputText = textParts.joinToString(""),
            functionCalls = functionCalls,
            thoughtSummaries = thoughtSummaries
        )
    }

    companion object {
        const val API_REVISION = "2026-05-20"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun buildUserTextStep(text: String): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("user_input"))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        }
                    )
                }
            )
        }

        fun buildModelTextStep(text: String): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("model_output"))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        }
                    )
                }
            )
        }

        fun buildFunctionResultStep(
            callId: String,
            name: String,
            resultPayload: JsonObject
        ): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("function_result"))
            put("call_id", JsonPrimitive(callId))
            put("name", JsonPrimitive(name))
            put(
                "result",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(resultPayload.toString()))
                        }
                    )
                }
            )
        }
    }
}
