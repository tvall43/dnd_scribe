package com.example.dndscribe.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dndscribe.data.local.AppDatabase
import com.example.dndscribe.data.local.SessionEntity
import com.example.dndscribe.data.remote.AiSessionClient
import com.example.dndscribe.data.repository.ActiveSessionRepository
import com.example.dndscribe.data.repository.AppConfig
import com.example.dndscribe.data.repository.SessionRepository
import com.example.dndscribe.data.repository.SettingsRepository
import com.example.dndscribe.recording.ActiveSessionState
import com.example.dndscribe.recording.RecordingService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepository = SessionRepository(AppDatabase.getDatabase(application).sessionDao())
    private val settingsRepository = SettingsRepository(application)
    private val activeSessionRepository = ActiveSessionRepository(application)
    private val gson = Gson()

    private val _config = MutableStateFlow(AppConfig())
    val config = _config.asStateFlow()

    val currentTranscript = ActiveSessionState.currentTranscript
    val currentNotes = ActiveSessionState.currentNotes
    val finalSummary = ActiveSessionState.finalSummary
    val isRecording = ActiveSessionState.isRecording
    val isUpdatingNotes = ActiveSessionState.isUpdatingNotes
    val isGeneratingFinal = ActiveSessionState.isGeneratingFinal

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val allSessions = sessionRepository.allSessions
    
    val filteredSessions = combine(allSessions, _searchQuery) { sessions, query ->
        if (query.isBlank()) sessions
        else sessions.filter { 
            it.name.contains(query, ignoreCase = true) || 
            it.notes.contains(query, ignoreCase = true) 
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            settingsRepository.configFlow.collect {
                if (_config.value != it) {
                    _config.value = it
                }
            }
        }
        viewModelScope.launch {
            val snapshot = activeSessionRepository.snapshotFlow.first()
            ActiveSessionState.restore(snapshot.transcript, snapshot.notes, snapshot.finalSummary)
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleRecording() {
        val context = getApplication<Application>().applicationContext
        if (isRecording.value) {
            context.startService(RecordingService.stopIntent(context))
        } else {
            ContextCompat.startForegroundService(context, RecordingService.startIntent(context))
        }
    }

    fun generateNote() {
        val cfg = _config.value
        if (isRecording.value) {
            getApplication<Application>().applicationContext.startService(
                RecordingService.generateNoteIntent(getApplication())
            )
            return
        }

        if (isUpdatingNotes.value || currentTranscript.value.isBlank()) return
        if (!AiSessionClient.validateBaseUrl(cfg.llmUrl, cfg.allowInsecureHttp)) {
            Toast.makeText(getApplication(), "Set a valid LLM URL. Enable insecure HTTP if you need http:// endpoints.", Toast.LENGTH_LONG).show()
            return
        }

        ActiveSessionState.setUpdatingNotes(true)
        viewModelScope.launch {
            try {
                val note = AiSessionClient.generateNote(cfg, currentTranscript.value)
                if (!note.isNullOrBlank()) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    ActiveSessionState.appendNote("-- $time --\n$note")
                    persistActiveSessionContent()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Note generation failed", e)
            } finally {
                ActiveSessionState.setUpdatingNotes(false)
            }
        }
    }

    fun generateFinal() {
        val cfg = _config.value
        if (isRecording.value) {
            getApplication<Application>().applicationContext.startService(
                RecordingService.generateFinalIntent(getApplication())
            )
            return
        }

        if (isGeneratingFinal.value || currentNotes.value.isBlank()) return
        if (!AiSessionClient.validateBaseUrl(cfg.llmUrl, cfg.allowInsecureHttp)) {
            Toast.makeText(getApplication(), "Set a valid LLM URL. Enable insecure HTTP if you need http:// endpoints.", Toast.LENGTH_LONG).show()
            return
        }

        ActiveSessionState.setGeneratingFinal(true)
        viewModelScope.launch {
            try {
                val summary = AiSessionClient.generateFinal(cfg, currentNotes.value)
                if (!summary.isNullOrBlank()) {
                    ActiveSessionState.setFinalSummary(summary)
                    persistActiveSessionContent()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Final summary failed", e)
            } finally {
                ActiveSessionState.setGeneratingFinal(false)
            }
        }
    }

    fun saveCurrentSession() {
        viewModelScope.launch {
            if (isRecording.value) {
                Toast.makeText(getApplication(), "Stop recording before saving the session", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (currentTranscript.value.isBlank() && currentNotes.value.isBlank()) return@launch
            val name = "Session - " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            sessionRepository.insertSession(
                SessionEntity(
                    name = name,
                    fullTranscript = currentTranscript.value,
                    notes = currentNotes.value,
                    finalSummary = finalSummary.value
                )
            )
            clearActiveSession()
        }
    }

    fun clearActiveSession() {
        ActiveSessionState.clear()
        viewModelScope.launch {
            activeSessionRepository.clear()
        }
    }
    
    fun updateConfig(newConfig: AppConfig) {
        if (_config.value == newConfig) return
        _config.value = newConfig
        viewModelScope.launch {
            settingsRepository.updateConfig(newConfig)
        }
    }

    fun updateNotes(newNotes: String) {
        ActiveSessionState.updateNotes(newNotes)
        viewModelScope.launch {
            persistActiveSessionContent()
        }
    }

    fun updateTranscript(newTranscript: String) {
        ActiveSessionState.updateTranscript(newTranscript)
        viewModelScope.launch {
            persistActiveSessionContent()
        }
    }

    fun updateFinalSummary(newSummary: String) {
        ActiveSessionState.updateFinalSummary(newSummary)
        viewModelScope.launch {
            persistActiveSessionContent()
        }
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            sessionRepository.deleteSession(session)
        }
    }

    fun loadSession(session: SessionEntity) {
        if (isRecording.value) {
            Toast.makeText(getApplication(), "Stop recording before loading an archived session", Toast.LENGTH_SHORT).show()
            return
        }
        ActiveSessionState.load(session)
        viewModelScope.launch {
            persistActiveSessionContent()
        }
    }

    fun exportArchives(context: Context) {
        viewModelScope.launch {
            try {
                val sessions = allSessions.firstOrNull() ?: emptyList()
                if (sessions.isEmpty()) {
                    Toast.makeText(context, "No sessions to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val json = gson.toJson(sessions)
                val exportDir = File(context.cacheDir, "exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                val fileName = "dnd-scribe-archives-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                val exportFile = File(exportDir, fileName)
                exportFile.writeText(json)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )
                
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    type = "application/json"
                }
                
                val shareIntent = Intent.createChooser(sendIntent, "Export DnD Scribe Archives")
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Export failed", e)
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importArchives(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json != null) {
                    val type = object : TypeToken<List<SessionEntity>>() {}.type
                    val importedSessions: List<SessionEntity> = gson.fromJson(json, type)
                    importedSessions.forEach { session ->
                        sessionRepository.insertSession(session.copy(id = 0))
                    }
                    Toast.makeText(context, "Imported ${importedSessions.size} sessions", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Import failed", e)
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun persistActiveSessionContent() {
        activeSessionRepository.updateContent(
            transcript = currentTranscript.value,
            notes = currentNotes.value,
            finalSummary = finalSummary.value
        )
    }
}
