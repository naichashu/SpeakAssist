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
import com.example.input.TextInputMode
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

    // 跟踪当前 step 内同一 action type 的连续失败次数，用于硬拦截
    // 每个 step 开始时重置
    private val stepActionFailures = mutableMapOf<String, Int>()
    private val MAX_STEP_ACTION_FAILURES = 2

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
        var retryCount = 0
        val maxRetries = 4
        val compressionLevel = 80
        try {
        while (stepCount < maxSteps) {
            // 读取当前输入模式
            val inputMode = SettingsPrefs.textInputMode(getApplication()).first()

            // 检查取消请求
            if (_cancelRequested.value) {
                Log.d(TAG, "任务被用户取消，中断 HTTP 请求")
                modelClient?.cancelCurrentRequest()
                return finishTask(sessionId, false, "用户手动取消任务", isCancelled = true)
            }

            val client = modelClient ?: return finishTask(sessionId, false, "模型客户端未初始化")
            Log.d(TAG, "执行步骤 $stepCount")

            // hide overlay → 读前台包名 → 非自身才截图 → restore overlay。
            // captureForegroundContext 内部用 try-finally 保证 hide/restore 配对，
            // 避免旧实现"在 isMyProjectApp 分支跳过 restore"的悬浮窗消失 bug。
            val myProjectApp = getApplication<Application>().packageName
            val (currentApp, screenShot) = accessibilityService.captureForegroundContext(myProjectApp)
            val isMyProjectApp = currentApp == myProjectApp
            Log.d(TAG, "当前应用: $currentApp $myProjectApp")
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

            // 提取 action type 用于失败跟踪
            val actionType = extractActionType(response.action)
            val stepFails = stepActionFailures[actionType] ?: 0

            // 连续失败拦截：同一 action type 失败 2 次就不再执行
            val result: ActionResult = if (stepFails >= MAX_STEP_ACTION_FAILURES) {
                Log.d(TAG, "action=$actionType 当前step内已失败 $stepFails 次，跳过执行")
                ActionResult(false, "该动作连续失败，强制换策略")
            } else {
                actionExecutor?.execute(
                    response.action,
                    screenShot?.width ?: displayMetrics.widthPixels,
                    screenShot?.height ?: displayMetrics.heightPixels
                ) ?: ActionResult(false, "ActionExecutor is null")
            }

            Log.d(TAG, "执行动作结果: ${result.success}: ${result.message}")

            // 更新执行状态供悬浮窗观察
            _executionState.value = _executionState.value.copy(
                currentStep = stepCount + 1,
                currentAction = result.message ?: response.action.take(100)
            )

            // 保存步骤到数据库
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
                // 在微信里 type 失败 + DIRECT 模式 → 立即提示用户切换输入法
                if (actionType == "type" && inputMode == TextInputMode.DIRECT && currentApp == "com.tencent.mm") {
                    Log.w(TAG, "Type在微信+DIRECT模式下失败，立即提示用户切换输入法")
                    Toast.makeText(
                        getApplication(),
                        "输入失败：微信等应用会拦截直接输入。请去「设置」→「输入方式」切换到「输入法模拟」模式后重试。",
                        Toast.LENGTH_LONG
                    ).show()
                    return finishTask(
                        sessionId, false,
                        "输入失败：当前「直接设置文本」模式被微信拦截。请去「设置」→「输入方式」切换到「输入法模拟」模式，然后重新执行任务。"
                    )
                }

                // Type 失败 + 非微信 + DIRECT 模式 + setText/Paste 均失败 → 提示切换输入法
                if (actionType == "type" && inputMode == TextInputMode.DIRECT &&
                    ((result.message ?: "").contains("不可访问") ||
                     (result.message ?: "").contains("剪贴板粘贴输入"))) {
                    Log.w(TAG, "Type在DIRECT模式下被拦截，立即结束并提示用户")
                    Toast.makeText(
                        getApplication(),
                        "输入失败：微信等应用会拦截直接输入。请去「设置」→「输入方式」切换到「输入法模拟」模式后重试。",
                        Toast.LENGTH_LONG
                    ).show()
                    return finishTask(
                        sessionId, false,
                        "输入失败：当前「直接设置文本」模式被微信拦截。请去「设置」→「输入方式」切换到「输入法模拟」模式，然后重新执行任务。"
                    )
                }

                // 把刚刚写入历史的畸形 assistant 消息改写成占位符，
                // 避免模型在下一轮对话里把自己错误的输出当成格式模板照抄，引发级联失败。
                if (messageContext.isNotEmpty() && messageContext.last().role == "assistant") {
                    messageContext[messageContext.size - 1] =
                        client.createAssistantMessage("", "[上一轮输出格式错误，已过滤]")
                }

                // 构建具体错误消息：包含失败原因和替代策略指导
                val errorText = buildErrorText(actionType, result.message ?: "未知错误", inputMode, currentApp)

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
                Log.d(TAG, "已向模型反馈失败: ${result.message}")

                retryCount++
                stepActionFailures[actionType] = stepFails + 1

                if (retryCount > maxRetries) {
                    Log.e(TAG, "重试超过上限，结束流程: ${result.message}")
                    return finishTask(sessionId, false, "连续错误超过上限: ${result.message}")
                }

                // 重试：继续下一轮循环
                delay(1000)
                continue
            } else {
                // 成功-重置重试计数
                retryCount = 0
                stepActionFailures.remove(actionType)
            }

            val settleDelayMs = result.actionDetail?.waitMs ?: 1000L
            Log.d(TAG, "等待界面稳定: ${settleDelayMs}ms, action=${result.actionDetail?.type}")
            delay(settleDelayMs)
            stepCount++
            // 每个成功的 step 完成后清零失败计数器，
            // 防止跨 step 的同种 action 误命中硬拦截阈值
            stepActionFailures.clear()
        }
        Log.w("ChatViewModel", "达到最大步数限制")
        return finishTask(sessionId, false, "达到最大步数限制($maxSteps)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须重抛，否则破坏取消语义
            finishTask(sessionId, false, "用户手动取消任务", isCancelled = true)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "executeTaskLoop 异常", e)
            return finishTask(sessionId, false, "执行异常：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 构建具体的错误反馈文本，包含失败原因和替代策略
     */
    private fun buildErrorText(actionType: String, errorMessage: String, inputMode: TextInputMode, currentApp: String?): String {
        return when {
            // 微信里 type 失败：统一提示切输入法
            actionType == "type" && currentApp == "com.tencent.mm" -> {
                """
                    上一轮动作执行失败: $errorMessage

                    原因分析：微信等应用会拦截直接设置文本和剪贴板粘贴操作，输入法模拟模式才能正常工作。

                    请先去侧栏「设置」→「输入方式」切换到「输入法模拟」模式，然后重新执行任务。
                    切换后需要将系统输入法切换为 SpeakAssist 输入法（切换方式：长按地球键选择 SpeakAssist 输入法）。
                """.trimIndent()
            }
            else -> {
                """
                    上一轮动作执行失败: $errorMessage

                    请分析失败原因，选择 Back/换坐标 Tap/换方向 Swipe/finish 中的一种。
                    禁止重复执行同样的失败动作。
                """.trimIndent()
            }
        }
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
     * 从AI响应中提取操作类型。
     * 匹配模式：action="Type", action="Long Press", action=Type 等各种变体。
     *
     * 关键：正则必须支持带引号的值内部可以有空格（如 "Long Press", "Double Tap"），
     * 否则含空格的 action 会被截断为 unknown，导致错误反馈不精准。
     */
    private fun extractActionType(action: String): String {
        val lower = action.lowercase()

        // 优先匹配带引号的值（引号内可以有空格）
        val quotedMatch = Regex(
            """action\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (quotedMatch != null) {
            return normalizeActionType(quotedMatch.groupValues[1])
        }

        // 其次匹配无引号的值（遇到空白/逗号/括号停止）
        val bareMatch = Regex(
            """action\s*=\s*([^\s,)]+)""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        return if (bareMatch != null) {
            normalizeActionType(bareMatch.groupValues[1])
        } else {
            "unknown"
        }
    }

    /**
     * 将原始 action 值归一化为标准类型。
     * 覆盖所有可能的变体：全小写/驼峰/带空格/带下划线。
     */
    private fun normalizeActionType(raw: String): String {
        return when (raw.trim()) {
            "finish" -> "finish"
            "launch" -> "launch"
            "tap" -> "tap"
            "type" -> "type"
            "swipe" -> "swipe"
            "back" -> "back"
            "home" -> "home"
            "wait" -> "wait"
            "longpress", "long press", "long_press" -> "longpress"
            "doubletap", "double tap", "double_tap" -> "doubletap"
            else -> "unknown"
        }
    }
}
