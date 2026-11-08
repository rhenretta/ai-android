package com.personal.pixelagent.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrivateSessionBounds(
    val startTurnId: Long,
    val startMemoryId: Long,
    val startTraceId: Long
)

data class AgentSettingsSnapshot(
    val hasApiKey: Boolean,
    val voiceName: String,
    val previousVoiceName: String?,
    val gatekeeperEnabled: Boolean,
    val sleepStrictCalls: Boolean,
    val privateModeActive: Boolean,
    val triageModel: String,
    val triageThinkingLevel: String,
    val liveVoiceModel: String,
    val lastDegradedError: String?
)

class SecureSettingsStore internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "pixel_agent_secure_prefs",
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    )

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<AgentSettingsSnapshot> = _state.asStateFlow()

    fun getApiKeyOrThrow(): String {
        val key = prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("Gemini API key is not configured. Add GEMINI_API_KEY to .env and run deploy_and_grant_pixel.sh, or paste your key in Setup.")
        }
        return key
    }

    fun hasApiKey(): Boolean = !prefs.getString(KEY_GEMINI_API_KEY, null)?.trim().isNullOrEmpty()

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API key cannot be blank." }
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).remove(KEY_LAST_DEGRADED_ERROR).apply()
        refresh()
    }

    fun getVoiceName(): String = prefs.getString(KEY_VOICE_NAME, DEFAULT_FEMALE_VOICE) ?: DEFAULT_FEMALE_VOICE

    fun setVoiceName(newVoice: String): String {
        val clean = newVoice.trim()
        require(clean.isNotEmpty()) { "Voice name cannot be empty." }
        val previous = getVoiceName()
        prefs.edit()
            .putString(KEY_PREVIOUS_VOICE_NAME, previous)
            .putString(KEY_VOICE_NAME, clean)
            .apply()
        refresh()
        return previous
    }

    fun isGatekeeperEnabled(): Boolean = prefs.getBoolean(KEY_GATEKEEPER_ENABLED, true)

    fun setGatekeeperEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GATEKEEPER_ENABLED, enabled).apply()
        refresh()
    }

    fun isSleepStrictCalls(): Boolean = prefs.getBoolean(KEY_SLEEP_STRICT_CALLS, false)

    fun setSleepStrictCalls(strict: Boolean) {
        prefs.edit().putBoolean(KEY_SLEEP_STRICT_CALLS, strict).apply()
        refresh()
    }

    fun getZenRuleId(): String? = prefs.getString(KEY_ZEN_RULE_ID, null)

    fun setZenRuleId(ruleId: String?) {
        prefs.edit().apply {
            if (ruleId == null) remove(KEY_ZEN_RULE_ID) else putString(KEY_ZEN_RULE_ID, ruleId)
        }.apply()
    }

    fun getTriageModel(): String = prefs.getString(KEY_TRIAGE_MODEL_V2, DEFAULT_TRIAGE_MODEL) ?: DEFAULT_TRIAGE_MODEL

    fun setTriageModel(model: String) {
        val clean = model.trim()
        require(clean.isNotEmpty()) { "Triage model cannot be blank." }
        prefs.edit().putString(KEY_TRIAGE_MODEL_V2, clean).apply()
        refresh()
    }

    fun getTriageThinkingLevel(): String =
        prefs.getString(KEY_TRIAGE_THINKING_LEVEL, DEFAULT_TRIAGE_THINKING_LEVEL) ?: DEFAULT_TRIAGE_THINKING_LEVEL

    fun setTriageThinkingLevel(level: String) {
        val clean = level.trim().lowercase()
        require(clean in SUPPORTED_THINKING_LEVELS) {
            "Unsupported thinking level '$level'. Supported levels: low, medium, high."
        }
        prefs.edit().putString(KEY_TRIAGE_THINKING_LEVEL, clean).apply()
        refresh()
    }

    fun getChatModel(): String = DEFAULT_CHAT_MODEL

    fun getLiveVoiceModel(): String = prefs.getString(KEY_LIVE_VOICE_MODEL, DEFAULT_LIVE_MODEL) ?: DEFAULT_LIVE_MODEL

    fun setLastDegradedError(error: String?) {
        prefs.edit().apply {
            if (error == null) remove(KEY_LAST_DEGRADED_ERROR) else putString(KEY_LAST_DEGRADED_ERROR, error)
        }.apply()
        refresh()
    }

    fun isPrivateModeActive(): Boolean = prefs.getBoolean(KEY_PRIVATE_MODE_ACTIVE, false)

    fun enterPrivateMode(bounds: PrivateSessionBounds) {
        if (isPrivateModeActive()) return
        prefs.edit()
            .putBoolean(KEY_PRIVATE_MODE_ACTIVE, true)
            .putLong(KEY_PRIVATE_START_TURN_ID, bounds.startTurnId)
            .putLong(KEY_PRIVATE_START_MEMORY_ID, bounds.startMemoryId)
            .putLong(KEY_PRIVATE_START_TRACE_ID, bounds.startTraceId)
            .apply()
        refresh()
    }

    fun getPrivateSessionBounds(): PrivateSessionBounds? {
        if (!isPrivateModeActive()) return null
        return PrivateSessionBounds(
            startTurnId = prefs.getLong(KEY_PRIVATE_START_TURN_ID, 0L),
            startMemoryId = prefs.getLong(KEY_PRIVATE_START_MEMORY_ID, 0L),
            startTraceId = prefs.getLong(KEY_PRIVATE_START_TRACE_ID, 0L)
        )
    }

    fun exitPrivateModeAndConsumeBounds(): PrivateSessionBounds? {
        val bounds = getPrivateSessionBounds()
        prefs.edit()
            .putBoolean(KEY_PRIVATE_MODE_ACTIVE, false)
            .remove(KEY_PRIVATE_START_TURN_ID)
            .remove(KEY_PRIVATE_START_MEMORY_ID)
            .remove(KEY_PRIVATE_START_TRACE_ID)
            .apply()
        refresh()
        return bounds
    }

    private fun refresh() {
        _state.value = readSnapshot()
    }

    private fun readSnapshot(): AgentSettingsSnapshot = AgentSettingsSnapshot(
        hasApiKey = hasApiKey(),
        voiceName = getVoiceName(),
        previousVoiceName = prefs.getString(KEY_PREVIOUS_VOICE_NAME, null),
        gatekeeperEnabled = isGatekeeperEnabled(),
        sleepStrictCalls = isSleepStrictCalls(),
        privateModeActive = isPrivateModeActive(),
        triageModel = getTriageModel(),
        triageThinkingLevel = getTriageThinkingLevel(),
        liveVoiceModel = getLiveVoiceModel(),
        lastDegradedError = prefs.getString(KEY_LAST_DEGRADED_ERROR, null)
    )

    companion object {
        const val DEFAULT_FEMALE_VOICE = "Kore"
        const val DEFAULT_TRIAGE_MODEL = "gemini-3.8-flash"
        const val DEFAULT_TRIAGE_THINKING_LEVEL = "low"
        const val DEFAULT_CHAT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live-extended-thinking"
        val SUPPORTED_THINKING_LEVELS = setOf("low", "medium", "high")

        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_PREVIOUS_VOICE_NAME = "previous_voice_name"
        private const val KEY_GATEKEEPER_ENABLED = "gatekeeper_enabled"
        private const val KEY_SLEEP_STRICT_CALLS = "sleep_strict_calls"
        private const val KEY_ZEN_RULE_ID = "zen_rule_id"
        private const val KEY_TRIAGE_MODEL_V2 = "triage_model_v2"
        private const val KEY_TRIAGE_THINKING_LEVEL = "triage_thinking_level"
        private const val KEY_LIVE_VOICE_MODEL = "live_voice_model"
        private const val KEY_LAST_DEGRADED_ERROR = "last_degraded_error"
        private const val KEY_PRIVATE_MODE_ACTIVE = "private_mode_active"
        private const val KEY_PRIVATE_START_TURN_ID = "private_start_turn_id"
        private const val KEY_PRIVATE_START_MEMORY_ID = "private_start_memory_id"
        private const val KEY_PRIVATE_START_TRACE_ID = "private_start_trace_id"
    }
}
