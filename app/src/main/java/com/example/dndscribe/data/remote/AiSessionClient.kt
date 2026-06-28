package com.example.dndscribe.data.remote

import com.example.dndscribe.data.repository.AppConfig
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

object AiSessionClient {
    fun validateBaseUrl(baseUrl: String, allowInsecureHttp: Boolean): Boolean {
        if (baseUrl.isBlank()) return false

        val trimmedUrl = baseUrl.trim()
        if (!allowInsecureHttp && trimmedUrl.startsWith("http://", ignoreCase = true)) {
            return false
        }

        return trimmedUrl.toHttpUrlOrNull() != null
    }

    suspend fun transcribeChunk(file: File, config: AppConfig): String? {
        val whisperUrl = if (config.syncApiSettings) config.llmUrl else config.whisperUrl
        val whisperKey = if (config.syncApiSettings) config.llmApiKey else config.whisperApiKey
        val endpoint = buildEndpoint(whisperUrl, "/v1/audio/transcriptions", config.allowInsecureHttp) ?: return null

        val requestFile = file.asRequestBody("audio/webm".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
        val model = config.whisperModel.toRequestBody("text/plain".toMediaTypeOrNull())
        val format = "json".toRequestBody("text/plain".toMediaTypeOrNull())
        val auth = whisperKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

        return RetrofitClient.whisperApi.transcribe(endpoint, auth, body, model, format).text.trim()
    }

    suspend fun generateNote(config: AppConfig, transcript: String, previousNotes: String? = null): String? {
        val content = if (!previousNotes.isNullOrBlank()) {
            "Previous running notes:\n\n$previousNotes\n\nNew transcript excerpt:\n\n$transcript"
        } else {
            "Transcript excerpt:\n\n$transcript"
        }
        return getCompletion(config, config.notesPrompt, content)
    }

    suspend fun generateFinal(config: AppConfig, notes: String): String? {
        val content = "Full session notes:\n\n$notes"
        return getCompletion(config, config.finalPrompt, content)
    }

    suspend fun synthesizeSpeech(config: AppConfig, text: String): Pair<ByteArray, String?>? {
        val ttsUrl = if (config.useLlmUrlForTts) config.llmUrl else config.ttsUrl
        val ttsKey = if (config.useLlmUrlForTts) config.llmApiKey else config.ttsApiKey
        val endpoint = buildEndpoint(ttsUrl, "/v1/audio/speech", config.allowInsecureHttp) ?: return null
        if (endpoint.isBlank()) return null
        val auth = ttsKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
        val response = RetrofitClient.ttsApi.synthesizeSpeech(
            endpoint, auth,
            TtsRequest(model = config.ttsModel, input = text, voice = config.ttsVoice)
        )
        return Pair(response.bytes(), response.contentType()?.toString())
    }

    private suspend fun getCompletion(config: AppConfig, prompt: String, content: String): String? {
        val endpoint = buildEndpoint(config.llmUrl, "/v1/chat/completions", config.allowInsecureHttp) ?: return null
        val auth = config.llmApiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

        val response = RetrofitClient.llmApi.getCompletion(
            endpoint,
            auth,
            LlmRequest(
                model = config.llmModel,
                messages = listOf(
                    LlmMessage("system", prompt),
                    LlmMessage("user", content)
                )
            )
        )

        return response.choices.firstOrNull()?.message?.content?.trim()
    }

    private fun buildEndpoint(baseUrl: String, path: String, allowInsecureHttp: Boolean): String? {
        if (!validateBaseUrl(baseUrl, allowInsecureHttp)) return null
        return baseUrl.trim().trimEnd('/') + path
    }
}
