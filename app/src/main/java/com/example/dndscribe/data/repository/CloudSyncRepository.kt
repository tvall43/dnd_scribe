package com.example.dndscribe.data.repository

import com.example.dndscribe.data.local.SessionEntity
import com.example.dndscribe.data.remote.AiSessionClient
import com.example.dndscribe.data.remote.CloudSessionRequest
import com.example.dndscribe.data.remote.CloudSyncRequest
import com.example.dndscribe.data.remote.RetrofitClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudSyncRepository {
    suspend fun syncSessions(config: AppConfig, sessions: List<SessionEntity>) {
        if (!canSync(config) || sessions.isEmpty()) return

        RetrofitClient.cloudApi.syncSessions(
            buildEndpoint(config, "/sync"),
            authHeader(config),
            CloudSyncRequest(sessions.map { it.toCloudRequest(config) })
        )
    }

    suspend fun syncSession(config: AppConfig, session: SessionEntity, remoteId: String? = null) {
        if (!canSync(config)) return

        RetrofitClient.cloudApi.upsertSession(
            buildEndpoint(config, "/sessions"),
            authHeader(config),
            session.toCloudRequest(config, remoteId)
        )
    }

    suspend fun syncActiveSession(
        config: AppConfig,
        transcript: String,
        notes: String,
        finalSummary: String,
        startedAt: Long
    ) {
        if (!canSync(config)) return
        if (transcript.isBlank() && notes.isBlank() && finalSummary.isBlank()) return

        val date = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        val nameTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(date))
        RetrofitClient.cloudApi.upsertSession(
            buildEndpoint(config, "/sessions"),
            authHeader(config),
            CloudSessionRequest(
                id = activeSessionId(config),
                deviceId = config.cloudDeviceId,
                name = "Active Session - $nameTime",
                date = date,
                fullTranscript = transcript,
                notes = notes,
                finalSummary = finalSummary,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteActiveSession(config: AppConfig) {
        if (!canSync(config)) return

        RetrofitClient.cloudApi.deleteSession(
            buildEndpoint(config, "/sessions/${activeSessionId(config)}"),
            authHeader(config)
        )
    }

    private fun SessionEntity.toCloudRequest(config: AppConfig, remoteId: String? = null): CloudSessionRequest {
        return CloudSessionRequest(
            id = remoteId ?: "session-${id}",
            deviceId = config.cloudDeviceId,
            name = name,
            date = date,
            fullTranscript = fullTranscript,
            notes = notes,
            finalSummary = finalSummary,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun canSync(config: AppConfig): Boolean {
        return config.cloudBackupEnabled && AiSessionClient.validateBaseUrl(config.cloudUrl, config.allowInsecureHttp)
    }

    private fun buildEndpoint(config: AppConfig, path: String): String {
        return config.cloudUrl.trim().trimEnd('/') + path
    }

    private fun authHeader(config: AppConfig): String? {
        return config.cloudApiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    private fun activeSessionId(config: AppConfig): String {
        val deviceId = config.cloudDeviceId.ifBlank { "android" }
        return "active-$deviceId"
    }
}
