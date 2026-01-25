package com.example.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.network.dto.ChatMessage
import com.example.network.dto.ChatRequest
import com.example.network.dto.ContentItem
import com.example.network.dto.ImageUrl
import com.example.speakassist.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser.parseString
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class ModelResponse(
    val thinking: String,
    val action: String
)

class ModelClient(
    private val context: Context,
    baseUrl: String,
    private val apiKey: String
) {
    private val api: AutoGLMApi

    companion object {
        const val TAG = "ModelClient"
    }

    init {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // 创建OkHttpClient实例
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header(
                        "Authorization",
                        if (apiKey.isBlank() || apiKey == "EMPTY")
                            "Bearer EMPTY" else "Bearer $apiKey"
                    )
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        // 验证URL格式
        val validatedBaseUrl = checkAndFixUrl(baseUrl)

        val retrofit = Retrofit.Builder()
            .baseUrl(validatedBaseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        Log.d(TAG, "创建的API接口实例")

        api = retrofit.create(AutoGLMApi::class.java)
    }

    /**
     * 验证并修复URL格式
     */
    private fun checkAndFixUrl(url: String): String {
        var fixedUrl = url.trim()
        // 如果URL没有协议头，添加默认的https协议
        if (!fixedUrl.startsWith("http://") && !fixedUrl.startsWith("https://")) {
            fixedUrl = "https://$fixedUrl"
        }
        return fixedUrl
    }

    /**
     * 发送请求并解析响应
     */
    suspend fun request(
        messages: List<ChatMessage>,
        modelName: String
    ): ModelResponse {
        val request = ChatRequest(
            model = modelName,
            messages = messages,
            maxTokens = 3000,
            temperature = 0.0,
            topP = 0.85,
            frequencyPenalty = 0.2,
            stream = false
        )

        val response = api.chatCompletion(request)
        Log.d(TAG, "响应内容: ${response}")

        // 检查响应是否成功
        if (response.isSuccessful && response.body() != null) {
            val responseBody = response.body()!!
            // 获取响应结果
            val context = responseBody.choices.firstOrNull()?.message?.content ?: ""
            return parseResponse(context)
        } else {
            throw Exception("请求失败: ${response.code()} ${response.message()}")
        }
    }

    /**
     * 解析响应内容
     */
    private fun parseResponse(content: String): ModelResponse {
        Log.d(TAG, "解析前响应内容: ${content.take(500)}")

        var thinking = ""
        var action = ""

        // 处理特殊格式
        if (content.contains("finish(message=")) {
            val parts = content.split("finish(message=", limit = 2)
            thinking = parts[0].trim()
            action = "finish(message=" + parts[1]
        } else if (content.contains("do(action=")) {
            val parts = content.split("do(action=", limit = 2)
            thinking = parts[0].trim()
            action = "do(action=" + parts[1]
        } else if (content.contains("<answer>")) {
            val parts = content.split("<answer>", limit = 2)
            thinking = parts[0]
                .replace("<think>", "")
                .replace("</think>", "")
                .replace("<redacted_reasoning>", "")
                .replace("</redacted_reasoning>", "")
                .trim()
            action = parts[1].replace("</answer>", "").trim()
        } else {
            action = content.trim()
        }

        // 尝试从内容中提取JSON
        if (!action.startsWith("{") && !action.startsWith("do(") && !action.startsWith("finish(")) {
            val funcMatch = Regex("""(do|finish)\s*\([^)]+\)""", RegexOption.IGNORE_CASE).find(content)
            if (funcMatch != null) {
                action = funcMatch.value
            } else {
                val extractedJson = extractJsonFromContent(content)
                if (extractedJson.isNotEmpty()) {
                    action = extractedJson
                }
            }
        }
        Log.d(TAG, "解析后响应内容:thinking=${thinking.take(80)}, action=${action.take(80)}")

        return ModelResponse(thinking = thinking, action = action)
    }

    /**
     * 从内容中提取JSON对象
     */
    private fun extractJsonFromContent(content: String): String {
        var startIndex = -1
        var braceCount = 0
        val candidates = mutableListOf<String>()

        Log.d(TAG, "从内容中提取的JSON对象")

        // 遍历字符串，寻找JSON对象
        for (i in content.indices) {
            when (content[i]) {
                '{' -> {
                    if (startIndex == -1) startIndex = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        val candidate = content.substring(startIndex, i + 1)
                        try {
                            parseString(candidate)
                            candidates.add(candidate)
                        } catch (e: Exception) {}
                        startIndex = -1
                    }
                }
            }
        }
        return candidates.firstOrNull() ?: ""
    }

    /**
     * 创建系统消息
     */
    fun createSystemMessage(): ChatMessage {
        val systemPrompt = buildSystemPrompt()
        return ChatMessage(
            role = "system",
            content = listOf(ContentItem(type = "text", text = systemPrompt))
        )
    }

    /**
     * 获取系统prompt
     */
    private fun buildSystemPrompt(): String {
        val template = context.getString(R.string.system_prompt_template)
        return String.format(template, java.time.LocalDate.now())
    }

    /**
     * 创建用户消息（第一次调用，包含原始任务）
     */
    fun createUserMessage(userPrompt: String, screenshot: Bitmap?, currentApp: String?, quality: Int = 80): ChatMessage {
        return createMessage(userPrompt, screenshot, currentApp, quality)
    }

    /**
     * 创建屏幕信息消息（后续调用，只包含屏幕信息）
     */
    fun createScreenInfoMessage(screenshot: Bitmap?, currentApp: String?, quality: Int = 80): ChatMessage {
        return createMessage("** Screen Info **", screenshot, currentApp, quality)
    }

    /**
     * 创建消息的通用基础方法
     */
    private fun createMessage(
        text: String,
        screenshot: Bitmap?,
        currentApp: String?,
        quality: Int = 80
    ): ChatMessage {
        val userContent = mutableListOf<ContentItem>()
        val screenInfoJson = buildScreenInfo(currentApp)
        val fullText = if (text.isEmpty()) screenInfoJson else "$text\n\n$screenInfoJson"

        // 先放图片，再放文本
        screenshot?.let { bitmap ->
            val base64Image = bitmapToBase64(bitmap, quality)
            userContent.add(
                ContentItem(
                    type = "image_url",
                    imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image")
                )
            )
        }

        userContent.add(ContentItem(type = "text", text = fullText))
        return ChatMessage(role = "user", content = userContent)
    }

    fun removeImagesFromMessage(message: ChatMessage): ChatMessage {
        val textOnlyContent = message.content.filter { it.type == "text" }
        return ChatMessage(
            role = message.role,
            content = textOnlyContent
        )
    }

    /**
    * 构建屏幕信息（使用 JsonObject 确保转义安全）
    */
    private fun buildScreenInfo(currentApp: String?): String {
        val json = JsonObject()
        json.addProperty("current_app", currentApp ?: "Unknown")
        return json.toString()
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        }
    }

    /**
     * 创建助手消息（添加到上下文）
     */
    fun createAssistantMessage(thinking: String, action: String): ChatMessage {
        val content = "<think>$thinking</think><answer>$action</answer>"
        return ChatMessage(
            role = "assistant",
            content = listOf(ContentItem(type = "text", text = content))
        )
    }

    /**
     * 确保URL字符串以斜杠结尾
     */
    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }
}