package com.example.network

import com.example.network.dto.ChatRequest
import com.example.network.dto.ChatResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

// 大模型网络请求接口
interface AutoGLMApi {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatRequest
    ): Response<ChatResponse>
}