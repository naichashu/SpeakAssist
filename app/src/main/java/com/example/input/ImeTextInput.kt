package com.example.input

import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService

class ImeTextInput(
    private val service: MyAccessibilityService
) {

    fun inputText(text: String): TextInputResult {
        if (text.isBlank()) {
            return TextInputResult(false, "缺少要输入的文本")
        }
        if (!MyInputMethodService.isEnabled(service)) {
            return TextInputResult(false, "当前输入方式为输入法模拟，请先启用 SpeakAssist 输入法")
        }
        if (!MyInputMethodService.isCurrentInputMethod(service)) {
            return TextInputResult(false, "当前输入方式为输入法模拟，请先切换到 SpeakAssist 输入法")
        }
        val success = MyInputMethodService.inputText(text)
        return if (success) {
            TextInputResult(true, "输入法模拟输入成功：$text")
        } else {
            TextInputResult(false, "SpeakAssist 输入法已切换，但当前输入框未激活或无法写入")
        }
    }
}
