package com.example.bean

// AI接口请求体（对应autoglm-phone模型的请求格式）
data class AIRequest(
    val model: String,
    val messages: List<Message>,
    val api_key: String
)

// 消息体
data class Message(
    val role: String, // 角色：user（用户）、assistant（助手）
    val content: String // 消息内容
)
