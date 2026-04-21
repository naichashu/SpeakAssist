package com.example.speech

import android.content.Context
import com.example.data.SettingsPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class BaiduSpeechCredentials(
    val apiKey: String,
    val secretKey: String
) {
    val isValid: Boolean get() = apiKey.isNotBlank() && secretKey.isNotBlank()
}

/**
 * 百度语音凭据来源：用户在 API 配置页填写，持久化到 DataStore。
 * 早期版本曾硬编码作者个人 key，本版本全部移除。
 */
object BaiduSpeechConfig {

    /** 观察凭据变化的 Flow，任一项改动都会 emit 新值 */
    fun credentialsFlow(context: Context): Flow<BaiduSpeechCredentials> {
        return combine(
            SettingsPrefs.baiduApiKey(context),
            SettingsPrefs.baiduSecretKey(context)
        ) { apiKey, secretKey -> BaiduSpeechCredentials(apiKey, secretKey) }
    }
}
