package com.personal.pixelagent

import com.personal.pixelagent.agent.TriageContract
import com.personal.pixelagent.data.settings.SecureSettingsStore
import com.personal.pixelagent.network.GeminiApiException
import com.personal.pixelagent.network.GeminiInteractionsClient
import com.personal.pixelagent.network.GeminiVoicesClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiClientsAndToolsTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `interactions client enforces store false, Api-Revision header, and preserves thought signatures across tool rounds`() = runTest {
        // Round 1: Model emits a thought signature + function_call
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "inter_round_1",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "thought",
                          "signature": "sig_opaque_token_abc123",
                          "summary": [{"type": "text", "text": "Checking available voices first."}]
                        },
                        {
                          "type": "function_call",
                          "id": "call_01",
                          "name": "search_available_voices",
                          "arguments": {"gender": "female", "search": "warm"}
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        // Round 2: Model receives function_result and produces final spoken/text answer
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "inter_round_2",
                      "status": "completed",
                      "steps": [
                        {
                          "type": "model_output",
                          "content": [{"type": "text", "text": "I found Sulafat, a warm female voice, and switched to it."}]
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            apiKeyProvider = { "test-api-key-123" },
            baseUrl = server.url("/")
        )

        val tools = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("name", JsonPrimitive("search_available_voices"))
                }
            )
        }

        var capturedCallName = ""
        val reply = client.runToolConversationTurn(
            model = "gemini-3.8-flash",
            systemInstruction = "System test instruction",
            priorDialogueSteps = emptyList(),
            userMessage = "Can you switch to a warm voice?",
            tools = tools,
            toolHandler = { call ->
                capturedCallName = call.name
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("matched_voice", JsonPrimitive("Sulafat"))
                }
            }
        )

        assertEquals("search_available_voices", capturedCallName)
        assertEquals("I found Sulafat, a warm female voice, and switched to it.", reply)

        // Inspect Request 1
        val req1 = server.takeRequest()
        assertEquals("/v1beta/interactions", req1.path)
        assertEquals("2026-05-20", req1.getHeader("Api-Revision"))
        assertEquals("test-api-key-123", req1.getHeader("x-goog-api-key"))
        val body1 = json.parseToJsonElement(req1.body.readUtf8()).jsonObject
        assertEquals(false, body1["store"]!!.jsonPrimitive.boolean)
        assertEquals("gemini-3.8-flash", body1["model"]!!.jsonPrimitive.content)

        // Inspect Request 2: verify `thought` signature + `function_call` + `function_result` were preserved in order
        val req2 = server.takeRequest()
        val body2 = json.parseToJsonElement(req2.body.readUtf8()).jsonObject
        val inputSteps = body2["input"]!!.jsonArray
        assertEquals("user_input", inputSteps[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("thought", inputSteps[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig_opaque_token_abc123", inputSteps[1].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("function_call", inputSteps[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_result", inputSteps[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_01", inputSteps[3].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `interactions client fails fast with GeminiApiException on HTTP error without silent fallback`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"API key not valid"}}""")
        )

        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            apiKeyProvider = { "bad-key" },
            baseUrl = server.url("/")
        )

        try {
            client.executeInteraction(
                model = "gemini-3.8-flash",
                systemInstruction = null,
                inputSteps = listOf(GeminiInteractionsClient.buildUserTextStep("Hello")),
                responseFormat = null,
                tools = null,
                generationConfig = null
            )
            fail("Expected GeminiApiException on HTTP 401")
        } catch (expected: GeminiApiException) {
            assertEquals(401, expected.statusCode)
            assertTrue(expected.responseBody.contains("API key not valid"))
        }
    }

    @Test
    fun `voices client defaults to female Kore and fails fast on nonexistent voice id`() = runTest {
        assertEquals("Kore", SecureSettingsStore.DEFAULT_FEMALE_VOICE)
        val defaultStudio = GeminiVoicesClient.STUDIO_VOICES.first()
        assertEquals("Kore", defaultStudio.id)
        assertEquals("female", defaultStudio.gender)

        // Mock empty search result for an invalid voice ID -> must throw, never silently swap
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"voices":[{"id":"Aoede","gender":"female"}]}""")
        )

        val voicesClient = GeminiVoicesClient(
            httpClient = httpClient,
            apiKeyProvider = { "valid-key" },
            baseUrl = server.url("/")
        )

        // Known studio voice resolves cleanly
        val kore = voicesClient.validateVoiceExistsOrThrow("kore")
        assertEquals("Kore", kore.id)

        // Unknown voice fails fast with explicit IllegalArgumentException
        try {
            voicesClient.validateVoiceExistsOrThrow("NonExistentSyntheticVoice99")
            fail("Expected IllegalArgumentException when requesting nonexistent voice")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("NonExistentSyntheticVoice99"))
        }
    }

    @Test
    fun `structured triage enforces active thinking config and 3_8-flash default`() = runTest {
        assertEquals("gemini-3.8-flash", SecureSettingsStore.DEFAULT_TRIAGE_MODEL)
        assertEquals("low", SecureSettingsStore.DEFAULT_TRIAGE_THINKING_LEVEL)
        assertEquals("gemini-3.8-flash", SecureSettingsStore.DEFAULT_CHAT_MODEL)

        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "id": "inter_triage_fast",
                          "status": "completed",
                          "steps": [
                            {
                              "type": "model_output",
                              "content": [{"type": "text", "text": "{\"rationale\":\"Verified sender and topic scope match.\",\"tier\":\"URGENT\"}"}]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )
        }

        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            apiKeyProvider = { "valid-key" },
            baseUrl = server.url("/")
        )

        val outDefault = client.generateStructuredJson(
            model = SecureSettingsStore.DEFAULT_TRIAGE_MODEL,
            systemInstruction = TriageContract.BASELINE_SYSTEM_INSTRUCTION,
            userPrompt = "Sample prompt",
            jsonSchema = TriageContract.JSON_SCHEMA
        )
        assertTrue(outDefault.contains("\"tier\":\"URGENT\""))

        val reqDefault = server.takeRequest()
        val reqDefaultBody = json.parseToJsonElement(reqDefault.body.readUtf8()).jsonObject
        assertEquals("gemini-3.8-flash", reqDefaultBody["model"]!!.jsonPrimitive.content)
        val genCfgDefault = reqDefaultBody["generation_config"]!!.jsonObject
        assertEquals("low", genCfgDefault["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("none", genCfgDefault["thinking_summaries"]!!.jsonPrimitive.content)
        assertFalse(genCfgDefault.containsKey("temperature"))

        val outMedium = client.generateStructuredJson(
            model = SecureSettingsStore.DEFAULT_TRIAGE_MODEL,
            systemInstruction = TriageContract.BASELINE_SYSTEM_INSTRUCTION,
            userPrompt = "Sample prompt",
            jsonSchema = TriageContract.JSON_SCHEMA,
            thinkingLevel = "medium"
        )
        assertTrue(outMedium.contains("\"tier\":\"URGENT\""))

        val reqMedium = server.takeRequest()
        val genCfgMedium = json.parseToJsonElement(reqMedium.body.readUtf8()).jsonObject["generation_config"]!!.jsonObject
        assertEquals("medium", genCfgMedium["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("none", genCfgMedium["thinking_summaries"]!!.jsonPrimitive.content)
    }
}
