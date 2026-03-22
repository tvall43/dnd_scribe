package com.example.dndscribe.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/") // Placeholder, URLs are provided per request
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val whisperApi: WhisperApi = retrofit.create(WhisperApi::class.java)
    val llmApi: LlmApi = retrofit.create(LlmApi::class.java)
}
