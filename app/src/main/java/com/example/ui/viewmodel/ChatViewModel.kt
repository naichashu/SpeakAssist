package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.network.ModelClient
import com.example.network.dto.ChatMessage
import com.example.network.dto.ContentItem
import com.example.register.ActionExecutor
import com.example.register.ActionResult
import com.example.register.AppRegister
import com.example.service.MyAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private var modelClient: ModelClient? = null
    private var actionExecutor: ActionExecutor? = null

    // 维护对话上下文（消息历史，仅在运行时有效，包含图片等大数据）
    private val messageContext = mutableListOf<ChatMessage>()

    companion object {
        const val TAG = "ChatViewModel"
    }

    init {
        viewModelScope.launch {
            Log.d(TAG, "初始化模型客户端")
            val baseUrl = "https://open.bigmodel.cn/api/paas/v4"
            val apiKey = "3400b28973dbc4f62558def1f2053b96.kiUwPwhwyLvULvWt"
            modelClient = ModelClient(getApplication(), baseUrl, apiKey)

            MyAccessibilityService.getInstance()?.let { service ->
                actionExecutor = ActionExecutor(service)
            }
        }
    }

    suspend fun executeTaskLoop(userPrompt: String, modelName: String) {
        AppRegister.initialize(getApplication()) // 初始化注册，以确保最新的映射配置被加载
        Log.d(TAG, "开始执行任务")
        var stepCount = 0
        val maxSteps = 50
        var errorSteps = 0
        val compressionLevel = 80
        while (stepCount < maxSteps) {
            val client = modelClient ?: return
            Log.d(TAG, "执行步骤 $stepCount")

            if (stepCount == 0) {
                // 第一次调用：添加系统消息和用户消息（包含原始任务）
                if (messageContext.isEmpty()) {
                    messageContext.add(client.createSystemMessage())
                }
                messageContext.add(
                    client.createUserMessage(
                        userPrompt,
                        null,
                        null,
                        compressionLevel
                    )
                )
            } else {
                // 后续调用：只添加屏幕信息
                messageContext.add(client.createScreenInfoMessage(null, null, compressionLevel))
            }

            // 调用模型（使用消息上下文）
            val messagesList: List<ChatMessage> = messageContext.toList()
            val response = client.request(
                messages = messagesList,
                modelName = modelName
            )

            Log.d(
                TAG,
                "模型响应: thinking=${response.thinking.take(80)}, action=${response.action.take(80)}"
            )

            // 添加助手消息到上下文
            messageContext.add(client.createAssistantMessage(response.thinking, response.action))

            // 如果模型返回的是 finish，则直接结束，不再执行动作
            val isFinishAction = response.action.contains("\"_metadata\":\"finish\"") ||
                    response.action.contains("\"_metadata\": \"finish\"") ||
                    response.action.lowercase().contains("finish(")

            val displayMetrics = getApplication<Application>().resources.displayMetrics

            //  执行动作
            val result = actionExecutor?.execute(
                response.action,
                null ?: displayMetrics.widthPixels,
                null ?: displayMetrics.heightPixels
            ) ?: ActionResult(false, "ActionExecutor is null")

            Log .d(TAG, "执行动作结果: ${result.success}: ${result.message}")

            if (isFinishAction) {
                actionExecutor?.bringAppToForeground()
                Log.d(TAG, "任务完成(finish动作)")
                return
            }

            // 检查是否完成
            val isFinished = result.message != null && (result.message.contains("完成") ||
                    result.message.contains("finish"))

            if (isFinished) {
                // 任务完成
                val completionMessage = result.message
                // 确保返回应用
                actionExecutor?.bringAppToForeground()
                Log.d(TAG, "任务完成: $completionMessage")
                return
            }

            // 错误处理
            if  (!result.success) {
                val errorText = buildString {
                    appendLine("上一步你的输出错误，${result.message}")
                    appendLine("请严格按照系统提示中的格式，仅输出以下两种之一：")
                    appendLine("1. do(action=\"...\", ...)")
                    appendLine("2. finish(message=\"...\")")
                    appendLine("不要输出列表、自然语言说明或其他非规范格式。")
                    appendLine()
                    append("你上一次的输出是：")
                    append(response.action.take(200))
                }
                messageContext.add(
                    ChatMessage(
                        role = "user",
                        content = listOf(
                            ContentItem(
                                type = "text",
                                text = errorText
                            )
                        )
                    )
                )
                Log.d(TAG, "已向模型反馈格式错误，要求按规范重新输出动作")

                errorSteps++

                if ( errorSteps > 5) {
                    // 失败也尝试返回应用
                    actionExecutor?.bringAppToForeground()
                    Log.e(TAG, "重试超过上限，结束流程: ${result.message}")
                    return
                }

                // 重试：继续下一轮循环
                delay(900)
                continue
            } else {
                // 成功-重试计数
                errorSteps = 0
            }

            // 等待界面稳定
            delay(900)
            stepCount++
        }
        actionExecutor?.bringAppToForeground()
        Log.w("ChatViewModel", "达到最大步数限制")
    }
}