package com.example.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.diagnostics.AppLog
import com.example.network.dto.ChatMessage
import com.example.network.dto.ChatRequest
import com.example.network.dto.ContentItem
import com.example.network.dto.ImageUrl
import com.example.speakassist.R
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser.parseString
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val okHttpClient: OkHttpClient
    private val requestBaseUrl: String
    private val provider: Provider
    private var currentCall: okhttp3.Call? = null

    companion object {
        const val TAG = "ModelClient"
    }

    private enum class Provider {
        ZHIPU,
        MINIMAX,
        OPENAI_COMPATIBLE
    }

    init {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            AppLog.d("OkHttp", message)
        }.apply {
            level = if (com.example.speakassist.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }

        okHttpClient = OkHttpClient.Builder()
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
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        requestBaseUrl = checkAndFixUrl(baseUrl).removeChatCompletionsSuffix().ensureTrailingSlash()
        provider = detectProvider(requestBaseUrl)
        AppLog.d(TAG, "ModelClient 初始化，baseUrl=$requestBaseUrl, provider=$provider")
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

    private fun String.removeChatCompletionsSuffix(): String {
        return removeSuffix("/chat/completions")
            .removeSuffix("/chat/completions/")
            .removeSuffix("chat/completions")
            .removeSuffix("chat/completions/")
    }

    private fun detectProvider(baseUrl: String): Provider {
        val lower = baseUrl.lowercase(Locale.US)
        return when {
            "minimax" in lower -> Provider.MINIMAX
            "bigmodel.cn" in lower -> Provider.ZHIPU
            else -> Provider.OPENAI_COMPATIBLE
        }
    }

    /**
     * 发送请求并解析响应。内部用 suspendCancellableCoroutine 包装 OkHttp Call，
     * 这样协程被取消时 HTTP 请求也会被真正中断。
     */
    suspend fun request(
        messages: List<ChatMessage>,
        modelName: String
    ): ModelResponse = suspendCancellableCoroutine { continuation ->
        try {
            val requestMessages = prepareMessagesForProvider(messages)
            val httpRequest = buildChatRequest(modelName, requestMessages)
            val jsonBody = Gson().toJson(httpRequest)
            val requestBody: RequestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val requestUrl = "${requestBaseUrl}chat/completions"
            AppLog.d(
                TAG,
                "发送模型请求: provider=$provider, url=$requestUrl, model=$modelName, messages=${requestMessages.size}"
            )

            val httpRequestBuilder = Request.Builder()
                .url(requestUrl)
                .header("Authorization", if (apiKey.isBlank() || apiKey == "EMPTY") "Bearer EMPTY" else "Bearer $apiKey")
                .post(requestBody)

            currentCall = okHttpClient.newCall(httpRequestBuilder.build())
            currentCall?.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    AppLog.e(TAG, "请求失败: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.peekBody(1200).string()
                            AppLog.e(
                                TAG,
                                "请求失败详情: code=${response.code}, message=${response.message}, provider=$provider, body=$errorBody"
                            )
                            AppLog.e(TAG, "请求失败: ${response.code} ${response.message}")
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    java.io.IOException("请求失败: ${response.code} ${response.message}")
                                )
                            }
                            return
                        }
                        val bodyStr = response.body?.string() ?: ""
                        AppLog.d(TAG, "响应内容: $bodyStr")

                        val json = parseString(bodyStr).asJsonObject
                        val content = json
                            .getAsJsonArray("choices")
                            ?.firstOrNull()
                            ?.asJsonObject
                            ?.getAsJsonObject("message")
                            ?.get("content")
                            ?.asString ?: ""

                        if (continuation.isActive) {
                            continuation.resume(parseResponse(content))
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "解析响应异常: ${e.message}")
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    } finally {
                        response.close()
                    }
                }
            })

            // 协程被取消时中断 HTTP 请求
            continuation.invokeOnCancellation {
                currentCall?.cancel()
                AppLog.d(TAG, "HTTP 请求已被取消")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "构建请求异常: ${e.message}")
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 取消当前正在进行的 HTTP 请求。
     */
    fun cancelCurrentRequest() {
        currentCall?.cancel()
        AppLog.d(TAG, "用户取消请求")
    }

    /**
     * 解析响应内容
     */
    private fun buildChatRequest(modelName: String, messages: List<ChatMessage>): ChatRequest {
        return when (provider) {
            Provider.MINIMAX -> ChatRequest(
                model = modelName,
                messages = messages,
                maxTokens = null,
                maxCompletionTokens = 2048,
                temperature = 0.01,
                topP = 0.85,
                frequencyPenalty = null,
                stream = false
            )

            else -> ChatRequest(
                model = modelName,
                messages = messages,
                maxTokens = 3000,
                temperature = 0.0,
                topP = 0.85,
                frequencyPenalty = 0.2,
                stream = false
            )
        }
    }

    private fun prepareMessagesForProvider(messages: List<ChatMessage>): List<ChatMessage> {
        if (provider != Provider.MINIMAX) return messages
        return messages.map { message ->
            if (message.role == "user") {
                ChatMessage(
                    role = message.role,
                    content = flattenTextContent(message.content)
                )
            } else {
                message
            }
        }
    }

    private fun flattenTextContent(content: Any): String {
        return when (content) {
            is String -> content
            is List<*> -> content
                .filterIsInstance<ContentItem>()
                .mapNotNull { it.text }
                .joinToString("\n")
                .trim()
            else -> content.toString()
        }
    }

    private fun parseResponse(content: String): ModelResponse {
        AppLog.d(TAG, "解析前响应内容: ${content.take(500)}")

        var thinking = ""
        var action = ""

        // <answer> 是 system prompt 要求的规范格式，优先匹配；
        // 否则再退化到 finish()/do() 的函数调用形式兜底。
        if (content.contains("<answer>")) {
            val parts = content.split("<answer>", limit = 2)
            thinking = parts[0].trim()
            action = parts[1].replace("</answer>", "").trim()
        } else if (content.contains("finish(message=")) {
            val parts = content.split("finish(message=", limit = 2)
            thinking = parts[0].trim()
            action = "finish(message=" + parts[1]
        } else if (content.contains("do(action=")) {
            val parts = content.split("do(action=", limit = 2)
            thinking = parts[0].trim()
            action = "do(action=" + parts[1]
        } else {
            action = content.trim()
        }

        // 尝试从内容中提取JSON
        if (!action.startsWith("{") && !action.startsWith("do(") && !action.startsWith("finish(")) {
            val funcMatch =
                Regex("""(do|finish)\s*\([^)]+\)""", RegexOption.IGNORE_CASE).find(content)
            if (funcMatch != null) {
                action = funcMatch.value
            } else {
                val extractedJson = extractJsonFromContent(content)
                if (extractedJson.isNotEmpty()) {
                    action = extractedJson
                }
            }
        }

        thinking = sanitizeThinking(thinking)

        AppLog.d(TAG, "解析后响应内容:thinking=${thinking.take(80)}, action=${action.take(80)}")

        return ModelResponse(thinking = thinking, action = action)
    }

    /**
     * 清理 thinking：剥离残留标签，并在括号严重不平衡时整段丢弃。
     * 模型偶发会吐 `[{'type':' text', ' text="..."` 这类破碎片段作为"推理"，
     * 若原样写入 messageContext，下一轮模型会把自己破碎的历史当格式模板照抄，
     * 造成级联解析失败。
     */
    private fun sanitizeThinking(raw: String): String {
        if (raw.isBlank()) return ""
        val stripped = raw
            .replace("<think>", "")
            .replace("</think>", "")
            .replace("<answer>", "")
            .replace("</answer>", "")
            .replace("<redacted_reasoning>", "")
            .replace("</redacted_reasoning>", "")
            .trim()
        if (stripped.isEmpty()) return ""

        var paren = 0
        var bracket = 0
        var brace = 0
        for (c in stripped) {
            when (c) {
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                '{' -> brace++
                '}' -> brace--
            }
        }
        if (paren != 0 || bracket != 0 || brace != 0) {
            AppLog.w(TAG, "thinking 含不平衡括号，丢弃以防上下文污染: ${stripped.take(120)}")
            return ""
        }
        return stripped
    }

    /**
     * 从内容中提取JSON对象
     */
    private fun extractJsonFromContent(content: String): String {
        var startIndex = -1
        var braceCount = 0
        val candidates = mutableListOf<String>()

        AppLog.d(TAG, "从内容中提取的JSON对象")

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
                        } catch (e: Exception) {
                        }
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
        // 纯文本 content，与原参考项目一致；autoglm-phone 训练分布里
        // system/assistant 的 content 是字符串，模型看到数组结构时会把自己的
        // 输出也写成 `[{'type':...,'text':...}]` 造成解析失败。
        return ChatMessage(
            role = "system",
            content = systemPrompt
        )
    }

    /**
     * 获取系统prompt
     */
    private fun buildSystemPrompt(): String {
        val template = context.getString(R.string.system_prompt_template)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return String.format(template, today)
    }

    /**
     * 创建用户消息（第一次调用，包含原始任务）
     */
    fun createUserMessage(
        userPrompt: String,
        screenshot: Bitmap?,
        currentApp: String?,
        quality: Int = 80
    ): ChatMessage {
        return createMessage(userPrompt, screenshot, currentApp, quality)
    }

    /**
     * 创建屏幕信息消息（后续调用，只包含屏幕信息）
     */
    fun createScreenInfoMessage(
        screenshot: Bitmap?,
        currentApp: String?,
        quality: Int = 80
    ): ChatMessage {
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
        // 只有数组形态的 content 才可能含图片；字符串 content 原样返回。
        val content = message.content
        if (content !is List<*>) return message
        val textOnlyContent = content.filterIsInstance<ContentItem>().filter { it.type == "text" }
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
     *
     * 关键：content 必须是纯字符串，不能包成 `[{"type":"text","text":...}]`。
     * autoglm-phone 会参照历史 assistant 消息的结构组织自己的输出，若历史用了
     * 数组 content，模型会仿写 `[{'type':' text', ' text="..."})` 导致后续解析全挂。
     */
    fun createAssistantMessage(thinking: String, action: String): ChatMessage {
        val content = "<think>$thinking</think><answer>$action</answer>"
        return ChatMessage(
            role = "assistant",
            content = content
        )
    }

    /**
     * 确保URL字符串以斜杠结尾
     */
    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }
}
