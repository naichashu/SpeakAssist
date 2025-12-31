package com.example.bean

// AI接口响应体（简化版，根据实际返回格式调整）
data class AIResponse(
    val choices: List<Choice>,
    val usage: Usage
)

data class Choice(
    val message: Message
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)