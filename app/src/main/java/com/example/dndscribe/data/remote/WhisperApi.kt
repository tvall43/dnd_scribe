package com.example.dndscribe.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

interface WhisperApi {
    @Multipart
    @POST
    suspend fun transcribe(
        @Url url: String,
        @Header("Authorization") authHeader: String?,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody
    ): TranscriptionResponse
}

data class TranscriptionResponse(
    val text: String
)
