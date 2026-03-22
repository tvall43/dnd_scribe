package com.example.dndscribe.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dndscribe.data.local.AppDatabase
import com.example.dndscribe.data.local.SessionEntity
import com.example.dndscribe.data.remote.LlmMessage
import com.example.dndscribe.data.remote.LlmRequest
import com.example.dndscribe.data.remote.RetrofitClient
import com.example.dndscribe.data.repository.AppConfig
import com.example.dndscribe.data.repository.SessionRepository
import com.example.dndscribe.data.repository.SettingsRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepository = SessionRepository(AppDatabase.getDatabase(application).sessionDao())
    private val settingsRepository = SettingsRepository(application)
    private val gson = Gson()

    private val _config = MutableStateFlow(AppConfig())
    val config = _config.asStateFlow()

    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript = _currentTranscript.asStateFlow()

    private val _currentNotes = MutableStateFlow("")
    val currentNotes = _currentNotes.asStateFlow()

    private val _finalSummary = MutableStateFlow("")
    val finalSummary = _finalSummary.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isUpdatingNotes = MutableStateFlow(false)
    val isUpdatingNotes = _isUpdatingNotes.asStateFlow()

    private val _isGeneratingFinal = MutableStateFlow(false)
    val isGeneratingFinal = _isGeneratingFinal.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var transcriptSinceLastNote = ""
    private var lastNoteTime = System.currentTimeMillis()
    private var lastFinalTime = System.currentTimeMillis()

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
                // Only update if it's different to avoid overwriting newer in-memory changes
                // with slower DataStore emissions during rapid typing.
                if (_config.value != it) {
                    _config.value = it
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val context = getApplication<Application>().applicationContext
        val audioFile = File(context.cacheDir, "recording.webm")
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            _isRecording.value = true
            lastNoteTime = System.currentTimeMillis()
            lastFinalTime = System.currentTimeMillis()
            
            startRecordingLoop(audioFile)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start recording", e)
        }
    }

    private fun startRecordingLoop(audioFile: File) {
        recordingJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(_config.value.chunkSec * 1000L)
                if (_isRecording.value) {
                    rotateAndTranscribe(audioFile)
                }
                
                val now = System.currentTimeMillis()
                if (now - lastNoteTime > _config.value.notesIntervalMin * 60 * 1000L) {
                    generateNote()
                }
                
                if (now - lastFinalTime > _config.value.finalIntervalMin * 60 * 1000L) {
                    generateFinal()
                }
            }
        }
    }

    private suspend fun rotateAndTranscribe(audioFile: File) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            
            val transcribeFile = File(getApplication<Application>().cacheDir, "chunk_${System.currentTimeMillis()}.webm")
            if (audioFile.exists()) {
                audioFile.renameTo(transcribeFile)
            }
            
            val context = getApplication<Application>().applicationContext
            if (_isRecording.value) {
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    } else {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    }
                    setOutputFile(audioFile.absolutePath)
                    prepare()
                    start()
                }
            }
            
            if (transcribeFile.exists()) {
                transcribeChunk(transcribeFile)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error in rotateAndTranscribe", e)
        }
    }

    private suspend fun transcribeChunk(file: File) {
        val cfg = _config.value
        val whisperUrl = if (cfg.syncApiSettings) cfg.llmUrl else cfg.whisperUrl
        val whisperKey = if (cfg.syncApiSettings) cfg.llmApiKey else cfg.whisperApiKey
        
        if (whisperUrl.isBlank()) return

        try {
            val requestFile = file.asRequestBody("audio/webm".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
            val model = cfg.whisperModel.toRequestBody("text/plain".toMediaTypeOrNull())
            val format = "json".toRequestBody("text/plain".toMediaTypeOrNull())
            val auth = if (whisperKey.isNotBlank()) "Bearer $whisperKey" else null

            val response = RetrofitClient.whisperApi.transcribe(
                "$whisperUrl/v1/audio/transcriptions",
                auth, body, model, format
            )
            
            val text = response.text.trim()
            if (text.isNotBlank()) {
                _currentTranscript.value += (if (_currentTranscript.value.isEmpty()) "" else " ") + text
                transcriptSinceLastNote += (if (transcriptSinceLastNote.isEmpty()) "" else " ") + text
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Transcription failed", e)
        } finally {
            if (file.exists()) file.delete()
        }
    }

    private fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        mediaRecorder?.apply {
            try {
                stop()
            } catch (e: Exception) {}
            release()
        }
        mediaRecorder = null
    }

    fun generateNote() {
        if (_isUpdatingNotes.value || transcriptSinceLastNote.isBlank()) return
        
        val cfg = _config.value
        if (cfg.llmUrl.isBlank()) return

        _isUpdatingNotes.value = true
        viewModelScope.launch {
            try {
                val auth = if (cfg.llmApiKey.isNotBlank()) "Bearer ${cfg.llmApiKey}" else null
                val prompt = cfg.notesPrompt
                val content = "Transcript excerpt:\n\n$transcriptSinceLastNote"
                
                val response = RetrofitClient.llmApi.getCompletion(
                    "${cfg.llmUrl}/v1/chat/completions",
                    auth,
                    LlmRequest(
                        model = cfg.llmModel,
                        messages = listOf(
                            LlmMessage("system", prompt),
                            LlmMessage("user", content)
                        )
                    )
                )
                
                val note = response.choices.firstOrNull()?.message?.content?.trim()
                if (!note.isNullOrBlank()) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val entry = "— $time —\n$note"
                    _currentNotes.value += (if (_currentNotes.value.isEmpty()) "" else "\n\n") + entry
                    transcriptSinceLastNote = ""
                    lastNoteTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Note generation failed", e)
            } finally {
                _isUpdatingNotes.value = false
            }
        }
    }

    fun generateFinal() {
        if (_isGeneratingFinal.value || _currentNotes.value.isBlank()) return
        
        val cfg = _config.value
        if (cfg.llmUrl.isBlank()) return

        _isGeneratingFinal.value = true
        viewModelScope.launch {
            try {
                val auth = if (cfg.llmApiKey.isNotBlank()) "Bearer ${cfg.llmApiKey}" else null
                val prompt = cfg.finalPrompt
                val content = "Full session notes:\n\n${_currentNotes.value}"
                
                val response = RetrofitClient.llmApi.getCompletion(
                    "${cfg.llmUrl}/v1/chat/completions",
                    auth,
                    LlmRequest(
                        model = cfg.llmModel,
                        messages = listOf(
                            LlmMessage("system", prompt),
                            LlmMessage("user", content)
                        )
                    )
                )
                
                val summary = response.choices.firstOrNull()?.message?.content?.trim()
                if (!summary.isNullOrBlank()) {
                    _finalSummary.value = summary
                    lastFinalTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Final summary failed", e)
            } finally {
                _isGeneratingFinal.value = false
            }
        }
    }

    fun saveCurrentSession() {
        viewModelScope.launch {
            if (_currentTranscript.value.isBlank() && _currentNotes.value.isBlank()) return@launch
            val name = "Session - " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            sessionRepository.insertSession(
                SessionEntity(
                    name = name,
                    fullTranscript = _currentTranscript.value,
                    notes = _currentNotes.value,
                    finalSummary = _finalSummary.value
                )
            )
            clearActiveSession()
        }
    }

    fun clearActiveSession() {
        _currentTranscript.value = ""
        _currentNotes.value = ""
        _finalSummary.value = ""
        transcriptSinceLastNote = ""
    }
    
    fun updateConfig(newConfig: AppConfig) {
        if (_config.value == newConfig) return
        _config.value = newConfig
        viewModelScope.launch {
            settingsRepository.updateConfig(newConfig)
        }
    }

    fun updateNotes(newNotes: String) {
        _currentNotes.value = newNotes
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            sessionRepository.deleteSession(session)
        }
    }

    fun loadSession(session: SessionEntity) {
        _currentTranscript.value = session.fullTranscript
        _currentNotes.value = session.notes
        _finalSummary.value = session.finalSummary
        transcriptSinceLastNote = ""
    }

    fun exportArchives(context: Context) {
        viewModelScope.launch {
            try {
                val sessions = allSessions.first()
                val json = gson.toJson(sessions)
                
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, json)
                    type = "text/plain"
                }
                
                val shareIntent = Intent.createChooser(sendIntent, "Export DnD Scribe Archives")
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Export failed", e)
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
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Import failed", e)
            }
        }
    }
}
