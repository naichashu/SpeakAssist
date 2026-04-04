# SpeakAssist

毕业设计：基于语音控制的 Android 智能助手 App。

## 项目概述

SpeakAssist 通过自然语言指令驱动大模型理解当前界面，并结合无障碍服务、截图与动作执行能力，在 Android 设备上完成自动化操作。

核心能力：
- 文本任务输入
- 语音识别输入
- 悬浮窗快捷唤起
- 历史任务记录与步骤详情

## 技术栈

- Android + Kotlin
- AccessibilityService / InputMethodService
- Retrofit + OkHttp
- Room
- DataStore
- 百度语音识别 REST API
- 智谱 AutoGLM 接口

## 主要流程

1. 用户在主界面或悬浮窗输入任务。
2. `ChatViewModel` 获取当前应用信息与截图。
3. `ModelClient` 组装提示词并请求模型。
4. `ActionExecutor` 解析模型动作并执行。
5. 执行结果与步骤写入 Room，历史页可查看。

## 常用命令

```bash
./gradlew assembleDebug
./gradlew lint
./gradlew test
./gradlew installDebug
```

## 相关文档

- `CLAUDE.md`：仓库协作说明
- `SpeakAssist_UI设计文档.md`：UI 设计文档

## 说明

本仓库当前以 Android 客户端实现为准；旧的 Python/Open-AutoGLM 启动方式已不再适用于当前项目结构。
