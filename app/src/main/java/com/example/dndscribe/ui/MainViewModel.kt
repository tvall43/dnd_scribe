package com.example.dndscribe.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
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
import com.example.dndscribe.data.repository.CloudSyncRepository
import com.example.dndscribe.data.repository.SessionRepository
import com.example.dndscribe.data.repository.SettingsRepository
import com.example.dndscribe.recording.ActiveSessionState
import com.example.dndscribe.recording.RecordingService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepository = SessionRepository(AppDatabase.getDatabase(application).sessionDao())
    private val settingsRepository = SettingsRepository(application)
    private val activeSessionRepository = ActiveSessionRepository(application)
    private val cloudSyncRepository = CloudSyncRepository()
    private val gson = Gson()

    private val _config = MutableStateFlow(AppConfig())
    val config = _config.asStateFlow()

    val currentTranscript = ActiveSessionState.currentTranscript
    val currentNotes = ActiveSessionState.currentNotes
    val finalSummary = ActiveSessionState.finalSummary
    val isRecording = ActiveSessionState.isRecording
    val isUpdatingNotes = ActiveSessionState.isUpdatingNotes
    val isGeneratingFinal = ActiveSessionState.isGeneratingFinal

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()

    private var ttsMediaPlayer: MediaPlayer? = null
    private var ttsAudioTrack: AudioTrack? = null
    private var ttsAudioFile: File? = null
    @Volatile private var ttsStopped = false

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
                val note = AiSessionClient.generateNote(
                    cfg,
                    currentTranscript.value,
                    previousNotes = extractPreviousNotesContext(currentNotes.value, cfg)
                )
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
            val name = "Session - " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val session = SessionEntity(
                name = name,
                fullTranscript = currentTranscript.value,
                notes = currentNotes.value,
                finalSummary = finalSummary.value
            )
            val insertedId = sessionRepository.insertSession(session)
            syncSessionToCloud(session.copy(id = insertedId))
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
        if (isRecording.value) return
        ActiveSessionState.updateTranscript(newTranscript)
        viewModelScope.launch {
            persistActiveSessionContent()
        }
    }

    fun updateFinalSummary(newSummary: String) {
        if (isRecording.value) return
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
                exportDir.listFiles()?.forEach { existingFile ->
                    if (existingFile.isFile) {
                        existingFile.delete()
                    }
                }
                val fileName = "dnd-scribe-archives.json"
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

    fun exportSettings(context: Context) {
        viewModelScope.launch {
            try {
                val exportDir = File(context.cacheDir, "exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                exportDir.listFiles()?.forEach { existingFile ->
                    if (existingFile.isFile) {
                        existingFile.delete()
                    }
                }

                val fileName = "dnd-scribe-settings.json"
                val exportFile = File(exportDir, fileName)
                exportFile.writeText(gson.toJson(_config.value))
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

                val shareIntent = Intent.createChooser(sendIntent, "Backup DnD Scribe Settings")
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Settings export failed", e)
                Toast.makeText(context, "Settings backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importSettings(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json.isNullOrBlank()) {
                    Toast.makeText(context, "Settings file was empty", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val importedConfig = gson.fromJson(json, AppConfig::class.java)
                updateConfig(importedConfig)
                Toast.makeText(context, "Settings restored", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Settings import failed", e)
                Toast.makeText(context, "Settings restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun speakSummary(text: String) {
        if (text.isBlank()) {
            Toast.makeText(getApplication(), "Nothing to speak", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = _config.value
        val ttsUrl = if (cfg.useLlmUrlForTts) cfg.llmUrl else cfg.ttsUrl
        if (!AiSessionClient.validateBaseUrl(ttsUrl, cfg.allowInsecureHttp)) {
            Toast.makeText(getApplication(), "Set a valid TTS URL. Enable insecure HTTP if you need http:// endpoints.", Toast.LENGTH_LONG).show()
            return
        }
        _isSpeaking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = AiSessionClient.synthesizeSpeech(cfg, text) ?: return@launch
                val (audioBytes, contentType) = result
                if (audioBytes.isEmpty()) {
                    Log.w("MainViewModel", "TTS returned empty audio")
                    withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "TTS returned empty audio", Toast.LENGTH_SHORT).show() }
                    cleanupTts()
                    return@launch
                }
                Log.d("MainViewModel", "TTS response: contentType=$contentType size=${audioBytes.size} firstBytes=${audioBytes.take(8).joinToString("") { "%02x".format(it) }}")
                val app = getApplication<Application>()
                if (audioBytes.size >= 12 && audioBytes[0] == 0x52.toByte() && audioBytes[1] == 0x49.toByte() && audioBytes[2] == 0x46.toByte() && audioBytes[3] == 0x46.toByte()) {
                    withContext(Dispatchers.IO) {
                        playWavDirect(audioBytes, app)
                    }
                } else {
                    val ext = when {
                        audioBytes.size >= 4 && audioBytes[0] == 0x4F.toByte() && audioBytes[1] == 0x67.toByte() && audioBytes[2] == 0x67.toByte() && audioBytes[3] == 0x53.toByte() -> "ogg"
                        audioBytes.size >= 4 && audioBytes[0] == 0x66.toByte() && audioBytes[1] == 0x4C.toByte() && audioBytes[2] == 0x61.toByte() && audioBytes[3] == 0x43.toByte() -> "flac"
                        audioBytes.size >= 3 && audioBytes[0] == 0x49.toByte() && audioBytes[1] == 0x44.toByte() && audioBytes[2] == 0x33.toByte() -> "mp3"
                        contentType?.startsWith("audio/") == true -> contentType.substringAfter("audio/").substringBefore(";").substringBefore("x-").let { if (it.isBlank()) "wav" else it }
                        else -> "wav"
                    }
                    val tempFile = File(app.cacheDir, "tts_playback.$ext")
                    if (tempFile.exists()) tempFile.delete()
                    tempFile.writeBytes(audioBytes)
                    ttsAudioFile = tempFile
                    withContext(Dispatchers.Main) {
                        val player = MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            setOnPreparedListener {
                                start()
                                setOnCompletionListener { cleanupTts() }
                            }
                            setOnErrorListener { _, what, extra ->
                                Log.e("MainViewModel", "MediaPlayer error: what=$what extra=$extra ext=$ext contentType=$contentType size=${audioBytes.size}")
                                Toast.makeText(app, "TTS playback error ($what/$extra)", Toast.LENGTH_SHORT).show()
                                cleanupTts()
                                true
                            }
                        }
                        ttsMediaPlayer = player
                        player.prepareAsync()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "TTS failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "TTS failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                cleanupTts()
            }
        }
    }

    private suspend fun playWavDirect(audioBytes: ByteArray, app: Application) {
        try {
            var offset = 12
            var sampleRate = 22050
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = 0
            var dataSize = 0
            while (offset + 8 <= audioBytes.size) {
                val chunkId = String(audioBytes, offset, 4, Charsets.US_ASCII)
                val rawSize = ((audioBytes[offset + 7].toInt() and 0xFF) shl 24) or
                    ((audioBytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((audioBytes[offset + 5].toInt() and 0xFF) shl 8) or
                    (audioBytes[offset + 4].toInt() and 0xFF)
                var chunkSize = rawSize
                if (rawSize == -1) chunkSize = audioBytes.size - offset - 8
                when (chunkId) {
                    "fmt " -> {
                        val formatTag = ((audioBytes[offset + 9].toInt() and 0xFF) shl 8) or (audioBytes[offset + 8].toInt() and 0xFF)
                        if (formatTag != 1) {
                            Log.e("MainViewModel", "Unsupported WAV format: $formatTag (only PCM=1 supported)")
                            withContext(Dispatchers.Main) { Toast.makeText(app, "Unsupported WAV format ($formatTag)", Toast.LENGTH_SHORT).show() }
                            cleanupTts()
                            return
                        }
                        channels = ((audioBytes[offset + 11].toInt() and 0xFF) shl 8) or (audioBytes[offset + 10].toInt() and 0xFF)
                        sampleRate = ((audioBytes[offset + 15].toInt() and 0xFF) shl 24) or
                            ((audioBytes[offset + 14].toInt() and 0xFF) shl 16) or
                            ((audioBytes[offset + 13].toInt() and 0xFF) shl 8) or
                            (audioBytes[offset + 12].toInt() and 0xFF)
                        bitsPerSample = ((audioBytes[offset + 23].toInt() and 0xFF) shl 8) or (audioBytes[offset + 22].toInt() and 0xFF)
                    }
                    "data" -> {
                        dataOffset = offset + 8
                        dataSize = minOf(chunkSize, audioBytes.size - dataOffset)
                    }
                }
                offset += 8 + chunkSize
                if (offset >= audioBytes.size) break
            }
            if (dataSize <= 0) {
                Log.w("MainViewModel", "No data chunk found in WAV")
                withContext(Dispatchers.Main) { Toast.makeText(app, "No audio data in WAV", Toast.LENGTH_SHORT).show() }
                cleanupTts()
                return
            }
            val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = when (bitsPerSample) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf == AudioTrack.ERROR) {
                Log.e("MainViewModel", "Invalid audio params: sampleRate=$sampleRate channels=$channels bits=$bitsPerSample")
                withContext(Dispatchers.Main) { Toast.makeText(app, "Unsupported audio format ($sampleRate/$channels/$bitsPerSample)", Toast.LENGTH_SHORT).show() }
                cleanupTts()
                return
            }
            Log.d("MainViewModel", "WAV parsed: sampleRate=$sampleRate channels=$channels bits=$bitsPerSample dataSize=$dataSize minBuf=$minBuf")
            val frameSize = channels * (bitsPerSample / 8)
            val totalFrames = dataSize / frameSize
            val durationMs = (totalFrames * 1000L) / sampleRate
            _isSpeaking.value = true
            ttsMediaPlayer = null
            ttsAudioFile = null
            ttsStopped = false
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(minBuf.coerceAtLeast(65536))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("MainViewModel", "AudioTrack failed to initialize (stream mode)")
                track.release()
                withContext(Dispatchers.Main) { Toast.makeText(app, "AudioTrack init failed", Toast.LENGTH_SHORT).show() }
                cleanupTts()
                return
            }
            ttsAudioTrack = track
            track.play()
            var written = 0
            while (written < dataSize) {
                if (ttsStopped) break
                val end = minOf(written + 8192, dataSize)
                track.write(audioBytes, dataOffset + written, end - written)
                written = end
            }
            if (!ttsStopped) {
                delay(durationMs + 200)
            }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.release()
            cleanupTts()
        } catch (e: Exception) {
            Log.e("MainViewModel", "WAV direct playback failed", e)
            withContext(Dispatchers.Main) { Toast.makeText(app, "TTS playback failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            cleanupTts()
        }
    }

    fun stopSpeaking() {
        cleanupTts()
    }

    private fun cleanupTts() {
        ttsStopped = true
        ttsAudioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                pause()
                flush()
                stop()
            }
            release()
        }
        ttsAudioTrack = null
        ttsMediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        ttsMediaPlayer = null
        ttsAudioFile?.delete()
        ttsAudioFile = null
        _isSpeaking.value = false
    }

    override fun onCleared() {
        super.onCleared()
        cleanupTts()
    }

    fun importArchives(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json != null) {
                    val type = object : TypeToken<List<SessionEntity>>() {}.type
                    val importedSessions: List<SessionEntity> = gson.fromJson(json, type)
                    val existingSessions = allSessions.firstOrNull().orEmpty()
                    val existingKeys = existingSessions.mapTo(mutableSetOf()) { session ->
                        listOf(session.name, session.date.toString(), session.fullTranscript, session.notes, session.finalSummary)
                    }
                    var insertedCount = 0
                    importedSessions.forEach { session ->
                        val sessionKey = listOf(
                            session.name,
                            session.date.toString(),
                            session.fullTranscript,
                            session.notes,
                            session.finalSummary
                        )
                        if (existingKeys.add(sessionKey)) {
                            sessionRepository.insertSession(session.copy(id = 0))
                            insertedCount += 1
                        }
                    }
                    Toast.makeText(context, "Imported $insertedCount new sessions", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Import failed", e)
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun syncNow(context: Context) {
        viewModelScope.launch {
            try {
                val cfg = _config.value
                if (!cfg.cloudBackupEnabled) {
                    Toast.makeText(context, "Cloud backup is off", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val sessions = allSessions.firstOrNull().orEmpty()
                cloudSyncRepository.syncSessions(cfg, sessions)
                if (currentTranscript.value.isNotBlank() || currentNotes.value.isNotBlank() || finalSummary.value.isNotBlank()) {
                    val snapshot = activeSessionRepository.snapshotFlow.first()
                    cloudSyncRepository.syncActiveSession(
                        cfg,
                        currentTranscript.value,
                        currentNotes.value,
                        finalSummary.value,
                        snapshot.sessionStartedAt
                    )
                    activeSessionRepository.updateCloudSyncTime(System.currentTimeMillis())
                }
                Toast.makeText(context, "Cloud sync complete", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Cloud sync failed", e)
                Toast.makeText(context, "Cloud sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun pullFromCloud(context: Context) {
        viewModelScope.launch {
            try {
                val cfg = _config.value
                if (!cfg.cloudBackupEnabled) {
                    Toast.makeText(context, "Cloud backup is off", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val remoteSessions = cloudSyncRepository.fetchSessions(cfg)
                val localSessions = allSessions.firstOrNull().orEmpty()
                val existingKeys = localSessions.mapTo(mutableSetOf()) { session ->
                    listOf(session.name, session.date.toString(), session.fullTranscript, session.notes, session.finalSummary)
                }

                var insertedCount = 0
                remoteSessions.forEach { remote ->
                    val sessionKey = listOf(
                        remote.name,
                        remote.date.toString(),
                        remote.fullTranscript,
                        remote.notes,
                        remote.finalSummary
                    )
                    if (existingKeys.add(sessionKey)) {
                        sessionRepository.insertSession(
                            SessionEntity(
                                name = remote.name,
                                date = remote.date,
                                fullTranscript = remote.fullTranscript,
                                notes = remote.notes,
                                finalSummary = remote.finalSummary
                            )
                        )
                        insertedCount += 1
                    }
                }

                Toast.makeText(context, "Pulled $insertedCount sessions from server", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Cloud pull failed", e)
                Toast.makeText(context, "Cloud pull failed: ${e.message}", Toast.LENGTH_LONG).show()
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

    private suspend fun syncSessionToCloud(session: SessionEntity) {
        val cfg = _config.value
        if (!cfg.cloudBackupEnabled) return

        try {
            cloudSyncRepository.syncSession(cfg, session)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Cloud session sync failed", e)
            Toast.makeText(getApplication(), "Saved locally, but cloud sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        try {
            cloudSyncRepository.deleteActiveSession(cfg)
        } catch (e: Exception) {
            Log.w("MainViewModel", "Cloud active-session cleanup failed", e)
        }
    }

    private fun extractPreviousNotesContext(notes: String, config: AppConfig): String? {
        if (!config.includePreviousNotesContext) return null
        val trimmedNotes = notes.trim()
        if (trimmedNotes.isBlank()) return null

        val count = config.previousNotesContextCount.coerceAtLeast(1)
        val entries = trimmedNotes.split("\n\n").filter { it.isNotBlank() }
        return entries.takeLast(count).joinToString("\n\n").takeIf { it.isNotBlank() }
    }
}
