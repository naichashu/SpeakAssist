# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SpeakAssist is an Android voice-controlled AI assistant app (毕业设计). It uses AutoGLM large language model to automate tasks on Android devices through natural language commands. The app captures screen content, sends it to the AI model, and executes actions (tap, launch apps, type text) based on AI responses.

**UI/UX Design Doc**: See `SpeakAssist_UI设计文档.md` for the complete UI design specification (v2.0, finalized).

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean and build
./gradlew clean build

# Run lint analysis
./gradlew lint
```

The debug APK is output to `app/build/intermediates/apk/debug/app-debug.apk`.

## Architecture

The app follows MVVM pattern with these key components:

### Core Flow
1. **MainActivity** (`app/src/main/java/com/example/speakassist/MainActivity.kt`) - Entry point, hosts the main chat UI (top nav + permission banner + chat list + bottom input bar)
2. **ChatViewModel** (`app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`) - Main business logic, manages the AI conversation loop (max 50 steps), handles screenshot capture, error recovery, and returns app to foreground on completion

### Network Layer
- **ModelClient** (`app/src/main/java/com/example/network/ModelClient.kt`) - Handles API communication with AutoGLM, creates messages with screenshots, manages conversation context
- **AutoGLMApi** (`app/src/main/java/com/example/network/AutoGLMApi.kt`) - Retrofit API interface
- DTOs in `app/src/main/java/com/example/network/dto/` - Request/response data classes

### Action Execution
- **ActionExecutor** (`app/src/main/java/com/example/register/ActionExecutor.kt`) - Parses AI JSON responses and executes device actions (tap, launch, type)
- **AppRegister** (`app/src/main/java/com/example/register/AppRegister.kt`) - Registry of installed apps (app name → package name mapping)

### Services
- **MyAccessibilityService** (`app/src/main/java/com/example/service/MyAccessibilityService.kt`) - Android accessibility service for screen capture, UI element interaction, app launching
- **MyInputMethodService** (`app/src/main/java/com/example/service/MyInputMethodService.kt`) - Custom input method for text input

### Speech Recognition
- **BaiduSpeechManager** (`app/src/main/java/com/example/speech/BaiduSpeechManager.kt`) - Baidu Cloud speech-to-text integration using REST API

### Data Layer (to be implemented)
- **Room Database** - Stores chat history with two tables:
  - `task_sessions`: id, user_command, status(success/fail/running), created_at
  - `task_steps`: id, session_id(FK), step_number, action_type, action_description, ai_thinking, created_at
- **DataStore Preferences** - Stores app settings (e.g. floating window on/off). Dependency declared but not yet used in code.

## UI Design Summary

Refer to `SpeakAssist_UI设计文档.md` for full details. Key points:

### Pages
1. **首页 (Main Page)**: Top nav (hamburger + title + settings) → permission banner → chat message list → bottom input bar (text input + voice button + send button)
2. **侧栏 (Drawer)**: Left slide-out drawer showing chat history list (from Room DB), click to open detail page
3. **历史详情页 (History Detail)**: New page showing step-by-step execution log with action descriptions and AI thinking
4. **设置页 (Settings)**: Permission management (accessibility, input method, audio, overlay) + floating window toggle + about/feedback/clear history

### Floating Windows (System Overlay via WindowManager)
1. **圆形悬浮窗 (Circle Floating Button)**:
   - Idle state: circle (~60dp) on right edge, half-hidden
   - Tap → expand to rounded rectangle (logo left + text right), start voice recognition
   - Recognition success → show text → **auto-send** (no confirmation) → collapse back
   - Recognition fail → show "识别失败" → auto-collapse after 3s
   - Tap logo during recognition → cancel, collapse immediately
   - Hidden during task execution
2. **执行卡片 (Execution Card)**:
   - System overlay, semi-transparent, top-center, draggable
   - Shows: task title, current step number (no total), current action, cancel button
   - Disappears 5s after task completion
   - Hidden during screenshot capture, restored after

### Chat Messages
- User messages: right-aligned, blue background (#2196F3)
- System messages: left-aligned, gray background (#F0F0F0)
- Only final results in chat; intermediate steps in history detail page
- Empty state: show guide text

### Style
- Light theme only (no dark mode)
- Primary color: #2196F3 (Material Blue)
- Success: #4CAF50, Error: #F44336
- Font sizes: title 18sp, body 14sp, caption 12sp
- Corner radius: 12dp (messages/cards), 8dp (buttons)

## Key Technical Details

- **Target SDK**: 36, **Min SDK**: 24
- **LLM API**: Zhipu AI's AutoGLM model via `https://open.bigmodel.cn/api/paas/v4`
- **Speech API**: Baidu Cloud ASR (vop.baidu.com)
- **Screenshot**: Requires Android 11 (API 30)+ for MediaProjection API
- **Token Management**: After each action, screenshots are removed from conversation history to save tokens
- **Action Format**: AI returns JSON with `_metadata: "do"` (action) or `_metadata: "finish"` (complete)
- **Supported Actions**: `launch` (launch app), `tap` (click at coordinates), `type` (input text), `swipe` (swipe screen), `back` (go back), `home` (return to home screen), `longpress` (long press), `doubletap` (double tap), `wait` (wait for delay ms)

## Key Dependencies

- **Retrofit + OkHttp**: API communication
- **Gson**: JSON parsing
- **QMUI**: UI components
- **Firebase App Distribution**: Beta testing distribution
- **DataStore Preferences**: Local key-value storage (declared, not yet used)
- **Room** (to be added): Local database for chat history

## Permissions Required

- `INTERNET` - API calls
- `QUERY_ALL_PACKAGES` - List installed apps
- `RECORD_AUDIO` - Voice input for speech recognition
- `SYSTEM_ALERT_WINDOW` - Floating window (circle button + execution card)
- `MANAGE_EXTERNAL_STORAGE` - Screenshot capture
- `WRITE_SECURE_SETTINGS` - Auto-restart accessibility service (requires ADB or system signature)

## Common Development Notes

- The app requires both Accessibility Service and Input Method to be enabled
- Settings page should show permission status for: accessibility, input method, audio recording, overlay window
- Coordinate system uses 0-1000 relative values (thousandths of screen width/height)
- Conversation context accumulates during execution - removing images after each step to manage token usage
- On finish or error, app automatically returns to foreground via `bringAppToForeground()`
- Error recovery: up to 4 consecutive errors allowed before terminating the task loop
- `wait` action has a maximum delay of 30 seconds to prevent indefinite blocking
- Screenshot capture: hide execution card overlay before capture, restore after
- Network errors: show error on execution card + failure message in chat list
