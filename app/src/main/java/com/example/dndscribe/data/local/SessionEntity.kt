package com.example.dndscribe.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val date: Long = System.currentTimeMillis(),
    val fullTranscript: String = "",
    val notes: String = "",
    val finalSummary: String = ""
)
