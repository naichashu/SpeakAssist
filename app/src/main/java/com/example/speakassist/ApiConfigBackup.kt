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
        // nullable Int：null = 未知版本（不是我们的导出文件或格式损坏），
        // 与故意写 version=0 的歧义文件区分开来
        @SerializedName("version") val version: Int? = null,
        @SerializedName("zhipu_api_key") val zhipuApiKey: String = "",
        @SerializedName("baidu_api_key") val baiduApiKey: String = "",
        @SerializedName("baidu_secret_key") val baiduSecretKey: String = ""
    )

    class BackupFormatException(message: String) : Exception(message)
    class BackupVersionException(message: String) : Exception(message)
    /** 文件合法但三个 key 字段全空——拒绝导入以防把现有配置覆盖掉 */
    class BackupEmptyException(message: String) : Exception(message)

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

        // null = JSON 里缺字段或完全不是我们的格式
        if (dto.version == null) {
            throw BackupFormatException("不是 SpeakAssist 导出的配置文件")
        }
        if (dto.version != CURRENT_VERSION) {
            throw BackupVersionException("版本 ${dto.version} 不兼容，期望 $CURRENT_VERSION")
        }

        // 防误导入空文件覆盖现有配置：
        // - 三字段全空 → 直接拒绝（用户大概率误传了空模板/损坏文件）
        // - 部分非空 → 仅写入有值的字段，已有的不动
        //   智谱独立判；百度俩 key 必须配对，任一缺则整对不写
        //   （与 ApiConfigActivity.save 的成对约束保持一致）
        val hasZhipu = dto.zhipuApiKey.isNotBlank()
        val hasBaidu = dto.baiduApiKey.isNotBlank() && dto.baiduSecretKey.isNotBlank()
        if (!hasZhipu && !hasBaidu) {
            throw BackupEmptyException("文件未填写任何 API Key")
        }
        if (hasZhipu) {
            SettingsPrefs.setZhipuApiKey(context, dto.zhipuApiKey)
        }
        if (hasBaidu) {
            SettingsPrefs.setBaiduCredentials(context, dto.baiduApiKey, dto.baiduSecretKey)
        }
        dto
    }
}
