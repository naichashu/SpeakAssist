package com.example.speech

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

/**
 * Vosk 语音识别管理器
 * 负责加载模型、创建 Recognizer、处理识别结果
 *
 * 使用方式：
 * 1. init() 加载模型（耗时，约1-3秒）
 * 2. createRecognizer() 创建识别器（每次识别任务创建一个）
 * 3. acceptAudio() 持续输入音频数据
 * 4. result() / partialResult() 获取识别结果
 * 5. destroy() 释放资源
 */
class VoskRecognizerManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskRecognizerManager"

        // 模型名称（与 assets 目录下文件夹名一致）
        const val MODEL_NAME = "vosk-model-small-cn-0.22"

        // Vosk 模型采样率（必须匹配模型训练时的采样率）
        const val SAMPLE_RATE = 16000f

        // 模型路径（app 私有目录）
        private fun getModelPath(context: Context): String {
            return "${context.filesDir.absolutePath}/$MODEL_NAME"
        }
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = model != null

    /**
     * 获取模型路径
     */
    fun getModelPath(): String = getModelPath(context)

    /**
     * 加载模型
     * @return true 加载成功，false 加载失败
     */
    fun loadModel(): Boolean {
        // 如果已经加载，直接返回
        model?.let { return true }

        val modelPath = getModelPath(context)
        val modelDir = File(modelPath)

        // 检查模型是否存在
        if (!modelDir.exists()) {
            Log.w(TAG, "模型不存在: $modelPath，尝试从 assets 复制")
            try {
                copyModelFromAssets()
            } catch (e: Exception) {
                Log.e(TAG, "从 assets 复制模型失败", e)
                return false
            }
        }

        // 加载模型
        return try {
            model = Model(modelPath)
            Log.i(TAG, "Vosk 模型加载成功: $modelPath")
            true
        } catch (e: IOException) {
            Log.e(TAG, "模型加载失败: $modelPath", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "模型加载异常", e)
            false
        }
    }

    /**
     * 从 assets 目录复制模型到 app 私有目录
     * 首次调用时执行，后续直接使用已复制的模型
     */
    private fun copyModelFromAssets() {
        val assetManager = context.assets
        val destDir = File(getModelPath(context))

        Log.d(TAG, "开始复制模型从 assets 到: ${destDir.absolutePath}")

        // 复制整个模型目录
        copyDirectory(assetManager, MODEL_NAME, destDir)

        Log.i(TAG, "模型复制完成")
    }

    /**
     * 递归复制 assets 目录下的内容到目标目录
     */
    private fun copyDirectory(
        assetManager: android.content.res.AssetManager,
        srcPath: String,
        destDir: File
    ) {
        destDir.mkdirs()

        try {
            val files = assetManager.list(srcPath) ?: return

            if (files.isEmpty()) {
                // 顶层目录，尝试列出内容
                return
            }

            for (fileName in files) {
                val src = "$srcPath/$fileName"
                val destFile = File(destDir, fileName)

                // 检查是否是目录
                val assetList = assetManager.list(src)
                if (!assetList.isNullOrEmpty()) {
                    // 是目录，递归复制
                    copyDirectory(assetManager, src, destFile)
                } else {
                    // 是文件，直接复制
                    try {
                        assetManager.open(src).use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.v(TAG, "复制文件: $src -> ${destFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "复制文件失败: $src", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制目录失败: $srcPath", e)
        }
    }

    /**
     * 创建 Recognizer（每次识别任务创建一个）
     * @return Recognizer 实例，创建失败返回 null
     */
    fun createRecognizer(): Recognizer? {
        val m = model ?: run {
            Log.e(TAG, "模型未加载，无法创建 Recognizer")
            return null
        }

        // 如果已有 recognizer，先关闭
        recognizer?.close()

        return try {
            Recognizer(m, SAMPLE_RATE).also {
                recognizer = it
                Log.d(TAG, "Recognizer 创建成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 Recognizer 失败", e)
            null
        }
    }

    /**
     * 处理音频数据（16bit PCM，16kHz 单声道）
     * @param audioData 音频数据字节数组
     * @return 如果 isFinal=true（识别完成）返回识别结果字符串，否则返回 null
     *         如果还在识别中，可调用 getPartialResult() 获取部分结果
     */
    fun acceptAudio(audioData: ByteArray): String? {
        val rec = recognizer ?: return null

        return try {
            val isFinal = rec.acceptWaveForm(audioData, audioData.size)
            if (isFinal) {
                extractResultText(rec.result, "text")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            null
        }
    }

    /**
     * 获取部分识别结果（还在识别中时调用）
     * @return 部分识别结果字符串
     */
    fun getPartialResult(): String? {
        return try {
            val partial = recognizer?.partialResult ?: return null
            extractResultText(partial, "partial")
        } catch (e: Exception) {
            Log.e(TAG, "获取部分结果失败", e)
            null
        }
    }

    /**
     * 获取最终识别结果
     * @return 识别结果字符串，如果没有结果返回空字符串
     */
    fun getFinalResult(): String {
        return try {
            val result = recognizer?.result ?: return ""
            extractResultText(result, "text") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "获取最终结果失败", e)
            ""
        }
    }

    private fun extractResultText(rawResult: Any?, key: String): String? {
        if (rawResult == null) return null

        if (rawResult is String) {
            return parseJsonField(rawResult, key)
        }

        return try {
            val methodName = when (key) {
                "partial" -> "getPartial"
                else -> "getText"
            }
            val method = rawResult.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }
            val value = method?.invoke(rawResult) as? String
            if (!value.isNullOrBlank()) {
                value.trim()
            } else {
                parseJsonField(rawResult.toString(), key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 $key 结果失败，改用字符串解析: ${e.message}")
            parseJsonField(rawResult.toString(), key)
        }
    }

    private fun parseJsonField(rawJson: String, key: String): String? {
        val normalized = rawJson.trim()
        if (normalized.isEmpty()) return null

        return try {
            val value = JSONObject(normalized).optString(key).trim()
            if (value.isEmpty()) null else value
        } catch (e: Exception) {
            Log.w(TAG, "解析 JSON 字段失败(key=$key): ${e.message}")
            if (normalized.startsWith("{") && normalized.endsWith("}")) {
                null
            } else {
                normalized
            }
        }
    }

    /**
     * 重置识别器状态
     * 用于开始新的识别任务
     */
    fun reset() {
        try {
            recognizer?.reset()
            Log.d(TAG, "Recognizer 已重置")
        } catch (e: Exception) {
            Log.e(TAG, "重置 Recognizer 失败", e)
        }
    }

    /**
     * 释放资源
     * 调用后不能再使用此对象
     */
    fun destroy() {
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 Recognizer 失败", e)
        }
        recognizer = null

        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 Model 失败", e)
        }
        model = null

        Log.d(TAG, "VoskRecognizerManager 已销毁")
    }
}
