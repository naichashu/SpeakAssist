package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.SettingsPrefs
import com.example.data.entity.TaskSession
import com.example.data.entity.TaskStep
import com.example.network.ModelClient
import com.example.network.dto.ChatMessage
import com.example.network.dto.ContentItem
import com.example.register.ActionExecutor
import com.example.register.ActionResult
import com.example.register.AppRegister
import com.example.service.MyAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private var modelClient: ModelClient? = null
    private var actionExecutor: ActionExecutor? = null
    private val db = AppDatabase.getInstance(application)

    // 维护对话上下文（消息历史，仅在运行时有效，包含图片等大数据）
    private val messageContext = mutableListOf<ChatMessage>()

    /**
     * 任务执行结果
     */
    data class TaskResult(
        val success: Boolean,
        val message: String
    )

    companion object {
        const val TAG = "ChatViewModel"

        /**
         * 任务执行状态，供悬浮窗等外部组件观察
         */
        data class TaskExecutionState(
            val isRunning: Boolean = false,
            val taskTitle: String = "",
            val currentStep: Int = 0,
            val currentAction: String = "",
            val isCompleted: Boolean = false,
            val isSuccess: Boolean = false,
            val isCancelled: Boolean = false,
            val resultMessage: String = ""
        )

        private val _executionState = MutableStateFlow(TaskExecutionState())
        val executionState: StateFlow<TaskExecutionState> = _executionState.asStateFlow()

        private val _cancelRequested = MutableStateFlow(false)
        val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()

        fun requestCancel() {
            _cancelRequested.value = true
        }

        fun resetState() {
            _executionState.value = TaskExecutionState()
            _cancelRequested.value = false
        }
    }

    init {
        viewModelScope.launch {
            MyAccessibilityService.getInstance()?.let { service ->
                actionExecutor = ActionExecutor(service)
            }
        }
    }

    suspend fun executeTaskLoop(userPrompt: String, modelName: String): TaskResult {
        Log.d(TAG, "开始执行任务")
        messageContext.clear()

        // 先通知开始执行（必须在任何前置检查之前），
        // 观察者依赖此状态渲染用户消息，否则 early return 会导致 UI 毫无反馈。
        _cancelRequested.value = false
        _executionState.value = TaskExecutionState(
            isRunning = true,
            taskTitle = userPrompt
        )
        // StateFlow 合并多个快速写入，delay 让观察者先收到 running 状态
        delay(50)

        // API Key 未配置：直接失败，引导用户去 API 配置页填写
        val apiKey = SettingsPrefs.zhipuApiKey(getApplication()).first()
        if (apiKey.isBlank()) {
            return failWithoutSession("请先在侧栏「API 配置」填写智谱 AutoGLM API Key")
        }
        // 每次任务都用最新 Key 重建 client，避免用户在配置页改了 Key 后 ViewModel 还用旧的
        modelClient = ModelClient(
            getApplication(),
            "https://open.bigmodel.cn/api/paas/v4",
            apiKey
        )

        AppRegister.initialize(getApplication()) // 初始化注册，以确保最新的映射配置被加载
        val accessibilityService = MyAccessibilityService.getInstance()
            ?: return failWithoutSession("无障碍服务未启用")

        // 创建会话记录
        val sessionId = db.taskSessionDao().insert(
            TaskSession(userCommand = userPrompt, status = "running")
        )

        var stepCount = 0
        val maxSteps = 50
        var errorSteps = 0
        val compressionLevel = 80
        while (stepCount < maxSteps) {
            // 检查取消请求
            if (_cancelRequested.value) {
                Log.d(TAG, "任务被用户取消，中断 HTTP 请求")
                modelClient?.cancelCurrentRequest()
                return finishTask(sessionId, false, "用户手动取消任务", isCancelled = true)
            }

            val client = modelClient ?: return finishTask(sessionId, false, "模型客户端未初始化")
            Log.d(TAG, "执行步骤 $stepCount")

            val currentApp = accessibilityService.currentApp.value
            val myProjectApp = getApplication<Application>().packageName
            val isMyProjectApp = currentApp == myProjectApp
            Log.d(TAG, "当前应用: $currentApp $myProjectApp")

            val screenShot = if (isMyProjectApp) {
                null
            } else {
                accessibilityService.getScreenshotSuspend()
            }
            Log.d(TAG, "获取屏幕截图结果: $screenShot")

            if (screenShot == null && !isMyProjectApp) {
                val androidVersion = android.os.Build.VERSION.SDK_INT
                val errorMessage = if (androidVersion < android.os.Build.VERSION_CODES.R) {
                    "无法获取屏幕截图：需要 Android 11 (API 30) 及以上版本，当前版本: Android ${android.os.Build.VERSION.RELEASE} (API $androidVersion)"
                } else {
                    "无法获取屏幕截图，请确保无障碍服务已启用并授予截图权限。如果已启用，请尝试重启应用。"
                }
                Toast.makeText(getApplication(), errorMessage, Toast.LENGTH_LONG).show()
                return finishTask(sessionId, false, errorMessage)
            }

            if (stepCount == 0) {
                // 第一次调用：添加系统消息和用户消息（包含原始任务）
                if (messageContext.isEmpty()) {
                    messageContext.add(client.createSystemMessage())
                }
                messageContext.add(
                    client.createUserMessage(
                        userPrompt,
                        screenShot,
                        currentApp,
                        compressionLevel
                    )
                )
            } else {
                // 后续调用：只添加屏幕信息
                messageContext.add(
                    client.createScreenInfoMessage(
                        screenShot,
                        currentApp,
                        compressionLevel
                    )
                )
            }

            // 调用模型（使用消息上下文）
            val messagesList: List<ChatMessage> = messageContext.toList()
            val response = try {
                client.request(messages = messagesList, modelName = modelName)
            } catch (e: Exception) {
                // HTTP 请求被取消时，cancelCurrentRequest() 会触发 onFailure，
                // resumeWithException 把 Canceled IOException 抛到这里。
                // 此时若 cancel 标志已设，直接退出循环。
                if (_cancelRequested.value) {
                    Log.d(TAG, "HTTP 请求被取消，结束任务")
                    return finishTask(sessionId, false, "用户手动取消任务", isCancelled = true)
                }
                throw e
            }

            Log.d(
                TAG,
                "模型响应: thinking=${response.thinking.take(80)}, action=${response.action.take(80)}"
            )

            // 添加助手消息到上下文
            messageContext.add(client.createAssistantMessage(response.thinking, response.action))

            // 从上下文中移除最后一条用户消息的图片（节省 token，参考原项目）：在执行动作后，移除图片只保留文本，这样可以节省大量 token
            if (messageContext.size >= 2) {
                val lastUserMessageIndex = messageContext.size - 2
                val lastUserMessage = messageContext[lastUserMessageIndex]
                if (lastUserMessage.role == "user") {
                    // 移除图片，只保留文本
                    messageContext[lastUserMessageIndex] =
                        client.removeImagesFromMessage(lastUserMessage)
                    Log.d(TAG, "已移除最后一条用户消息中的图片")
                }
            }

            // 如果模型返回的是 finish，则直接结束，不再执行动作
            val isFinishAction = response.action.contains("\"_metadata\":\"finish\"") ||
                    response.action.contains("\"_metadata\": \"finish\"") ||
                    response.action.lowercase().contains("finish(")

            val displayMetrics = getApplication<Application>().resources.displayMetrics

            //  执行动作
            val result = actionExecutor?.execute(
                response.action,
                screenShot?.width ?: displayMetrics.widthPixels,
                screenShot?.height ?: displayMetrics.heightPixels
            ) ?: ActionResult(false, "ActionExecutor is null")

            Log.d(TAG, "执行动作结果: ${result.success}: ${result.message}")

            // 更新执行状态供悬浮窗观察
            _executionState.value = _executionState.value.copy(
                currentStep = stepCount + 1,
                currentAction = result.message ?: response.action.take(100)
            )

            // 保存步骤到数据库
            val actionType = extractActionType(response.action)
            val actionDesc = result.message ?: response.action.take(100)
            db.taskStepDao().insert(
                TaskStep(
                    sessionId = sessionId,
                    stepNumber = stepCount + 1,
                    actionType = actionType,
                    actionDescription = actionDesc,
                    aiThinking = response.thinking.takeIf { it.isNotBlank() }
                )
            )

            if (isFinishAction) {
                Log.d(TAG, "任务完成(finish动作)")
                return finishTask(sessionId, true, result.message ?: "任务执行完成")
            }

            // 错误处理
            if (!result.success) {
                // 把刚刚写入历史的畸形 assistant 消息改写成占位符，
                // 避免模型在下一轮对话里把自己错误的输出当成格式模板照抄，引发级联失败。
                if (messageContext.isNotEmpty() && messageContext.last().role == "assistant") {
                    messageContext[messageContext.size - 1] =
                        client.createAssistantMessage("", "[上一轮输出格式错误，已过滤]")
                }

                val errorText = """
                    上一轮响应无法解析为有效动作。请严格按下面格式重新输出当前步骤的操作，禁止输出列表/字典/自然语言：
                    <think>简要理由</think><answer>do(action=..., ...)</answer>
                    或
                    <think>简要理由</think><answer>finish(message=...)</answer>
                """.trimIndent()
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

                if (errorSteps > 4) {
                    Log.e(TAG, "重试超过上限，结束流程: ${result.message}")
                    return finishTask(sessionId, false, "连续错误超过上限: ${result.message}")
                }

                // 重试：继续下一轮循环
                delay(1000)
                continue
            } else {
                // 成功-重试计数
                errorSteps = 0
            }

            val settleDelayMs = result.actionDetail?.waitMs ?: 1000L
            Log.d(TAG, "等待界面稳定: ${settleDelayMs}ms, action=${result.actionDetail?.type}")
            delay(settleDelayMs)
            stepCount++
        }
        Log.w("ChatViewModel", "达到最大步数限制")
        return finishTask(sessionId, false, "达到最大步数限制($maxSteps)")
    }

    /**
     * 结束任务，更新数据库状态并返回结果
     */
    private suspend fun finishTask(
        sessionId: Long,
        success: Boolean,
        message: String,
        isCancelled: Boolean = false
    ): TaskResult {
        val dbStatus = when {
            isCancelled -> "cancelled"
            success -> "success"
            else -> "fail"
        }
        db.taskSessionDao().updateStatus(sessionId, dbStatus)
        messageContext.clear()
        _executionState.value = _executionState.value.copy(
            isRunning = false,
            isCompleted = true,
            isSuccess = success,
            isCancelled = isCancelled,
            resultMessage = message
        )
        return TaskResult(success, message)
    }

    /**
     * 未创建会话就提前失败（例如无障碍未启用）
     * 不写库，但仍把结果走状态流，让观察者展示错误消息。
     */
    private fun failWithoutSession(message: String): TaskResult {
        messageContext.clear()
        _executionState.value = _executionState.value.copy(
            isRunning = false,
            isCompleted = true,
            isSuccess = false,
            isCancelled = false,
            resultMessage = message
        )
        return TaskResult(false, message)
    }

    /**
     * 从AI响应中提取操作类型
     */
    private fun extractActionType(action: String): String {
        val lower = action.lowercase()
        return when {
            lower.contains("finish(") -> "finish"
            lower.contains("\"launch\"") || lower.contains("action=\"launch\"") -> "launch"
            lower.contains("\"tap\"") || lower.contains("action=\"tap\"") -> "tap"
            lower.contains("\"type\"") || lower.contains("action=\"type\"") -> "type"
            lower.contains("\"swipe\"") || lower.contains("action=\"swipe\"") -> "swipe"
            lower.contains("\"back\"") || lower.contains("action=\"back\"") -> "back"
            lower.contains("\"home\"") || lower.contains("action=\"home\"") -> "home"
            lower.contains("\"wait\"") || lower.contains("action=\"wait\"") -> "wait"
            lower.contains("\"long press\"") -> "longpress"
            lower.contains("\"double tap\"") -> "doubletap"
            else -> "unknown"
        }
    }
}