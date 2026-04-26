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
- `app/src/main/java/com/example/network/ModelClient.kt`: pure OkHttp client for Zhipu AutoGLM. Uses `suspendCancellableCoroutine` so HTTP requests are cancelled when the coroutine is cancelled. System prompt + multimodal user message assembly, screenshot JPEG/base64 conversion, and response parsing into `thinking` + `action`. Handles `<answer>` tag parsing, `finish()`/`do()` function-call fallback, and JSON extraction.
- `app/src/main/java/com/example/register/ActionExecutor.kt`: action parsing and execution bridge. Coordinates are model-facing thousandths of screen width/height and are converted to absolute pixels here.
- `app/src/main/java/com/example/service/MyAccessibilityService.kt`: global automation surface for gestures, global back/home actions, current-app tracking, screenshot capture, and floating-window lifecycle.
- `app/src/main/java/com/example/service/MyInputMethodService.kt`: custom IME used for model-driven text input.
- `app/src/main/java/com/example/input/`: text-input dispatch layer. `TextInputExecutor` routes model `type` actions to either `AccessibilityTextInput` (direct `setText` via `AccessibilityNodeInfo`) or `ImeTextInput` (simulated input through the custom IME), based on `SettingsPrefs.textInputMode` (`direct` | `ime_simulation`). `ImeActivationHelper` handles switching to SpeakAssist IME when the IME path is selected.
- `app/src/main/java/com/example/speech/`: speech I/O. Two complementary engines, each with a single responsibility:
  - `BaiduSpeechManager` — online REST recognizer (百度语音). Handles all general-purpose command recognition: button-initiated capture from `MainActivity`, floating-window button capture, and the post-wake-word command capture. Returns null for empty/blank `result` so callers see `onError("未识别到语音")` instead of dispatching empty text.
  - `VoskRecognizerManager` + `WakeWordListeningManager` — offline keyword-spotter only. `WakeWordListeningManager` builds a JSON grammar from the wake-word list (e.g. `["小 噜 小 噜", ..., "[unk]"]`) and passes it to Vosk's 3-arg `Recognizer` constructor, which **constrains output to the grammar phrases or `[unk]`** — Vosk cannot emit arbitrary Chinese text. On wake-word detection it fires `onWakeWordDetected()`, breaks the loop, and releases the mic. It does NOT do command recognition; callers are expected to start `BaiduSpeechManager` next.
  - Vosk requires the `vosk-model-cn-0.1` folder under `app/src/main/assets/`. The model is copied to app internal storage on first use. Grammar support requires a lookahead model (the folder must contain `graph/Gr.fst` + `graph/HCLr.fst`, not a prebuilt `HCLG.fst`). The wake-word loop is gated by `SettingsPrefs.voiceWakeEnabled`. **Vosk grammar constraint is the human-voice filter** — the recognizer cannot emit arbitrary text, only grammar phrases or `[unk]`. **Do not skip frames before sending to Vosk** — `acceptWaveForm` requires every frame to maintain its state machine.

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

### Noise detection architecture

The codebase has a noise detection architecture documented in `docs/语音噪声检测技术方案.md`. The key design decisions:

- **No skip-frames before Vosk**: Vosk needs every frame. Noise detection must not skip `acceptWaveForm` calls.
- **Adaptive VAD**: Instead of fixed RMS thresholds (500 for wake word, 800 for voice input), the plan uses exponential moving average (EMA) to track noise floor and dynamically adjust thresholds.
- **Two scenes, different strategies**: Wake word scene — pre-learn environment, adapt matching threshold, keep Vosk full-frame; Voice input scene — pre-learn environment, adapt VAD params (energy threshold / silence duration), expose noise level to UI.
- **Zero new dependencies**: All noise detection is pure Kotlin algorithms (STE + ZCR + low-frequency ratio). No new native libraries or ML models.

### ActionExecutor state management

`ActionExecutor` maintains per-step failure tracking. After any step, if an action fails it:
1. Writes a placeholder assistant message (prevents model from treating its own bad output as a template)
2. Feeds back a structured error text to the model with correction guidance
3. Increments the failure counter for that action type
4. Retries with a different approach (up to 4 total retries per session)

The `extractActionType` regex uses separate patterns for quoted values (spaces allowed inside quotes) vs bare values (stops at whitespace). The `normalizeActionType` function handles all spelling variants: `longpress`/`long_press`/`long press` → `longpress`, `doubletap`/`double_tap`/`double tap` → `doubletap`.

### Text input dispatch

`TextInputExecutor` routes model `type` actions. When in `direct` mode, `AccessibilityTextInput` tries `ACTION_SET_TEXT` first, then falls back to clipboard paste (for WeChat and similar apps that intercept `setText`). When in `ime_simulation` mode, `ImeTextInput` simulates keystrokes through the custom IME. **`AccessibilityTextInput` uses stack-based iteration with explicit node recycling** — the old recursive DFS leaked `AccessibilityNodeInfo` (native memory, not JVM GC managed). Do not revert to recursive traversal.

## Platform and runtime notes

- Target SDK 36, min SDK 24, Kotlin/JVM target 11.
- Screenshot capture uses `AccessibilityService.takeScreenshot`, so full screenshot support requires Android 11+.
- The app depends on Accessibility service, custom input method, record-audio permission, and overlay permission for the full experience.
- `AndroidManifest.xml` includes broad automation-related permissions and registers both the accessibility service and IME.

## Notes from other repository docs

- `README.md` is a short Chinese overview of the Android app. `CLAUDE.md` (this file) is the authoritative architectural reference.
- `SpeakAssist_UI设计文档.md` is the UI design source of truth.
- `docs/` contains all technical design documents:
  - `语音唤醒词技术方案.md` — offline wake-word design used by `WakeWordListeningManager`
  - `语音噪声检测技术方案.md` — adaptive VAD and noise detection (已实现: `EnvironmentAnalyzer` 用于唤醒词场景，`AdaptiveVad` 用于语音输入场景)
  - `查看型任务_finish过早与输出过短修复方案.md` — 查看型任务的 finish 条件修正和结构化消息规范
  - `微信文件传输助手问题分析记录_2026-04-24.md` — 微信搜索框无法直接 setText 的根因分析
  - `代码审查报告_2026-04-25.md` — 2026-04-25 代码审查问题列表
- No Cursor rules, `.cursorrules`, or Copilot instruction files are present in the repository.

## Known memory/safety invariants

These are invariants that must not be violated when modifying code:

- **`AccessibilityNodeInfo`**: Every node obtained via `getChild()`, `findFocus()`, or `rootInActiveWindow` must be closed/recycled exactly once. `findFirstEditableNode` always closes `root` during traversal; caller closes `root` or the returned target depending on the path. Use `it !== root` identity check before closing to avoid double-close.
- **Coroutines**: Any `CoroutineScope` created for a long-lived component must be cancelled in `destroy()`. `BaiduSpeechManager` uses a class-level `scope` (SupervisorJob + IO) cancelled in `destroy()`.
- **`Handler`/`Runnable`**: When reassigning a `Handler` postDelayed Runnable (e.g. `postTaskCleanup`), always remove the old one before assigning the new one.
- **`ValueAnimator`**: Before starting a new animator, always cancel the previous one to prevent animation conflicts and stale callbacks.
