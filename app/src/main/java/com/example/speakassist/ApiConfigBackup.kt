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
 * API 配置的 JSON 导出/导入。
 *
 * 单独拆文件的理由：IO + JSON 与 Activity 解耦，Activity 只管 UI 回调；
 * 未来如果出第二个入口（命令行、ADB 工具）也能直接复用。
 */
object ApiConfigBackup {

    private const val CURRENT_VERSION = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class BackupDto(
        @SerializedName("version") val version: Int = 0,
        @SerializedName("zhipu_api_key") val zhipuApiKey: String = "",
        @SerializedName("baidu_api_key") val baiduApiKey: String = "",
        @SerializedName("baidu_secret_key") val baiduSecretKey: String = ""
    )

    class BackupFormatException(message: String) : Exception(message)
    class BackupVersionException(message: String) : Exception(message)

    /**
     * 导出 DataStore 当前持久化值到指定 uri。
     * 读持久化值而非 Activity 的 EditText：避免用户改一半未保存就导出错版本。
     */
    suspend fun exportToUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val dto = BackupDto(
            version = CURRENT_VERSION,
            zhipuApiKey = SettingsPrefs.zhipuApiKey(context).first(),
            baiduApiKey = SettingsPrefs.baiduApiKey(context).first(),
            baiduSecretKey = SettingsPrefs.baiduSecretKey(context).first()
        )
        val json = gson.toJson(dto)
        // "wt" 强制 truncate，避免覆盖旧文件时尾部残留
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法打开输出流")
    }

    /**
     * 从 uri 读 JSON 并写入 DataStore，返回 DTO 让 UI 刷新输入框。
     */
    suspend fun importFromUri(context: Context, uri: Uri): BackupDto = withContext(Dispatchers.IO) {
        val dto = context.contentResolver.openInputStream(uri)?.use { ins ->
            try {
                gson.fromJson(InputStreamReader(ins, Charsets.UTF_8), BackupDto::class.java)
            } catch (e: JsonSyntaxException) {
                throw BackupFormatException("JSON 解析失败：${e.message}")
            }
        } ?: throw IllegalStateException("无法打开输入流")

        // version 默认 0 → JSON 里缺字段或完全不是我们的格式
        if (dto.version == 0) {
            throw BackupFormatException("不是 SpeakAssist 导出的配置文件")
        }
        if (dto.version != CURRENT_VERSION) {
            throw BackupVersionException("版本 ${dto.version} 不兼容，期望 $CURRENT_VERSION")
        }

        SettingsPrefs.setZhipuApiKey(context, dto.zhipuApiKey)
        SettingsPrefs.setBaiduCredentials(context, dto.baiduApiKey, dto.baiduSecretKey)
        dto
    }
}
