package com.personal.pixelagent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class VoiceCatalogEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val gender: String? = null,
    val accent: String? = null,
    val pitch: String? = null,
    val persona: String? = null,
    val context: String? = null,
    val description: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val type: String = "prebuilt"
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: id
}

@Serializable
data class ListVoicesResponse(
    val voices: List<VoiceCatalogEntry> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null
)

/**
 * Queries Gemini's Voices Catalog (`GET /v1beta/voices`) and enriches entries with
 * official Gemini 3.8 Studio Voice metadata so the assistant can inspect, filter,
 * and self-configure its spoken persona on demand without silent fallbacks.
 */
class GeminiVoicesClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val baseUrl: HttpUrl = "https://generativelanguage.googleapis.com".toHttpUrl()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listVoices(
        gender: String? = null,
        accent: String? = null,
        pitch: String? = null,
        contextDomain: String? = null,
        searchQuery: String? = null,
        pageSize: Int = 50
    ): List<VoiceCatalogEntry> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegments("v1beta/voices")
            .addQueryParameter("type", "prebuilt")
            .addQueryParameter("page_size", pageSize.coerceIn(1, 200).toString())

        gender?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("gender", it.lowercase()) }
        accent?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("accent", it) }
        pitch?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("pitch", it.lowercase()) }
        contextDomain?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("context", it) }
        searchQuery?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("search", it) }

        val url = urlBuilder.build()
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException(response.code, url.encodedPath, raw)
            }
            val parsed = json.decodeFromString(ListVoicesResponse.serializer(), raw)
            if (parsed.voices.isNotEmpty()) {
                parsed.voices.map { entry -> enrichWithStudioMetadata(entry) }
            } else {
                filterStudioCatalogLocally(gender, searchQuery)
            }
        }
    }

    suspend fun validateVoiceExistsOrThrow(voiceId: String): VoiceCatalogEntry {
        val cleanId = voiceId.trim()
        require(cleanId.isNotEmpty()) { "Voice ID cannot be blank." }

        // Check curated 30 Gemini Studio Voices first (case-preserving canonical ID)
        STUDIO_VOICES.firstOrNull { it.id.equals(cleanId, ignoreCase = true) }?.let {
            return it
        }

        // Otherwise query live Voices API (`GET /v1beta/voices`) to validate Extended / Custom Voice IDs
        val matches = listVoices(searchQuery = cleanId, pageSize = 50)
        return matches.firstOrNull { it.id.equals(cleanId, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Voice '$cleanId' was not found in Gemini's available voices catalog. Call search_available_voices first to inspect valid voice IDs."
            )
    }

    private fun enrichWithStudioMetadata(entry: VoiceCatalogEntry): VoiceCatalogEntry {
        val studio = STUDIO_VOICES.firstOrNull { it.id.equals(entry.id, ignoreCase = true) } ?: return entry
        return entry.copy(
            gender = entry.gender ?: studio.gender,
            persona = entry.persona ?: studio.persona,
            description = entry.description ?: studio.description
        )
    }

    private fun filterStudioCatalogLocally(gender: String?, search: String?): List<VoiceCatalogEntry> {
        return STUDIO_VOICES.filter { v ->
            val genderOk = gender.isNullOrBlank() || v.gender.equals(gender.trim(), ignoreCase = true)
            val searchOk = search.isNullOrBlank() ||
                v.id.contains(search, ignoreCase = true) ||
                (v.persona?.contains(search, ignoreCase = true) == true) ||
                (v.description?.contains(search, ignoreCase = true) == true)
            genderOk && searchOk
        }
    }

    companion object {
        val STUDIO_VOICES: List<VoiceCatalogEntry> = listOf(
            VoiceCatalogEntry(id = "Kore", displayName = "Kore", gender = "female", pitch = "medium", persona = "Firm, Clear", description = "Clear, composed, confident female conversational voice (Default)."),
            VoiceCatalogEntry(id = "Aoede", displayName = "Aoede", gender = "female", pitch = "medium", persona = "Breezy, Natural", description = "Warm, breezy, relaxed female conversational voice."),
            VoiceCatalogEntry(id = "Sulafat", displayName = "Sulafat", gender = "female", pitch = "medium", persona = "Warm", description = "Warm, empathetic, reassuring female voice."),
            VoiceCatalogEntry(id = "Zephyr", displayName = "Zephyr", gender = "female", pitch = "high", persona = "Bright", description = "Bright, crisp, energetic female voice."),
            VoiceCatalogEntry(id = "Leda", displayName = "Leda", gender = "female", pitch = "high", persona = "Youthful", description = "Light, youthful, friendly female voice."),
            VoiceCatalogEntry(id = "Callirrhoe", displayName = "Callirrhoe", gender = "female", pitch = "medium", persona = "Easy-going", description = "Easy-going, calm, conversational female voice."),
            VoiceCatalogEntry(id = "Despina", displayName = "Despina", gender = "female", pitch = "medium", persona = "Smooth", description = "Smooth, polished female voice."),
            VoiceCatalogEntry(id = "Vindemiatrix", displayName = "Vindemiatrix", gender = "female", pitch = "low", persona = "Gentle", description = "Gentle, soft-spoken, soothing female voice."),
            VoiceCatalogEntry(id = "Autonoe", displayName = "Autonoe", gender = "female", pitch = "high", persona = "Bright", description = "Articulate, bright female voice."),
            VoiceCatalogEntry(id = "Erinome", displayName = "Erinome", gender = "female", pitch = "medium", persona = "Clear", description = "Crisp, clear, studio-grade female voice."),
            VoiceCatalogEntry(id = "Laomedeia", displayName = "Laomedeia", gender = "female", pitch = "medium", persona = "Upbeat", description = "Upbeat, cheerful female voice."),
            VoiceCatalogEntry(id = "Achernar", displayName = "Achernar", gender = "female", pitch = "medium", persona = "Soft", description = "Soft, calm female voice ideal for quiet hours."),
            VoiceCatalogEntry(id = "Pulcherrima", displayName = "Pulcherrima", gender = "female", pitch = "medium", persona = "Forward", description = "Direct, expressive female voice."),
            VoiceCatalogEntry(id = "Gacrux", displayName = "Gacrux", gender = "female", pitch = "low", persona = "Mature", description = "Mature, grounded, steady voice."),
            VoiceCatalogEntry(id = "Puck", displayName = "Puck", gender = "male", pitch = "medium", persona = "Upbeat", description = "Upbeat, lively, conversational male voice."),
            VoiceCatalogEntry(id = "Charon", displayName = "Charon", gender = "male", pitch = "low", persona = "Informative", description = "Calm, informative, measured male voice."),
            VoiceCatalogEntry(id = "Fenrir", displayName = "Fenrir", gender = "male", pitch = "medium", persona = "Excitable", description = "Dynamic, expressive, energetic male voice."),
            VoiceCatalogEntry(id = "Orus", displayName = "Orus", gender = "male", pitch = "low", persona = "Firm", description = "Steady, authoritative male voice."),
            VoiceCatalogEntry(id = "Enceladus", displayName = "Enceladus", gender = "male", pitch = "low", persona = "Breathy", description = "Soft, relaxed male voice."),
            VoiceCatalogEntry(id = "Iapetus", displayName = "Iapetus", gender = "male", pitch = "medium", persona = "Clear", description = "Clear, neutral male voice."),
            VoiceCatalogEntry(id = "Umbriel", displayName = "Umbriel", gender = "male", pitch = "medium", persona = "Easy-going", description = "Laid-back, natural male voice."),
            VoiceCatalogEntry(id = "Algieba", displayName = "Algieba", gender = "male", pitch = "low", persona = "Smooth", description = "Smooth, resonant male voice."),
            VoiceCatalogEntry(id = "Algenib", displayName = "Algenib", gender = "male", pitch = "low", persona = "Gravelly", description = "Deep, textured, gravelly male voice."),
            VoiceCatalogEntry(id = "Rasalgethi", displayName = "Rasalgethi", gender = "male", pitch = "medium", persona = "Informative", description = "Articulate, narrator-style male voice."),
            VoiceCatalogEntry(id = "Alnilam", displayName = "Alnilam", gender = "male", pitch = "low", persona = "Firm", description = "Focused, decisive male voice."),
            VoiceCatalogEntry(id = "Schedar", displayName = "Schedar", gender = "male", pitch = "medium", persona = "Even", description = "Balanced, even-keeled male voice."),
            VoiceCatalogEntry(id = "Achird", displayName = "Achird", gender = "male", pitch = "medium", persona = "Friendly", description = "Warm, approachable, friendly male voice."),
            VoiceCatalogEntry(id = "Zubenelgenubi", displayName = "Zubenelgenubi", gender = "male", pitch = "medium", persona = "Casual", description = "Relaxed, casual male voice."),
            VoiceCatalogEntry(id = "Sadachbia", displayName = "Sadachbia", gender = "male", pitch = "medium", persona = "Lively", description = "Animated, lively voice."),
            VoiceCatalogEntry(id = "Sadaltager", displayName = "Sadaltager", gender = "male", pitch = "low", persona = "Knowledgeable", description = "Thoughtful, knowledgeable voice.")
        )
    }
}
