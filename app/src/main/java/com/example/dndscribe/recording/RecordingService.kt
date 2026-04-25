package com.example.dndscribe.recording

import android.R as AndroidR
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.dndscribe.data.remote.AiSessionClient
import com.example.dndscribe.data.repository.ActiveSessionRepository
import com.example.dndscribe.data.repository.ActiveSessionSnapshot
import com.example.dndscribe.data.repository.AppConfig
import com.example.dndscribe.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val activeSessionRepository by lazy { ActiveSessionRepository(applicationContext) }

    private var currentConfig = AppConfig()
    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var transcriptSinceLastNote = ""
    private var lastNoteTime = System.currentTimeMillis()
    private var lastFinalTime = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            settingsRepository.configFlow.collect { currentConfig = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> serviceScope.launch {
                currentConfig = settingsRepository.configFlow.first()
                restorePersistedSession()
                startRecording()
            }
            ACTION_STOP -> serviceScope.launch { stopRecordingAndShutdown() }
            ACTION_GENERATE_NOTE -> serviceScope.launch { generateNote() }
            ACTION_GENERATE_FINAL -> serviceScope.launch { generateFinal() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaRecorder?.release()
        mediaRecorder = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startRecording() {
        if (ActiveSessionState.isRecording.value) return

        if (!AiSessionClient.validateBaseUrl(resolveWhisperBaseUrl(currentConfig), currentConfig.allowInsecureHttp)) {
            showToast("Set a valid Whisper URL. Enable insecure HTTP if you need http:// endpoints.")
            stopSelf()
            return
        }

        startForegroundWithNotification()

        val audioFile = File(cacheDir, "recording.webm")
        try {
            mediaRecorder = createRecorder(audioFile)
            ActiveSessionState.setRecording(true)
            if (lastNoteTime == 0L) {
                lastNoteTime = System.currentTimeMillis()
            }
            if (lastFinalTime == 0L) {
                lastFinalTime = System.currentTimeMillis()
            }
            persistActiveSession()
            startRecordingLoop(audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            ActiveSessionState.setRecording(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            showToast("Failed to start recording: ${e.message}")
        }
    }

    private fun startRecordingLoop(audioFile: File) {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            while (ActiveSessionState.isRecording.value) {
                delay(currentConfig.chunkSec * 1000L)
                if (!ActiveSessionState.isRecording.value) break

                rotateAndTranscribe(audioFile)

                val now = System.currentTimeMillis()
                if (now - lastNoteTime > currentConfig.notesIntervalMin * 60 * 1000L) {
                    generateNote()
                }
                if (now - lastFinalTime > currentConfig.finalIntervalMin * 60 * 1000L) {
                    generateFinal()
                }
            }
        }
    }

    private suspend fun stopRecordingAndShutdown() {
        if (!ActiveSessionState.isRecording.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        ActiveSessionState.setRecording(false)
        recordingJob?.cancel()
        recordingJob = null

        val activeFile = File(cacheDir, "recording.webm")
        val stoppedFile = stopCurrentRecorder(activeFile)
        if (stoppedFile?.exists() == true && stoppedFile.length() > 0L) {
            transcribeFile(stoppedFile)
        }

        persistActiveSession()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun rotateAndTranscribe(audioFile: File) {
        try {
            val transcribeFile = stopCurrentRecorder(audioFile)
            if (ActiveSessionState.isRecording.value) {
                mediaRecorder = createRecorder(audioFile)
            }
            if (transcribeFile?.exists() == true) {
                transcribeFile(transcribeFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in rotateAndTranscribe", e)
        }
    }

    private suspend fun transcribeFile(file: File) {
        try {
            val text = AiSessionClient.transcribeChunk(file, currentConfig).orEmpty()
            if (text.isNotBlank()) {
                ActiveSessionState.appendTranscript(text)
                transcriptSinceLastNote += (if (transcriptSinceLastNote.isEmpty()) "" else " ") + text
                persistActiveSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
        } finally {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private suspend fun generateNote() {
        if (ActiveSessionState.isUpdatingNotes.value || transcriptSinceLastNote.isBlank()) return
        if (!AiSessionClient.validateBaseUrl(currentConfig.llmUrl, currentConfig.allowInsecureHttp)) return

        ActiveSessionState.setUpdatingNotes(true)
        try {
            val note = AiSessionClient.generateNote(currentConfig, transcriptSinceLastNote)
            if (!note.isNullOrBlank()) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                ActiveSessionState.appendNote("-- $time --\n$note")
                transcriptSinceLastNote = ""
                lastNoteTime = System.currentTimeMillis()
                persistActiveSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Note generation failed", e)
        } finally {
            ActiveSessionState.setUpdatingNotes(false)
        }
    }

    private suspend fun generateFinal() {
        if (ActiveSessionState.isGeneratingFinal.value || ActiveSessionState.currentNotes.value.isBlank()) return
        if (!AiSessionClient.validateBaseUrl(currentConfig.llmUrl, currentConfig.allowInsecureHttp)) return

        ActiveSessionState.setGeneratingFinal(true)
        try {
            val summary = AiSessionClient.generateFinal(currentConfig, ActiveSessionState.currentNotes.value)
            if (!summary.isNullOrBlank()) {
                ActiveSessionState.setFinalSummary(summary)
                lastFinalTime = System.currentTimeMillis()
                persistActiveSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Final summary failed", e)
        } finally {
            ActiveSessionState.setGeneratingFinal(false)
        }
    }

    private fun createRecorder(audioFile: File): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.WEBM)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setOutputFile(audioFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun stopCurrentRecorder(audioFile: File): File? {
        val recorder = mediaRecorder ?: return null
        mediaRecorder = null

        return try {
            recorder.stop()
            recorder.release()

            val transcribeFile = File(cacheDir, "chunk_${System.currentTimeMillis()}.webm")
            if (audioFile.exists() && audioFile.renameTo(transcribeFile)) transcribeFile else null
        } catch (e: Exception) {
            recorder.release()
            Log.e(TAG, "Failed stopping recorder", e)
            null
        }
    }

    private fun resolveWhisperBaseUrl(config: AppConfig): String {
        return if (config.syncApiSettings) config.llmUrl else config.whisperUrl
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(AndroidR.drawable.ic_btn_speak_now)
            .setContentTitle("DnD Scribe recording")
            .setContentText("Transcribing your session in the background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(AndroidR.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DnD Scribe Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun persistActiveSession() {
        activeSessionRepository.updateAll(
            ActiveSessionSnapshot(
                transcript = ActiveSessionState.currentTranscript.value,
                notes = ActiveSessionState.currentNotes.value,
                finalSummary = ActiveSessionState.finalSummary.value,
                transcriptSinceLastNote = transcriptSinceLastNote,
                lastNoteTime = lastNoteTime,
                lastFinalTime = lastFinalTime
            )
        )
    }

    private suspend fun restorePersistedSession() {
        val snapshot = activeSessionRepository.snapshotFlow.first()
        ActiveSessionState.restore(snapshot.transcript, snapshot.notes, snapshot.finalSummary)
        transcriptSinceLastNote = snapshot.transcriptSinceLastNote
        lastNoteTime = snapshot.lastNoteTime
        lastFinalTime = snapshot.lastFinalTime
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.example.dndscribe.action.START_RECORDING"
        const val ACTION_STOP = "com.example.dndscribe.action.STOP_RECORDING"
        const val ACTION_GENERATE_NOTE = "com.example.dndscribe.action.GENERATE_NOTE"
        const val ACTION_GENERATE_FINAL = "com.example.dndscribe.action.GENERATE_FINAL"

        fun startIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)

        fun generateNoteIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_GENERATE_NOTE)

        fun generateFinalIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_GENERATE_FINAL)
    }
}
