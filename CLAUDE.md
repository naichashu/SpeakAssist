# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SpeakAssist is an Android voice-controlled AI assistant app (毕业设计). It uses AutoGLM large language model to automate tasks on Android devices through natural language commands. The app captures screen content, sends it to the AI model, and executes actions (tap, launch apps, type text) based on AI responses.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean and build
./gradlew clean build
```

The debug APK is output to `app/build/intermediates/apk/debug/app-debug.apk`.

## Architecture

The app follows MVVM pattern with these key components:

### Core Flow
1. **MainActivity** (`app/src/main/java/com/example/speakassist/MainActivity.kt`) - Entry point, handles user input and initiates task execution
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

## Key Technical Details

- **Target SDK**: 36, **Min SDK**: 24
- **API**: Uses Zhipu AI's AutoGLM model via `https://open.bigmodel.cn/api/paas/v4`
- **Screenshot**: Requires Android 11 (API 30)+ for MediaProjection API
- **Token Management**: After each action, screenshots are removed from conversation history to save tokens
- **Action Format**: AI returns JSON with `_metadata: "do"` (action) or `_metadata: "finish"` (complete)
- **Supported Actions**: `launch` (launch app), `tap` (click at coordinates), `type` (input text), `swipe` (swipe screen), `back` (go back), `home` (return to home screen), `longpress` (long press), `doubletap` (double tap), `wait` (wait for delay ms)

## Permissions Required

- `INTERNET` - API calls
- `QUERY_ALL_PACKAGES` - List installed apps
- `SYSTEM_ALERT_WINDOW` - Floating window
- `MANAGE_EXTERNAL_STORAGE` - Screenshot capture
- `WRITE_SECURE_SETTINGS` - Auto-restart accessibility service (requires ADB or system signature)

## Common Development Notes

- The app requires both Accessibility Service and Input Method to be enabled
- Coordinate system uses 0-1000 relative values (thousandths of screen width/height)
- Conversation context accumulates during execution - removing images after each step to manage token usage
- On finish or error, app automatically returns to foreground via `bringAppToForeground()`
- Error recovery: up to 4 consecutive errors allowed before terminating the task loop
- `wait` action has a maximum delay of 30 seconds to prevent indefinite blocking
