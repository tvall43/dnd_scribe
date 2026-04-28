package com.example.dndscribe.recording

import com.example.dndscribe.data.local.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveSessionState {
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

    fun setRecording(isRecording: Boolean) {
        _isRecording.value = isRecording
    }

    fun appendTranscript(text: String) {
        if (text.isBlank()) return
        _currentTranscript.value += (if (_currentTranscript.value.isEmpty()) "" else " ") + text
    }

    fun appendNote(note: String) {
        if (note.isBlank()) return
        _currentNotes.value += (if (_currentNotes.value.isEmpty()) "" else "\n\n") + note
    }

    fun updateNotes(notes: String) {
        _currentNotes.value = notes
    }

    fun updateTranscript(transcript: String) {
        _currentTranscript.value = transcript
    }

    fun updateFinalSummary(summary: String) {
        _finalSummary.value = summary
    }

    fun setFinalSummary(summary: String) {
        _finalSummary.value = summary
    }

    fun setUpdatingNotes(isUpdating: Boolean) {
        _isUpdatingNotes.value = isUpdating
    }

    fun setGeneratingFinal(isGenerating: Boolean) {
        _isGeneratingFinal.value = isGenerating
    }

    fun clear() {
        _currentTranscript.value = ""
        _currentNotes.value = ""
        _finalSummary.value = ""
    }

    fun load(session: SessionEntity) {
        _currentTranscript.value = session.fullTranscript
        _currentNotes.value = session.notes
        _finalSummary.value = session.finalSummary
    }

    fun restore(transcript: String, notes: String, finalSummary: String) {
        _currentTranscript.value = transcript
        _currentNotes.value = notes
        _finalSummary.value = finalSummary
    }
}
