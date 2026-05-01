package com.example.dndscribe.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppConfig(
    val whisperUrl: String = "",
    val whisperApiKey: String = "",
    val llmUrl: String = "",
    val llmApiKey: String = "",
    val allowInsecureHttp: Boolean = false,
    val syncApiSettings: Boolean = false,
    val llmModel: String = "mistral",
    val whisperModel: String = "whisper-1",
    val chunkSec: Int = 15,
    val notesIntervalMin: Int = 10,
    val finalIntervalMin: Int = 120,
    val includePreviousNotesContext: Boolean = false,
    val previousNotesContextCount: Int = 1,
    val cloudBackupEnabled: Boolean = false,
    val cloudUrl: String = "",
    val cloudApiKey: String = "",
    val cloudDeviceId: String = "android",
    val notesPrompt: String = "You are a D&D session scribe. Given the transcript excerpt below, write a short paragraph (4-6 sentences) summarizing what just happened. Focus on: what the party did, any NPCs they interacted with, decisions made, and anything discovered. Write in past tense. Be concise — this is a running log entry, not a full summary.",
    val finalPrompt: String = "You are a meticulous D&D session scribe. Given the session notes below, produce structured session summary:\n\n## Session Summary\nA 2-3 sentence narrative overview.\n\n## Key Events\n- Bullet list of major plot moments in order.\n\n## NPCs Encountered\n- Name: brief description / role / attitude\n\n## Decisions Made\n- Notable choices the party made\n\n## Loot & Discoveries\n- Items found, secrets uncovered, locations revealed\n\n## Threads & Hooks\n- Unresolved threads or hooks for next session\n\nKeep it concise but complete. Write in past tense."
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val WHISPER_URL = stringPreferencesKey("whisper_url")
        val WHISPER_API_KEY = stringPreferencesKey("whisper_api_key")
        val LLM_URL = stringPreferencesKey("llm_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val ALLOW_INSECURE_HTTP = booleanPreferencesKey("allow_insecure_http")
        val SYNC_API = booleanPreferencesKey("sync_api")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val WHISPER_MODEL = stringPreferencesKey("whisper_model")
        val CHUNK_SEC = intPreferencesKey("chunk_sec")
        val NOTES_INTERVAL = intPreferencesKey("notes_interval")
        val FINAL_INTERVAL = intPreferencesKey("final_interval")
        val INCLUDE_PREVIOUS_NOTES_CONTEXT = booleanPreferencesKey("include_previous_notes_context")
        val PREVIOUS_NOTES_CONTEXT_COUNT = intPreferencesKey("previous_notes_context_count")
        val CLOUD_BACKUP_ENABLED = booleanPreferencesKey("cloud_backup_enabled")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_DEVICE_ID = stringPreferencesKey("cloud_device_id")
        val NOTES_PROMPT = stringPreferencesKey("notes_prompt")
        val FINAL_PROMPT = stringPreferencesKey("final_prompt")
    }

    val configFlow: Flow<AppConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppConfig(
                whisperUrl = preferences[Keys.WHISPER_URL] ?: "",
                whisperApiKey = preferences[Keys.WHISPER_API_KEY] ?: "",
                llmUrl = preferences[Keys.LLM_URL] ?: "",
                llmApiKey = preferences[Keys.LLM_API_KEY] ?: "",
                allowInsecureHttp = preferences[Keys.ALLOW_INSECURE_HTTP] ?: false,
                syncApiSettings = preferences[Keys.SYNC_API] ?: false,
                llmModel = preferences[Keys.LLM_MODEL] ?: "mistral",
                whisperModel = preferences[Keys.WHISPER_MODEL] ?: "base",
                chunkSec = preferences[Keys.CHUNK_SEC] ?: 15,
                notesIntervalMin = preferences[Keys.NOTES_INTERVAL] ?: 10,
                finalIntervalMin = preferences[Keys.FINAL_INTERVAL] ?: 120,
                includePreviousNotesContext = preferences[Keys.INCLUDE_PREVIOUS_NOTES_CONTEXT] ?: false,
                previousNotesContextCount = preferences[Keys.PREVIOUS_NOTES_CONTEXT_COUNT] ?: 1,
                cloudBackupEnabled = preferences[Keys.CLOUD_BACKUP_ENABLED] ?: false,
                cloudUrl = preferences[Keys.CLOUD_URL] ?: "",
                cloudApiKey = preferences[Keys.CLOUD_API_KEY] ?: "",
                cloudDeviceId = preferences[Keys.CLOUD_DEVICE_ID] ?: "android",
                notesPrompt = preferences[Keys.NOTES_PROMPT] ?: AppConfig().notesPrompt,
                finalPrompt = preferences[Keys.FINAL_PROMPT] ?: AppConfig().finalPrompt
            )
        }

    suspend fun updateConfig(config: AppConfig) {
        context.dataStore.edit { preferences ->
            preferences[Keys.WHISPER_URL] = config.whisperUrl
            preferences[Keys.WHISPER_API_KEY] = config.whisperApiKey
            preferences[Keys.LLM_URL] = config.llmUrl
            preferences[Keys.LLM_API_KEY] = config.llmApiKey
            preferences[Keys.ALLOW_INSECURE_HTTP] = config.allowInsecureHttp
            preferences[Keys.SYNC_API] = config.syncApiSettings
            preferences[Keys.LLM_MODEL] = config.llmModel
            preferences[Keys.WHISPER_MODEL] = config.whisperModel
            preferences[Keys.CHUNK_SEC] = config.chunkSec
            preferences[Keys.NOTES_INTERVAL] = config.notesIntervalMin
            preferences[Keys.FINAL_INTERVAL] = config.finalIntervalMin
            preferences[Keys.INCLUDE_PREVIOUS_NOTES_CONTEXT] = config.includePreviousNotesContext
            preferences[Keys.PREVIOUS_NOTES_CONTEXT_COUNT] = config.previousNotesContextCount
            preferences[Keys.CLOUD_BACKUP_ENABLED] = config.cloudBackupEnabled
            preferences[Keys.CLOUD_URL] = config.cloudUrl
            preferences[Keys.CLOUD_API_KEY] = config.cloudApiKey
            preferences[Keys.CLOUD_DEVICE_ID] = config.cloudDeviceId
            preferences[Keys.NOTES_PROMPT] = config.notesPrompt
            preferences[Keys.FINAL_PROMPT] = config.finalPrompt
        }
    }
}
