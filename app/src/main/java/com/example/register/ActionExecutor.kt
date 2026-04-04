package com.example.register

import android.content.Intent
import android.util.Log
import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import kotlinx.coroutines.delay

/**
 * 动作详情
 */
data class ActionDetail(
    val type: String,
    val x1: Float? = null,
    val y1: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val text: String? = null,
    val waitMs: Long? = null
)

/**
 * 执行动作结果
 */
data class ActionResult(
    val success: Boolean,
    val message: String? = null,
    val actionDetail: ActionDetail? = null
)

/**
 * 动作执行器
 */
class ActionExecutor(private val service: MyAccessibilityService) {

    companion object {
        const val TAG = "ActionExecutor"
        private const val LAUNCH_SETTLE_DELAY_MS = 2500L
        private const val TAP_SETTLE_DELAY_MS = 1500L
        private const val INPUT_SETTLE_DELAY_MS = 1200L
        private const val SWIPE_SETTLE_DELAY_MS = 1200L
        private const val NAVIGATION_SETTLE_DELAY_MS = 1200L
    }

    /**
     * 执行动作
     */
    suspend fun execute(actionJson: String, screenWidth: Int, screenHeight: Int): ActionResult {
        return try {
            Log.d(TAG, "开始解析动作: ${actionJson.take(500)}")

            val jsonString = extractJsonFromText(actionJson)
            Log.d(TAG, "提取JSON: ${jsonString.take(250)}")

            // 如果没有JSON，则尝试修复
            if (jsonString.isEmpty() || jsonString == actionJson.trim()) {
                val fixedJson = tryFixMalformedJson(actionJson)
                if (fixedJson.isNotEmpty()) {
                    try {
                        val jsonElement = JsonParser.parseString(fixedJson)
                        if (jsonElement.isJsonObject) {
                            val actionObj = jsonElement.asJsonObject
                            Log.d(TAG, "修复后解析JSON成功: $actionObj")
                            // 处理动作对象
                            return processActionObject(actionObj, screenWidth, screenHeight)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "修复后的 JSON 仍然无法解析", e)
                    }
                }

                return ActionResult(
                    success = false,
                    message = "无法从响应中提取有效的 JSON 动作。"
                )
            }

            val jsonElement = try {
                JsonParser.parseString(jsonString)
            } catch (e: Exception) {
                Log.w(TAG, "标准解析失败，尝试 lenient 模式", e)
                try {
                    val reader = JsonReader(StringReader(jsonString))
                    reader.isLenient = true
                    JsonParser.parseReader(reader)
                } catch (e2: Exception) {
                    Log.e(TAG, "Lenient 模式也失败", e2)
                    val fixedJson = tryFixMalformedJson(jsonString)
                    if (fixedJson.isNotEmpty()) {
                        try {
                            return processActionObject(
                                JsonParser.parseString(fixedJson).asJsonObject,
                                screenWidth,
                                screenHeight
                            )
                        } catch (e3: Exception) {
                            Log.e(TAG, "修复后仍然无法解析", e3)
                        }
                    }
                    throw e2
                }
            }

            if (!jsonElement.isJsonObject) {
                val errorMsg = if (jsonElement.isJsonPrimitive) {
                    "响应不是 JSON 对象，而是: ${jsonElement.asString.take(100)}"
                } else {
                    "响应不是 JSON 对象"
                }
                throw IllegalStateException(errorMsg)
            }

            val actionObj = jsonElement.asJsonObject
            Log.d(TAG, "解析成功，对象: $actionObj")

            processActionObject(actionObj, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "解析动作失败", e)
            ActionResult(success = false, message = "解析动作失败: ${e.message}")
        }
    }

