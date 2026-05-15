package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.input.TextInputMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsPrefs {

    private val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
    private val TEXT_INPUT_MODE = stringPreferencesKey("text_input_mode")
    private val VOICE_WAKE_ENABLED = booleanPreferencesKey("voice_wake_enabled")
    private val DIAGNOSTICS_UPLOAD_ENABLED = booleanPreferencesKey("diagnostics_upload_enabled")
    private val ZHIPU_API_KEY = stringPreferencesKey("zhipu_api_key")
    private val BAIDU_API_KEY = stringPreferencesKey("baidu_api_key")
    private val BAIDU_SECRET_KEY = stringPreferencesKey("baidu_secret_key")
    private val MODEL_BASE_URL = stringPreferencesKey("model_base_url")
    private val MODEL_NAME = stringPreferencesKey("model_name")

    const val DEFAULT_MODEL_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val DEFAULT_MODEL_NAME = "autoglm-phone"

    fun floatingWindowEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] ?: false
        }
    }

    fun textInputMode(context: Context): Flow<TextInputMode> {
        return context.dataStore.data.map { prefs ->
            TextInputMode.fromStorageValue(prefs[TEXT_INPUT_MODE])
        }
    }

    /**
     * 语音唤醒词开关
     */
    fun voiceWakeEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[VOICE_WAKE_ENABLED] ?: false
        }
    }

    suspend fun setFloatingWindowEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] = enabled
        }
    }

    suspend fun setTextInputMode(context: Context, mode: TextInputMode) {
        context.dataStore.edit { prefs ->
            prefs[TEXT_INPUT_MODE] = mode.storageValue
        }
    }

    suspend fun setVoiceWakeEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_WAKE_ENABLED] = enabled
        }
    }

    /**
     * 诊断日志上传开关（默认 false）
     */
    fun diagnosticsUploadEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[DIAGNOSTICS_UPLOAD_ENABLED] ?: false
        }
    }

    suspend fun setDiagnosticsUploadEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DIAGNOSTICS_UPLOAD_ENABLED] = enabled
        }
    }

    /** 智谱 AutoGLM 的 API Key */
    fun zhipuApiKey(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs -> prefs[ZHIPU_API_KEY].orEmpty() }
    }

    /** 百度语音识别的 API Key */
    fun baiduApiKey(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs -> prefs[BAIDU_API_KEY].orEmpty() }
    }

    /** 百度语音识别的 Secret Key */
    fun baiduSecretKey(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs -> prefs[BAIDU_SECRET_KEY].orEmpty() }
    }

    suspend fun setZhipuApiKey(context: Context, value: String) {
        context.dataStore.edit { prefs -> prefs[ZHIPU_API_KEY] = value.trim() }
    }

    suspend fun setBaiduCredentials(context: Context, apiKey: String, secretKey: String) {
        context.dataStore.edit { prefs ->
            prefs[BAIDU_API_KEY] = apiKey.trim()
            prefs[BAIDU_SECRET_KEY] = secretKey.trim()
        }
    }

    fun modelBaseUrl(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[MODEL_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_BASE_URL
        }
    }

    fun modelName(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[MODEL_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_NAME
        }
    }

    suspend fun setModelBaseUrl(context: Context, value: String) {
        context.dataStore.edit { prefs -> prefs[MODEL_BASE_URL] = value.trim() }
    }

    suspend fun setModelName(context: Context, value: String) {
        context.dataStore.edit { prefs -> prefs[MODEL_NAME] = value.trim() }
    }
}
