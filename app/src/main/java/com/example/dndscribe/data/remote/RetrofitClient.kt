package com.example.dndscribe.data.remote

import com.example.dndscribe.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private fun client(readTimeoutSeconds: Long) = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl("http://localhost/") // Placeholder, URLs are provided per request
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val whisperApi: WhisperApi = retrofit(client(60)).create(WhisperApi::class.java)
    val llmApi: LlmApi = retrofit(client(240)).create(LlmApi::class.java)
    val cloudApi: CloudApi = retrofit(client(60)).create(CloudApi::class.java)
}
