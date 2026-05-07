package com.example.speakassist

import android.content.Context
import android.net.Uri
import com.example.data.SettingsPrefs
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * JSON export/import for API credentials.
 */
object ApiConfigBackup {

    private const val CURRENT_VERSION = 2
    private const val MIN_SUPPORTED_VERSION = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class BackupDto(
        @SerializedName("version") val version: Int? = null,
        @SerializedName("zhipu_api_key") val zhipuApiKey: String = "",
        @SerializedName("baidu_api_key") val baiduApiKey: String = "",
        @SerializedName("baidu_secret_key") val baiduSecretKey: String = "",
        @SerializedName("model_base_url") val modelBaseUrl: String = "",
        @SerializedName("model_name") val modelName: String = ""
    )

    class BackupFormatException(message: String) : Exception(message)
    class BackupVersionException(message: String) : Exception(message)
    class BackupEmptyException(message: String) : Exception(message)

    suspend fun exportToUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val dto = BackupDto(
            version = CURRENT_VERSION,
            zhipuApiKey = SettingsPrefs.zhipuApiKey(context).first(),
            baiduApiKey = SettingsPrefs.baiduApiKey(context).first(),
            baiduSecretKey = SettingsPrefs.baiduSecretKey(context).first(),
            modelBaseUrl = SettingsPrefs.modelBaseUrl(context).first(),
            modelName = SettingsPrefs.modelName(context).first()
        )
        val json = gson.toJson(dto)
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open output stream")
    }

    suspend fun importFromUri(context: Context, uri: Uri): BackupDto = withContext(Dispatchers.IO) {
        val dto = context.contentResolver.openInputStream(uri)?.use { ins ->
            try {
                gson.fromJson(InputStreamReader(ins, Charsets.UTF_8), BackupDto::class.java)
            } catch (e: JsonSyntaxException) {
                throw BackupFormatException("Invalid JSON: ${e.message}")
            }
        } ?: throw IllegalStateException("Cannot open input stream")

        if (dto.version == null) {
            throw BackupFormatException("Not a SpeakAssist config export")
        }
        if (dto.version !in MIN_SUPPORTED_VERSION..CURRENT_VERSION) {
            throw BackupVersionException(
                "Unsupported version ${dto.version}, expected $MIN_SUPPORTED_VERSION..$CURRENT_VERSION"
            )
        }

        val hasZhipu = dto.zhipuApiKey.isNotBlank()
        val hasBaidu = dto.baiduApiKey.isNotBlank() && dto.baiduSecretKey.isNotBlank()
        val hasModelBaseUrl = dto.modelBaseUrl.isNotBlank()
        val hasModelName = dto.modelName.isNotBlank()
        if (!hasZhipu && !hasBaidu && !hasModelBaseUrl && !hasModelName) {
            throw BackupEmptyException("Config file contains no API settings")
        }

        if (hasZhipu) {
            SettingsPrefs.setZhipuApiKey(context, dto.zhipuApiKey)
        }
        if (hasBaidu) {
            SettingsPrefs.setBaiduCredentials(context, dto.baiduApiKey, dto.baiduSecretKey)
        }
        if (hasModelBaseUrl) {
            SettingsPrefs.setModelBaseUrl(context, dto.modelBaseUrl)
        }
        if (hasModelName) {
            SettingsPrefs.setModelName(context, dto.modelName)
        }
        dto
    }
}
