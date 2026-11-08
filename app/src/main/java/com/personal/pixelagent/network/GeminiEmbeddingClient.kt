package com.personal.pixelagent.network

import com.personal.pixelagent.data.memory.EmbeddingMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

fun interface EmbeddingProvider {
    suspend fun embedText(text: String): FloatArray
}

class GeminiApiException(
    val statusCode: Int,
    val endpoint: String,
    val responseBody: String,
    message: String = "Gemini API error ($statusCode) at $endpoint: $responseBody"
) : IOException(message)

class GeminiEmbeddingClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val baseUrl: HttpUrl = "https://generativelanguage.googleapis.com".toHttpUrl()
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EmbedPart(val text: String)

    @Serializable
    private data class EmbedContentPayload(val parts: List<EmbedPart>)

    @Serializable
    private data class EmbedRequest(
        val model: String,
        val content: EmbedContentPayload,
        @SerialName("outputDimensionality") val outputDimensionality: Int
    )

    @Serializable
    private data class EmbeddingValues(val values: List<Float> = emptyList())

    @Serializable
    private data class EmbedResponse(val embedding: EmbeddingValues? = null)

    override suspend fun embedText(text: String): FloatArray = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        val url = baseUrl.newBuilder()
            .addPathSegments("v1beta/models/$EMBEDDING_MODEL:embedContent")
            .build()

        val payload = EmbedRequest(
            model = "models/$EMBEDDING_MODEL",
            content = EmbedContentPayload(parts = listOf(EmbedPart(text = text))),
            outputDimensionality = EmbeddingMath.TARGET_DIMENSIONS
        )

        val requestBody = json.encodeToString(EmbedRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException(response.code, url.encodedPath, rawBody)
            }
            val parsed = json.decodeFromString(EmbedResponse.serializer(), rawBody)
            val values = parsed.embedding?.values
            if (values.isNullOrEmpty()) {
                throw GeminiApiException(
                    response.code,
                    url.encodedPath,
                    rawBody,
                    "Gemini embedding response returned empty vector values."
                )
            }
            EmbeddingMath.normalizeL2(values.toFloatArray())
        }
    }

    companion object {
        const val EMBEDDING_MODEL = "gemini-embedding-2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
