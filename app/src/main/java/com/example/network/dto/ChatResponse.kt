package com.example.network.dto

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    val id: String,
    val choices: List<Choice>, // 对话消息列表
    val usage: Usage // 用量统计（可选，可忽略）
)

// 响应结果核心数据
data class Choice(
    val index: Int,
    val message: ResponseMessage,
    @SerializedName("finish_reason")
    val finishReason: String
)

data class ResponseMessage(
    val role: String,
    val content: String
)

// 用量统计数据（可选，无需额外处理）
data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int, // 输入所消耗的token数
    @SerializedName("completion_tokens")
    val completionTokens: Int, // 响应结果所消耗的token数
    @SerializedName("total_tokens")
    val totalTokens: Int // 总计消耗的token数
)