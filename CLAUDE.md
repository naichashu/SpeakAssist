# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SpeakAssist is an Android app that turns natural-language commands into on-device actions. The app collects the current screen state, sends the task plus screenshot context to Zhipu AutoGLM, parses the model response into a single action, executes it through Accessibility/input-method services, and persists the execution history locally.

The UI design source of truth is `SpeakAssist_UI设计文档.md`.

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run the full verification task set configured by Gradle
./gradlew build

# Run Android lint
./gradlew lint

# Run local JVM unit tests
./gradlew test

# Run a single JVM test class
./gradlew testDebugUnitTest --tests "com.example.YourTestClass"

# Run a single JVM test method
./gradlew testDebugUnitTest --tests "com.example.YourTestClass.yourTestMethod"

# Run instrumentation tests on a connected device/emulator
./gradlew connectedDebugAndroidTest

# Install debug build to a connected device
./gradlew installDebug
```

There are currently no committed test source sets under `app/src/test` or `app/src/androidTest`, so the single-test commands are the Gradle patterns to use when tests are added.

## Architecture

### End-to-end task loop

The core automation loop lives in `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`.

1. `MainActivity` collects text or voice input and starts a task.
2. `ChatViewModel` creates a `TaskSession` row in Room, initializes runtime-only message context, and publishes shared execution state through companion-object `StateFlow`s.
3. `MyAccessibilityService` provides the current foreground package and screenshots; screenshot capture temporarily hides the execution overlay.
4. `ModelClient` builds the system prompt plus multimodal user message, sends it to AutoGLM, and normalizes model output into `thinking` + `action`.
5. `ActionExecutor` converts the returned JSON/function-call-like payload into concrete gestures, app launches, text input, navigation actions, or waits.
6. After each step, `ChatViewModel` stores a `TaskStep` row, strips images from prior context to reduce token usage, waits for the UI to settle, and either continues, retries on malformed output, or finishes the session.

Key control limits in the loop: max 50 steps, up to 4 consecutive action-format errors, and a max `wait` duration of 30 seconds.

### Main subsystems

- `app/src/main/java/com/example/speakassist/MainActivity.kt`: main chat-style UI, permission banner, drawer navigation, voice/text input, and display of final task results.
- `app/src/main/java/com/example/network/ModelClient.kt`: Retrofit/OkHttp client for Zhipu AutoGLM, prompt assembly, screenshot JPEG/base64 conversion, and response parsing.
- `app/src/main/java/com/example/register/ActionExecutor.kt`: action parsing and execution bridge. Coordinates are model-facing thousandths of screen width/height and are converted to absolute pixels here.
- `app/src/main/java/com/example/service/MyAccessibilityService.kt`: global automation surface for gestures, global back/home actions, current-app tracking, screenshot capture, and floating-window lifecycle.
- `app/src/main/java/com/example/service/MyInputMethodService.kt`: custom IME used for model-driven text input.
- `app/src/main/java/com/example/input/`: text-input dispatch layer. `TextInputExecutor` routes model `type` actions to either `AccessibilityTextInput` (direct `setText` via `AccessibilityNodeInfo`) or `ImeTextInput` (simulated input through the custom IME), based on `SettingsPrefs.textInputMode` (`direct` | `ime_simulation`). `ImeActivationHelper` handles switching to SpeakAssist IME when the IME path is selected.
- `app/src/main/java/com/example/speech/`: speech I/O. Two complementary engines, each with a single responsibility:
  - `BaiduSpeechManager` — online REST recognizer (百度语音). Handles all general-purpose command recognition: button-initiated capture from `MainActivity`, floating-window button capture, and the post-wake-word command capture. Returns null for empty/blank `result` so callers see `onError("未识别到语音")` instead of dispatching empty text.
  - `VoskRecognizerManager` + `WakeWordListeningManager` — offline keyword-spotter only. `WakeWordListeningManager` builds a JSON grammar from the wake-word list (e.g. `["小 噜 小 噜", ..., "[unk]"]`) and passes it to Vosk's 3-arg `Recognizer` constructor, which **constrains output to the grammar phrases or `[unk]`** — Vosk cannot emit arbitrary Chinese text. On wake-word detection it fires `onWakeWordDetected()`, breaks the loop, and releases the mic. It does NOT do command recognition; callers are expected to start `BaiduSpeechManager` next.
  - Vosk requires the `vosk-model-small-cn-0.22` folder under `app/src/main/assets/`; on first use it is copied to app internal storage. Grammar support requires a lookahead model (the folder must contain `graph/Gr.fst` + `graph/HCLr.fst`, not a prebuilt `HCLG.fst`). The wake-word loop is gated by `SettingsPrefs.voiceWakeEnabled`.

### Persistence and UI state

- `app/src/main/java/com/example/data/AppDatabase.kt` defines a Room database with `TaskSession` and `TaskStep` tables. History screens read directly from this DB.
- `app/src/main/java/com/example/data/SettingsPrefs.kt` uses DataStore Preferences. Current keys: `floating_window_enabled`, `text_input_mode` (`direct` | `ime_simulation`), `voice_wake_enabled`.
- `HistoryActivity` shows recorded task sessions; `HistoryDetailActivity` shows per-step execution logs and AI thinking.

### Floating window flow

The floating-window implementation is service-owned, not activity-owned.

- `FloatingWindowManager` is created from `MyAccessibilityService.onServiceConnected()`.
- It watches `SettingsPrefs` to decide whether the circle button should exist.
- It watches `ChatViewModel.executionState` to hide the circle during execution, show/update the execution card, and restore the circle after completion.
- During execution, an `ExecutionCancelChipView` is shown beside the execution card; tapping it calls `ChatViewModel.requestCancel()` to stop the loop cleanly after the current step finishes.
- Voice-wake handoff: `WakeWordListeningManager.onWakeWordDetected` → `stopWakeWordListening()` (releases mic) → `BaiduSpeechManager.start()` (captures command). Baidu's `onResult` dispatches to `ChatViewModel.executeTaskLoop()`; `onError` shows a toast-like chip and restarts wake-word listening via `syncWakeWordListening()`. Manual floating-window capture (tapping the circle when idle) also calls `BaiduSpeechManager.start()` directly.
- Voice commands started from the floating window run the same `ChatViewModel.executeTaskLoop()` path as commands started from `MainActivity`.

### Prompt and action contract

The AutoGLM system prompt is stored in `app/src/main/res/values/strings.xml` as `system_prompt_template`.

Important implementation detail: the prompt still mentions a broader action vocabulary, but `ActionExecutor` only handles these actions today:

- `launch`
- `tap`
- `type`
- `swipe`
- `back`
- `home`
- `longpress`
- `doubletap`
- `wait`
- `finish`

If you change the prompt contract, keep `strings.xml`, `ModelClient.parseResponse()`, and `ActionExecutor` aligned.

## Platform and runtime notes

- Target SDK 36, min SDK 24, Kotlin/JVM target 11.
- Screenshot capture uses `AccessibilityService.takeScreenshot`, so full screenshot support requires Android 11+.
- The app depends on Accessibility service, custom input method, record-audio permission, and overlay permission for the full experience.
- `AndroidManifest.xml` includes broad automation-related permissions and registers both the accessibility service and IME.

## Notes from other repository docs

- `README.md` is a short Chinese overview of the Android app. `CLAUDE.md` (this file) is the authoritative architectural reference.
- `SpeakAssist_UI设计文档.md` is the UI design source of truth.
- `docs/语音唤醒词技术方案.md` documents the offline wake-word design used by `WakeWordListeningManager`.
- `docs/代码审查修复记录_2026-04-21.md` logs the bugs found and fixed in a 2026-04-21 code-review pass (截图崩溃/主线程阻塞/空结果派发/悬浮窗残留 Runnable 等). Read this before touching `ChatViewModel`, `FloatingWindowManager`, or the input-dispatch layer to avoid reintroducing resolved issues.
- No Cursor rules, `.cursorrules`, or Copilot instruction files are present in the repository at the time of writing.
