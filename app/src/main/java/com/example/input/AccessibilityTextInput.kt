package com.example.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        if (target == null) {
            // root 已在 findFirstEditableNode 遍历结束时关闭，无需再关
            return TextInputResult(false, "当前页面没有可直接输入的文本框")
        }

        val setTextSuccess = try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            if (target !== root) closeNode(target)
            closeNode(root)
        }

        if (setTextSuccess) {
            return TextInputResult(true, "直接输入文本成功：$text")
        }

        // setText 失败，尝试 Clipboard Paste 作为 fallback（绕过 WeChat 等 App 对 setText 的拦截）
        val pasteSuccess = tryClipboardPaste(text)
        if (pasteSuccess) {
            return TextInputResult(true, "剪贴板粘贴输入成功：$text")
        }

        return TextInputResult(false, "当前输入框不支持直接设置文本，也无法通过剪贴板粘贴输入")
    }

    private fun tryClipboardPaste(text: String): Boolean {
        try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SpeakAssist", text)
            clipboard.setPrimaryClip(clip)

            try {
                val pasteArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
                }

                val root = service.rootInActiveWindow ?: return false
                val target = findInputTarget(root)
                if (target == null) {
                    // root 已在 findFirstEditableNode 遍历结束时关闭，无需再关
                    return false
                }
                return try {
                    target.performAction(AccessibilityNodeInfo.ACTION_PASTE, pasteArgs)
                } finally {
                    if (target !== root) closeNode(target)
                    closeNode(root)
                }
            } finally {
                val clearClip = ClipData.newPlainText("SpeakAssist", "")
                clipboard.setPrimaryClip(clearClip)
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun findInputTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
            if (node.isEditable) {
                return node
            }
            closeNode(node)
        }
        service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
            if (node.isEditable) {
                return node
            }
            closeNode(node)
        }

        return findFirstEditableNode(root)
    }

    /**
     * 在 AccessibilityNodeInfo 树中查找第一个可编辑节点。
     * 使用显式栈迭代而非递归，确保遍历过程中每个节点使用完毕后立即释放。
     *
     * 节点释放规则：
     * - root 由调用方负责释放，本函数不会释放 root。
     * - 通过 getChild() 获取的子节点入栈，由本函数在出栈时释放。
     * - 找到的匹配节点返回给 caller（caller 负责释放）。
     *
     * @param root 树根节点，由 caller 持有
     * @return 第一个可编辑节点（caller 负责释放），null 表示未找到
     */
    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isEditable) {
                // 匹配成功：释放栈中暂存的所有其余节点（它们都是遍历过程中压入但
                // 尚未使用的），只保留返回的匹配节点
                recycleAll(stack)
                return current
            }
            // 子节点入栈（由 getChild() 获取，栈负责在出栈时释放）
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
            // 当前节点已遍历完毕，释放
            if (current !== root) {
                closeNode(current)
            }
        }
        return null
    }

    /**
     * 释放 ArrayDeque 中所有暂存的 AccessibilityNodeInfo。
     * 这些节点是遍历过程中压入栈但尚未使用的。
     */
    private fun recycleAll(nodes: ArrayDeque<AccessibilityNodeInfo>) {
        while (nodes.isNotEmpty()) {
            closeNode(nodes.removeLast())
        }
    }

    /**
     * 释放单个 AccessibilityNodeInfo。
     * API 36+ 使用 close()（实现 Closeable），旧版本使用 recycle()。
     * AccessibilityNodeInfo 在 API 36 添加了 Closeable 实现，但 Kotlin 编译器
     * 无法跨 SDK 版本解析 close() 方法，用反射调用确保兼容。
     */
    private fun closeNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val closeMethod = AccessibilityNodeInfo::class.java.getMethod("close")
                closeMethod.invoke(node)
            } catch (e: Exception) {
                // 反射失败时降级到 recycle（理论上不会发生）
                @Suppress("DEPRECATION")
                node.recycle()
            }
        } else {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}
