package com.example.input

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.MyAccessibilityService

class AccessibilityTextInput(
    private val service: MyAccessibilityService
) {

    fun inputText(text: String): TextInputResult {
        if (text.isBlank()) {
            return TextInputResult(false, "缺少要输入的文本")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return TextInputResult(false, "当前系统版本不支持直接设置文本")
        }

        val root = service.rootInActiveWindow
            ?: return TextInputResult(false, "当前页面不可访问，无法直接输入文本")

        val target = findInputTarget(root)
            ?: return TextInputResult(false, "当前页面没有可直接输入的文本框")

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (success) {
            TextInputResult(true, "直接输入文本成功：$text")
        } else {
            TextInputResult(false, "当前输入框不支持直接设置文本")
        }
    }

    private fun findInputTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
            if (node.isEditable) return node
        }
        service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
            if (node.isEditable) return node
        }
        return findEditableNode(root)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findEditableNode(child)
            if (match != null) return match
        }
        return null
    }
}
