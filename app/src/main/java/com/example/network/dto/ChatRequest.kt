package com.example.network.dto

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerializedName("max_tokens")
    val maxTokens: Int = 3000,
    val temperature: Double = 0.0,
    @SerializedName("top_p")
    val topP: Double = 0.85,
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double = 0.2,
    val stream: Boolean = false,
    @SerializedName("extra_body")
    val extraBody: Map<String, Any>? = null
)

data class ChatMessage(
    val role: String,
    // 接受 String 或 List<ContentItem>：
    //  - 带图片的 user 消息用 List<ContentItem>（图片 + 文本分项）
    //  - 纯文本的 system/assistant 消息用 String（与 OpenAI / autoglm-phone 训练分布一致，
    //    避免模型看到数组结构的 assistant 历史后把自己的输出也写成 [{'type':...}]）
    val content: Any
)

data class ContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)