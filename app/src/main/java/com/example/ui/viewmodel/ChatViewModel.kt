package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.network.ModelClient
import com.example.network.dto.ChatMessage
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private var modelClient: ModelClient?  = null

    // 维护对话上下文（消息历史，仅在运行时有效，包含图片等大数据）
    private val messageContext = mutableListOf<ChatMessage>()
    companion object  {
        const val TAG = "ChatViewModel"
    }

    init {
        viewModelScope.launch{
            Log.d(TAG, "初始化模型客户端")
            val baseUrl = "https://open.bigmodel.cn/api/paas/v4"
            val apiKey = "3400b28973dbc4f62558def1f2053b96.kiUwPwhwyLvULvWt"
            modelClient = ModelClient(getApplication(), baseUrl, apiKey)
        }
    }

    suspend fun executeTaskLoop(userPrompt: String, modelName: String) {
        Log.d( TAG, "开始执行任务")
        var stepCount = 0
        val maxSteps = 50
        val compressionLevel = 80
        while (stepCount < maxSteps) {
            val stepStartTime = System.currentTimeMillis()

            val client = modelClient ?: return
            Log.d(TAG, "执行步骤 $stepCount")

            if (stepCount == 0) {
                // 第一次调用：添加系统消息和用户消息（包含原始任务）
                if (messageContext.isEmpty()) {
                    messageContext.add(client.createSystemMessage())
                }
                messageContext.add(client.createUserMessage(userPrompt, null, null, compressionLevel))
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

            Log.d(TAG, "模型响应: thinking=${response.thinking.take(80)}, action=${response.action.take(80)}")

            // 添加助手消息到上下文
            messageContext.add(client.createAssistantMessage(response.thinking, response.action))

            // 如果模型返回的是 finish，则直接结束，不再执行动作
            val isFinishAction = response.action.contains("\"_metadata\":\"finish\"") ||
                    response.action.contains("\"_metadata\": \"finish\"") ||
                    response.action.lowercase().contains("finish(")
            stepCount++
        }
    }
}