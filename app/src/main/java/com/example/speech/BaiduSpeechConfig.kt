package com.example.speech

data class BaiduSpeechCredentials(
    val apiKey: String,
    val secretKey: String
)

object BaiduSpeechConfig {
    fun credentials(): BaiduSpeechCredentials {
        return BaiduSpeechCredentials(
            apiKey = "Xkmx5j1pbR3NvquUMOFnXo5u",
            secretKey = "I56pmB7DrQ1JNwoBMyjVBdJ6CUyIW49x"
        )
    }
}
