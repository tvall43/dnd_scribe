package com.example.dndscribe.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface CloudApi {
    @POST
    suspend fun syncSessions(
        @Url url: String,
        @Header("Authorization") authHeader: String?,
        @Body request: CloudSyncRequest
    ): CloudSyncResponse

    @POST
    suspend fun upsertSession(
        @Url url: String,
        @Header("Authorization") authHeader: String?,
        @Body session: CloudSessionRequest
    ): CloudSessionResponse

    @DELETE
    suspend fun deleteSession(
        @Url url: String,
        @Header("Authorization") authHeader: String?
    ): CloudDeleteResponse
}

data class CloudSyncRequest(
    val sessions: List<CloudSessionRequest>
)

data class CloudSyncResponse(
    val upserted: Int,
    val sessions: List<CloudSessionResponse>
)

data class CloudSessionRequest(
    val id: String,
    @SerializedName("device_id") val deviceId: String?,
    val name: String,
    val date: Long,
    @SerializedName("full_transcript") val fullTranscript: String,
    val notes: String,
    @SerializedName("final_summary") val finalSummary: String,
    @SerializedName("updated_at") val updatedAt: Long? = null
)

data class CloudSessionResponse(
    val id: String,
    @SerializedName("device_id") val deviceId: String?,
    val name: String,
    val date: Long,
    @SerializedName("full_transcript") val fullTranscript: String,
    val notes: String,
    @SerializedName("final_summary") val finalSummary: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("updated_at") val updatedAt: Long
)

data class CloudDeleteResponse(
    val status: String,
    val id: String
)
