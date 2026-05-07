package com.example.register

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionExecutor.tryFixMalformedJson 的单元测试。
 *
 * 这是项目里最复杂、最易踩坑的纯函数：负责把模型的函数式输出
 * （do(...) / finish(...)）和自然语言（"打开 xxx"）规整成内部 JSON。
 * commit 523f124 修过其中的引号截断算法，下面的 case 覆盖了：
 *   - 标准函数式输入
 *   - 模型违反规范输出裸引号时的兼容性（关键回归点）
 *   - 转义引号的反转义
 *   - 自然语言兜底
 *   - 完全无法识别时的优雅降级
 *
 * 选 tryFixMalformedJson 而不是其他函数的理由：
 *   1. companion object fun，无 Android 依赖，纯 JVM 可跑
 *   2. 输入输出都是字符串/JSON，断言简单稳定
 *   3. 业务后果重——这里挂掉等于模型的 finish 总结被截断或丢失
 */
class ActionExecutorTest {

    @Test
    fun `finish 标准函数式输入 - 正确转换为 JSON`() {
        val raw = """finish(message="任务完成")"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("finish", json.get("_metadata").asString)
        assertEquals("任务完成", json.get("message").asString)
    }

    @Test
    fun `finish message 含裸引号 - 取最后一个引号作为闭合（523f124 关键回归点）`() {
        // 模型偶尔会在 message 里写描述性的中文引号字面量
        // 旧算法会把第一个内嵌 " 当作闭引号 → message 被截成 "已点击"
        // 修复后取该段最后一个 " 作为闭合 → message 完整保留
        val raw = """finish(message="已点击"购买"按钮，订单号 12345")"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        val message = json.get("message").asString
        assertTrue("应保留'购买'字面量，实际：$message", message.contains("购买"))
        assertTrue("应保留'订单号 12345'尾部，实际：$message", message.contains("订单号 12345"))
    }

    @Test
    fun `finish message 含转义引号 - 反转义为单个引号`() {
        // 规范写法：模型按 JSON 风格转义 \" 时应反转义
        val raw = """finish(message="say \"hello\" world")"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("""say "hello" world""", json.get("message").asString)
    }

    @Test
    fun `finish 空 message - 不抛异常 且 message 为空字符串`() {
        val raw = """finish(message="")"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("finish", json.get("_metadata").asString)
        assertEquals("", json.get("message").asString)
    }

    @Test
    fun `do action with element 数组 - 数字坐标正确解析`() {
        val raw = """do(action="Tap", element=[100, 200])"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("do", json.get("_metadata").asString)
        assertEquals("Tap", json.get("action").asString)
        val element = json.get("element").asJsonArray
        assertEquals(2, element.size())
        assertEquals(100, element[0].asInt)
        assertEquals(200, element[1].asInt)
    }

    @Test
    fun `do launch with unquoted Chinese app - 保留 app 参数`() {
        val raw = """do(action=Launch, app=微信)"""

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("do", json.get("_metadata").asString)
        assertEquals("Launch", json.get("action").asString)
        assertEquals("微信", json.get("app").asString)
    }

    @Test
    fun `自然语言 打开微信 - 兜底转为 Launch action`() {
        // 用户/模型偶尔不按格式说话时，正则兜底能识别中文动作词
        val raw = "打开微信"

        val json = JsonParser.parseString(
            ActionExecutor.tryFixMalformedJson(raw)
        ).asJsonObject

        assertEquals("do", json.get("_metadata").asString)
        assertEquals("Launch", json.get("action").asString)
        assertEquals("微信", json.get("app").asString)
    }

    @Test
    fun `完全无法识别的文本 - 返回空字符串而非崩溃`() {
        // 既不是 do(/finish( 也无中文动作词，让上层走"无法解析"分支
        val raw = "Hello World, this is not an action."

        val result = ActionExecutor.tryFixMalformedJson(raw)

        assertEquals("", result)
    }
}