    /**
     * 从文本中提取 JSON 动作
     */
    private fun extractJsonFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            JsonParser.parseString(trimmed)
            return trimmed
        }

        val jsonCandidates = mutableListOf<String>()
        var startIndex = -1
        var braceCount = 0

        for (i in trimmed.indices) {
            when (trimmed[i]) {
                '{' -> {
                    if (startIndex == -1) startIndex = i
                    braceCount++
                }

                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        val candidate = trimmed.substring(startIndex, i + 1)
                        try {
                            JsonParser.parseString(candidate)
                            jsonCandidates.add(candidate)
                        } catch (e: Exception) {
                        }
                        startIndex = -1
                    }
                }
            }
        }

        if (jsonCandidates.isNotEmpty()) return jsonCandidates.first()

        val fixedJson = tryFixMalformedJson(trimmed)
        if (fixedJson.isNotEmpty()) {
            JsonParser.parseString(fixedJson)
            return fixedJson
        }

        return trimmed
    }

    /**
     * 尝试修复可能被截断的 JSON
     */
    private fun tryFixMalformedJson(text: String): String {
        val functionCallPattern = Regex("""(do|finish)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        val functionMatch = functionCallPattern.find(text)

        if (functionMatch != null) {
            val functionName = functionMatch.groupValues[1].lowercase()
            val paramsStr = functionMatch.groupValues[2]

            if (functionName == "finish") {
                val messagePattern =
                    Regex("""message\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                val messageMatch = messagePattern.find(paramsStr)
                val message = messageMatch?.groupValues?.get(1) ?: paramsStr.trim().trim('"', '\'')
                return """{"_metadata": "finish", "message": "$message"}"""
            } else if (functionName == "do") {
                val action = mutableMapOf<String, Any>("_metadata" to "do")
                val paramPattern = Regex(
                    """(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\[[^\]]+\]|\d+\.?\d*|true|false)""",
                    RegexOption.IGNORE_CASE
                )
                val paramMatches = paramPattern.findAll(paramsStr)

                for (match in paramMatches) {
                    val key = match.groupValues[1]
                    val valueStr = match.groupValues[2].trim()
                    val value: Any = when {
                        valueStr.startsWith("[") -> {
                            val arrayValues = valueStr.substring(1, valueStr.length - 1).split(",")
                                .map { it.trim() }
                            "[" + arrayValues.joinToString(",") + "]"
                        }

                        valueStr.startsWith("\"") || valueStr.startsWith("'") -> {
                            valueStr.trim('"', '\'').replace("\\\"", "\"").replace("\\'", "'")
                        }

                        valueStr == "true" -> true
                        valueStr == "false" -> false
                        valueStr.contains(".") -> valueStr.toDoubleOrNull() ?: valueStr
                        else -> valueStr.toIntOrNull() ?: valueStr
                    }
                    action[key] = value
                }

                val jsonBuilder = StringBuilder("{")
                jsonBuilder.append("\"_metadata\": \"do\"")
                for ((key, value) in action) {
                    if (key == "_metadata") continue
                    jsonBuilder.append(", \"$key\": ")
                    when (value) {
                        is String -> {
                            if (value.startsWith("[")) jsonBuilder.append(value)
                            else jsonBuilder.append("\"${value.replace("\"", "\\\"")}\"")
                        }

                        is Number, is Boolean -> jsonBuilder.append(value)
                        else -> {
                            val vStr = value.toString()
                            if (vStr.startsWith("[")) jsonBuilder.append(vStr)
                            else jsonBuilder.append("\"${vStr.replace("\"", "\\\"")}\"")
                        }
                    }
                }
                jsonBuilder.append("}")
                return jsonBuilder.toString()
            }
        }

        val pattern1 = Regex(
            """do\s*\(\s*action\s*=\s*["']([^"']+)["']\s*,\s*app\s*=\s*["']([^"']+)["']\s*\)""",
            RegexOption.IGNORE_CASE
        )
        val match1 = pattern1.find(text)
        if (match1 != null) {
            return """{"_metadata": "do", "action": "${match1.groupValues[1]}", "app": "${match1.groupValues[2]}"}"""
        }

        val launchPattern =
            Regex("""(?:打开|启动|运行|launch)\s*([^\s，,。.]+)""", RegexOption.IGNORE_CASE)
        val launchMatch = launchPattern.find(text)
        if (launchMatch != null) {
            return """{"_metadata": "do", "action": "Launch", "app": "${launchMatch.groupValues[1].trim()}"}"""
        }

        return ""
    }

    /**
     * 执行动作
     */
    private suspend fun processActionObject(
        actionObj: JsonObject,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val metadata = actionObj.get("_metadata")?.asString ?: ""

        Log.d(TAG, "处理动作: $metadata")

        return when (metadata) {
            "finish" -> {
                val message = actionObj.get("message")?.asString ?: "任务完成"
                ActionResult(success = true, message = message)
            }

            "do" -> {
                val action = actionObj.get("action")?.asString ?: ""
                executeAction(action, actionObj, screenWidth, screenHeight)
            }

            else -> {
                ActionResult(success = false, message = "未知的动作类型: $metadata")
            }
        }
    }

    /**
     * 应用返回前台
     */
    fun bringAppToForeground() {
        try {
            val packageName = service.packageName
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                service.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "返回应用失败", e)
        }
    }

    private suspend fun executeAction(
        action: String,
        actionObj: JsonObject,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        Log.d(TAG, "执行动作类型: $action (lowercase: ${action.lowercase()})")
        val result = try {
            when (action.lowercase()) {
                "launch" -> launchApp(actionObj)
                "tap" -> tap(actionObj, screenWidth, screenHeight)
                "type" -> type(actionObj)
                "swipe" -> swipe(actionObj, screenWidth, screenHeight)
                "back" -> back()
                "home" -> home()
                "longpress", "long press" -> longPress(actionObj, screenWidth, screenHeight)
                "doubletap", "double tap" -> doubleTap(actionObj, screenWidth, screenHeight)
                "wait" -> wait(actionObj)
                else -> ActionResult(success = false, message = "不支持的操作: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行动作异常: $action", e)
            ActionResult(success = false, message = "执行动作失败: $action - ${e.message}")
        }

        Log.d(TAG, "动作执行完成: $action -> success=${result.success}, message=${result.message}")
        return result
    }

    /**
     * 启动应用
     */
    private suspend fun launchApp(actionObj: JsonObject): ActionResult {
        // 1. 提取 app 参数并校验
        val appName = actionObj.get("app")?.asString ?: return ActionResult(
            success = false,
            message = "缺少app参数"
        )

        // 2. 获取应用包名并校验
        val packageName = AppRegister.getPackageName(appName)
        if (packageName.isBlank()) {
            return ActionResult(
                success = false,
                message = "未找到app: $appName"
            )
        }

        try {
            // 3. 获取应用启动意图并校验
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult(
                    success = false,
                    message = "无法获取 $appName 的启动意图，应用可能无法直接启动"
                )

            // 4. 设置 Intent 标记（按需调整，此处保留原标记，可根据需求移除 SINGLE_TOP）
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            // 5. 启动应用
            service.startActivity(intent)
            delay(1000)

            // 6. 启动成功，返回成功结果
            return ActionResult(
                success = true,
                message = "成功启动 app: $appName",
                actionDetail = ActionDetail(type = "launch", text = appName, waitMs = LAUNCH_SETTLE_DELAY_MS)
            )
        } catch (e: Exception) {
            // 7. 捕获所有可能的运行时异常，优雅返回错误结果
            return ActionResult(
                success = false,
                message = "启动 $appName 失败：${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 点击事件
     */
    private fun tap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ActionResult {
        val element = actionObj.get("element")
        Log.d(TAG, "点击事件位置: $element, 屏幕尺寸: ${screenWidth}x${screenHeight}")
        if (element.isJsonArray) {
            val arr = element.asJsonArray
            if (arr.size() == 2) {
                val (x, y) = relativeToAbsolute(
                    listOf(arr[0].asFloat, arr[1].asFloat),
                    screenWidth,
                    screenHeight
                )
                Log.d(TAG, "相对坐标[${arr[0]}, ${arr[1]}] -> 绝对坐标($x, $y)")
                val success = service.clickByNode(x, y)
                return if (success) {
                    ActionResult(
                        success = true,
                        message = "点击成功：坐标($x, $y)",
                        actionDetail = ActionDetail(
                            type = "tap",
                            x1 = x,
                            y1 = y,
                            waitMs = TAP_SETTLE_DELAY_MS
                        )
                    )
                } else {
                    ActionResult(
                        success = false,
                        message = "点击手势提交失败：坐标($x, $y)",
                        actionDetail = ActionDetail(
                            type = "tap",
                            x1 = x,
                            y1 = y
                        )
                    )
                }
            } else {
                Log.e(TAG, "参数错误：数组长度为${arr.size()}")
                return ActionResult(
                    success = false,
                    message = "参数错误：element字段需为「千分比相对坐标数组」，标准格式为 [x, y]，" +
                            "数组长度必须为2（x=屏幕宽度占比0-1000，y=屏幕高度占比0-1000）"
                )
            }
        } else {
            Log.e(TAG, "参数错误：element不是数组，类型为${element?.javaClass?.simpleName}")
            return ActionResult(
                success = false,
                message = "参数错误：element字段类型非法，需为JSON数组格式 [x, y]" +
                        "（x=屏幕宽度千分比0-1000，y=屏幕高度千分比0-1000）"
            )
        }
    }

    /**
     * 将相对坐标转换为绝对坐标
     */
    private fun relativeToAbsolute(
        arr: List<Float>,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float> {
        val x = (arr[0] / 1000f) * screenWidth
        val y = (arr[1] / 1000f) * screenHeight
        return Pair(x, y)
    }

    /**
     * 输入文本
     */
    private fun type(actionObj: JsonObject): ActionResult {
        val text = actionObj.get("text")?.asString ?: return ActionResult(
            success = false,
            message = "缺少text参数"
        )

        // 检查输入法服务是否已启用
        if (!MyInputMethodService.isEnabled(service)) {
            return ActionResult(
                success = false,
                message = "输入法服务未启用，无法完成输入操作"
            )
        }

        // 输入文本
        val inputSuccess = MyInputMethodService.inputText(text)

        // 返回结果
        return if (inputSuccess) {
            ActionResult(
                success = true,
                message = "文本输入成功：$text",
                actionDetail = ActionDetail(type = "type", text = text, waitMs = INPUT_SETTLE_DELAY_MS)
            )
        } else {
            ActionResult(
                success = false,
                message = "输入法已启用，但输入失败"
            )
        }

    }

    /**
     * 滑动屏幕
     * 支持两种格式：
     * 1. element: [[x1, y1], [x2, y2]]（千分比坐标）
     * 2. start: [x1, y1], end: [x2, y2]
     * @param actionObj 包含滑动参数的JSON对象
     * @param screenWidth 屏幕宽度像素
     * @param screenHeight 屏幕高度像素
     */
    private fun swipe(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ActionResult {
        val element = actionObj.get("element")
        val start = actionObj.get("start")
        val end = actionObj.get("end")

        Log.d(TAG, "滑动事件 - element: $element, start: $start, end: $end")

        // 解析坐标变量
        var startX: Float = 0f
        var startY: Float = 0f
        var endX: Float = 0f
        var endY: Float = 0f

        // 优先使用 start/end 格式
        if (start != null && end != null) {
            // 格式：start: [x, y], end: [x, y]
            if (!start.isJsonArray || start.asJsonArray.size() != 2 ||
                !end.isJsonArray || end.asJsonArray.size() != 2) {
                return ActionResult(
                    success = false,
                    message = "参数错误：start和end字段需为坐标数组 [x, y]"
                )
            }
            startX = start.asJsonArray[0].asFloat
            startY = start.asJsonArray[1].asFloat
            endX = end.asJsonArray[0].asFloat
            endY = end.asJsonArray[1].asFloat
        }
        // 其次使用 element 格式
        else if (element != null) {
            // 格式：element: [[x1, y1], [x2, y2]]
            if (!element.isJsonArray) {
                return ActionResult(
                    success = false,
                    message = "参数错误：element字段需为数组格式"
                )
            }

            val arr = element.asJsonArray
            if (arr.size() != 2) {
                return ActionResult(
                    success = false,
                    message = "参数错误：element数组长度必须为2，表示起点和终点"
                )
            }

            // 解析起点坐标
            if (!arr[0].isJsonArray || arr[0].asJsonArray.size() != 2) {
                return ActionResult(
                    success = false,
                    message = "参数错误：起点坐标格式错误，应为 [x1, y1]"
                )
            }
            val startArr = arr[0].asJsonArray
            startX = startArr[0].asFloat
            startY = startArr[1].asFloat

            // 解析终点坐标
            if (!arr[1].isJsonArray || arr[1].asJsonArray.size() != 2) {
                return ActionResult(
                    success = false,
                    message = "参数错误：终点坐标格式错误，应为 [x2, y2]"
                )
            }
            val endArr = arr[1].asJsonArray
            endX = endArr[0].asFloat
            endY = endArr[1].asFloat
        } else {
            return ActionResult(
                success = false,
                message = "参数错误：swipe需要element字段或start/end字段"
            )
        }

        // 验证坐标范围
        if (startX < 0 || startX > 1000 || startY < 0 || startY > 1000 ||
            endX < 0 || endX > 1000 || endY < 0 || endY > 1000) {
            return ActionResult(
                success = false,
                message = "坐标超出范围：x和y应在0-1000之间"
            )
        }

        // 转换为绝对坐标
        val (absStartX, absStartY) = relativeToAbsolute(listOf(startX, startY), screenWidth, screenHeight)
        val (absEndX, absEndY) = relativeToAbsolute(listOf(endX, endY), screenWidth, screenHeight)

        // 验证绝对坐标是否在屏幕范围内
        if (absStartX < 0 || absStartX > screenWidth || absStartY < 0 || absStartY > screenHeight ||
            absEndX < 0 || absEndX > screenWidth || absEndY < 0 || absEndY > screenHeight) {
            return ActionResult(
                success = false,
                message = "坐标超出屏幕范围：屏幕尺寸为 ${screenWidth}x$screenHeight"
            )
        }

        return try {
            val success = service.swipeByNode(absStartX, absStartY, absEndX, absEndY)
            if (success) {
                ActionResult(
                    success = true,
                    message = "滑动成功：从($absStartX, $absStartY)到($absEndX, $absEndY)",
                    actionDetail = ActionDetail(
                        type = "swipe",
                        x1 = absStartX,
                        y1 = absStartY,
                        x2 = absEndX,
                        y2 = absEndY,
                        waitMs = SWIPE_SETTLE_DELAY_MS
                    )
                )
            } else {
                ActionResult(
                    success = false,
                    message = "滑动执行失败"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "滑动执行异常", e)
            ActionResult(
                success = false,
                message = "滑动执行异常：${e.message}"
            )
        }
    }

    /**
     * 返回上一页
     */
    private fun back(): ActionResult {
        return try {
            val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            if (success) {
                Log.d(TAG, "执行返回成功")
                ActionResult(
                    success = true,
                    message = "返回上一页成功",
                    actionDetail = ActionDetail(type = "back", waitMs = NAVIGATION_SETTLE_DELAY_MS)
                )
            } else {
                Log.e(TAG, "执行返回失败：无障碍服务无法执行返回操作")
                ActionResult(
                    success = false,
                    message = "执行返回失败：设备可能不支持此操作"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行返回异常", e)
            ActionResult(
                success = false,
                message = "执行返回异常：${e.message}"
            )
        }
    }

    /**
     * 返回手机桌面
     */
    private fun home(): ActionResult {
        return try {
            val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            if (success) {
                Log.d(TAG, "执行返回桌面成功")
                ActionResult(
                    success = true,
                    message = "返回桌面成功",
                    actionDetail = ActionDetail(type = "home", waitMs = NAVIGATION_SETTLE_DELAY_MS)
                )
            } else {
                Log.e(TAG, "执行返回桌面失败：无障碍服务无法执行此操作")
                ActionResult(
                    success = false,
                    message = "执行返回桌面失败：设备可能不支持此操作"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行返回桌面异常", e)
            ActionResult(
                success = false,
                message = "执行返回桌面异常：${e.message}"
            )
        }
    }

    /**
     * 长按屏幕
     * @param actionObj 包含element字段的JSON对象，格式为 [x, y]（千分比坐标）
     * @param screenWidth 屏幕宽度像素
     * @param screenHeight 屏幕高度像素
     */
    private fun longPress(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ActionResult {
        val element = actionObj.get("element")
        Log.d(TAG, "长按事件位置: $element")

        if (element == null || !element.isJsonArray) {
            return ActionResult(
                success = false,
                message = "参数错误：longpress需要element字段，格式为 [x, y]（x=屏幕宽度千分比0-1000，y=屏幕高度千分比0-1000）"
            )
        }

        val arr = element.asJsonArray
        if (arr.size() != 2) {
            return ActionResult(
                success = false,
                message = "参数错误：element数组长度必须为2"
            )
        }

        // 验证坐标范围
        val relX = arr[0].asFloat
        val relY = arr[1].asFloat
        if (relX < 0 || relX > 1000 || relY < 0 || relY > 1000) {
            return ActionResult(
                success = false,
                message = "坐标超出范围：x和y应在0-1000之间"
            )
        }

        // 转换为绝对坐标
        val (x, y) = relativeToAbsolute(listOf(relX, relY), screenWidth, screenHeight)

        // 验证绝对坐标是否在屏幕范围内
        if (x < 0 || x > screenWidth || y < 0 || y > screenHeight) {
            return ActionResult(
                success = false,
                message = "坐标超出屏幕范围：屏幕尺寸为 ${screenWidth}x$screenHeight"
            )
        }

        return try {
            val success = service.longPressByNode(x, y)
            if (success) {
                ActionResult(
                    success = true,
                    message = "长按成功：坐标($x, $y)",
                    actionDetail = ActionDetail(type = "longpress", x1 = x, y1 = y, waitMs = TAP_SETTLE_DELAY_MS)
                )
            } else {
                ActionResult(
                    success = false,
                    message = "长按执行失败"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "长按执行异常", e)
            ActionResult(
                success = false,
                message = "长按执行异常：${e.message}"
            )
        }
    }

    /**
     * 双击屏幕
     * @param actionObj 包含element字段的JSON对象，格式为 [x, y]（千分比坐标）
     * @param screenWidth 屏幕宽度像素
     * @param screenHeight 屏幕高度像素
     */
    private fun doubleTap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ActionResult {
        val element = actionObj.get("element")
        Log.d(TAG, "双击事件位置: $element")

        if (element == null || !element.isJsonArray) {
            return ActionResult(
                success = false,
                message = "参数错误：doubletap需要element字段，格式为 [x, y]（x=屏幕宽度千分比0-1000，y=屏幕高度千分比0-1000）"
            )
        }

        val arr = element.asJsonArray
        if (arr.size() != 2) {
            return ActionResult(
                success = false,
                message = "参数错误：element数组长度必须为2"
            )
        }

        // 验证坐标范围
        val relX = arr[0].asFloat
        val relY = arr[1].asFloat
        if (relX < 0 || relX > 1000 || relY < 0 || relY > 1000) {
            return ActionResult(
                success = false,
                message = "坐标超出范围：x和y应在0-1000之间"
            )
        }

        // 转换为绝对坐标
        val (x, y) = relativeToAbsolute(listOf(relX, relY), screenWidth, screenHeight)

        // 验证绝对坐标是否在屏幕范围内
        if (x < 0 || x > screenWidth || y < 0 || y > screenHeight) {
            return ActionResult(
                success = false,
                message = "坐标超出屏幕范围：屏幕尺寸为 ${screenWidth}x$screenHeight"
            )
        }

        return try {
            val success = service.doubleTapByNode(x, y)
            if (success) {
                ActionResult(
                    success = true,
                    message = "双击成功：坐标($x, $y)",
                    actionDetail = ActionDetail(type = "doubletap", x1 = x, y1 = y, waitMs = TAP_SETTLE_DELAY_MS)
                )
            } else {
                ActionResult(
                    success = false,
                    message = "双击执行失败"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "双击执行异常", e)
            ActionResult(
                success = false,
                message = "双击执行异常：${e.message}"
            )
        }
    }

    /**
     * 等待指定时间
     * @param actionObj 包含delay字段的JSON对象，单位为毫秒
     */
    private suspend fun wait(actionObj: JsonObject): ActionResult {
        val delay = actionObj.get("delay")?.asLong

        if (delay == null || delay <= 0) {
            return ActionResult(
                success = false,
                message = "参数错误：wait需要有效的delay参数（毫秒）"
            )
        }

        // 限制最大等待时间，防止意外长时间等待
        val maxDelay = 30000L // 30秒
        val actualDelay = minOf(delay, maxDelay)

        Log.d(TAG, "等待 ${actualDelay}ms")
        delay(actualDelay)

        val message = if (delay > maxDelay) {
            "等待完成（已限制为${maxDelay}ms，原请求为${delay}ms）"
        } else {
            "等待完成：${delay}ms"
        }

        return ActionResult(
            success = true,
            message = message,
            actionDetail = ActionDetail(type = "wait", waitMs = actualDelay)
        )
    }

}