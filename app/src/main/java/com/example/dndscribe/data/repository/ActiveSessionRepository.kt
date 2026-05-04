package com.example.dndscribe.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.activeSessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "active_session")

data class ActiveSessionSnapshot(
    val transcript: String = "",
    val notes: String = "",
    val finalSummary: String = "",
    val transcriptSinceLastNote: String = "",
    val lastNoteTime: Long = 0L,
    val lastFinalTime: Long = 0L,
    val sessionStartedAt: Long = 0L,
    val lastCloudSyncTime: Long = 0L,
    val activeAudioFile: String? = null
)

class ActiveSessionRepository(private val context: Context) {
    private object Keys {
        val TRANSCRIPT = stringPreferencesKey("transcript")
        val NOTES = stringPreferencesKey("notes")
        val FINAL_SUMMARY = stringPreferencesKey("final_summary")
        val TRANSCRIPT_SINCE_LAST_NOTE = stringPreferencesKey("transcript_since_last_note")
        val LAST_NOTE_TIME = longPreferencesKey("last_note_time")
        val LAST_FINAL_TIME = longPreferencesKey("last_final_time")
        val SESSION_STARTED_AT = longPreferencesKey("session_started_at")
        val LAST_CLOUD_SYNC_TIME = longPreferencesKey("last_cloud_sync_time")
        val ACTIVE_AUDIO_FILE = stringPreferencesKey("active_audio_file")
    }

    val snapshotFlow: Flow<ActiveSessionSnapshot> = context.activeSessionDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            ActiveSessionSnapshot(
                transcript = preferences[Keys.TRANSCRIPT] ?: "",
                notes = preferences[Keys.NOTES] ?: "",
                finalSummary = preferences[Keys.FINAL_SUMMARY] ?: "",
                transcriptSinceLastNote = preferences[Keys.TRANSCRIPT_SINCE_LAST_NOTE] ?: "",
                lastNoteTime = preferences[Keys.LAST_NOTE_TIME] ?: 0L,
                        lastFinalTime = preferences[Keys.LAST_FINAL_TIME] ?: 0L,
                        sessionStartedAt = preferences[Keys.SESSION_STARTED_AT] ?: 0L,
                        lastCloudSyncTime = preferences[Keys.LAST_CLOUD_SYNC_TIME] ?: 0L,
                        activeAudioFile = preferences[Keys.ACTIVE_AUDIO_FILE]
                    )

        }

    suspend fun updateAll(snapshot: ActiveSessionSnapshot) {
        context.activeSessionDataStore.edit { preferences ->
            preferences[Keys.TRANSCRIPT] = snapshot.transcript
            preferences[Keys.NOTES] = snapshot.notes
            preferences[Keys.FINAL_SUMMARY] = snapshot.finalSummary
            preferences[Keys.TRANSCRIPT_SINCE_LAST_NOTE] = snapshot.transcriptSinceLastNote
            preferences[Keys.LAST_NOTE_TIME] = snapshot.lastNoteTime
            preferences[Keys.LAST_FINAL_TIME] = snapshot.lastFinalTime
            preferences[Keys.SESSION_STARTED_AT] = snapshot.sessionStartedAt
            preferences[Keys.LAST_CLOUD_SYNC_TIME] = snapshot.lastCloudSyncTime
            if (snapshot.activeAudioFile != null) {
                preferences[Keys.ACTIVE_AUDIO_FILE] = snapshot.activeAudioFile
            } else {
                preferences.remove(Keys.ACTIVE_AUDIO_FILE)
            }
        }
    }
    
    suspend fun updateCloudSyncTime(lastCloudSyncTime: Long) {

        context.activeSessionDataStore.edit { preferences ->
            preferences[Keys.LAST_CLOUD_SYNC_TIME] = lastCloudSyncTime
        }
    }

    suspend fun updateContent(transcript: String, notes: String, finalSummary: String) {
        context.activeSessionDataStore.edit { preferences ->
            preferences[Keys.TRANSCRIPT] = transcript
            preferences[Keys.NOTES] = notes
            preferences[Keys.FINAL_SUMMARY] = finalSummary
        }
    }

    suspend fun clear() {
        context.activeSessionDataStore.edit { it.clear() }
    }
}
