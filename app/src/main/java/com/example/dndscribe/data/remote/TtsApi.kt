package com.example.dndscribe.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface TtsApi {
    @POST
    suspend fun synthesizeSpeech(
        @Url url: String,
        @Header("Authorization") authHeader: String?,
        @Body request: TtsRequest
    ): ResponseBody
}

data class TtsRequest(
    val model: String,
    val input: String,
    val voice: String,
    @SerializedName("response_format") val responseFormat: String = "wav"
)
