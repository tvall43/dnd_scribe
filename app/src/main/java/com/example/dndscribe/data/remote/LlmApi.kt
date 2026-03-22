package com.example.dndscribe.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface LlmApi {
    @POST
    suspend fun getCompletion(
        @Url url: String,
        @Header("Authorization") authHeader: String?,
        @Body request: LlmRequest
    ): LlmResponse
}

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val max_tokens: Int = 1000
)

data class LlmMessage(
    val role: String,
    val content: String
)

data class LlmResponse(
    val choices: List<LlmChoice>
)

data class LlmChoice(
    val message: LlmMessage
)
