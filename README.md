# SpeakAssist

> 用一句话说出你想干的事，让手机自己去操作。

一个面向 Android 的语音 / 文本驱动自动化助手。用户说"帮我在美团点一份麻辣烫"、"回复小王说我五分钟到"、"打开微信给爸爸发昨天那张图"，应用结合多模态大模型与无障碍服务，在真实 App 里替你完成。

本项目为毕业设计，但工程实现完整，从模型调用、手势注入、机型适配到风控规避都有覆盖。

---

## 它能做的事

| 场景 | 任务样例 |
|---|---|
| 外卖点单 | "帮我点一份酸辣粉" / "在美团找最近的茶饮店点一杯奶茶少糖" |
| 即时通讯 | "给小王发个微信说我马上到" / "在文件传输助手发一个'收到'" |
| 信息查询 | "看看今天有什么娱乐新闻" / "查一下上海明天天气" |
| App 控制 | "打开支付宝扫一扫" / "把声音调到最大" |
| 长任务 | "下拉刷新看看朋友圈有没有新消息" / "翻一下美团首页看看有什么活动" |

支持语音和文本两种输入，悬浮窗常驻，可用唤醒词"小噜小噜"免动手启动。

---

## 工程上解决了什么

这部分是项目的核心价值，每一条对应一个真实踩过的坑。

### 1. 多模态任务执行闭环

`截图 + 用户意图 → 智谱 AutoGLM → 单步动作 → 执行 → 再截图`，循环到 `finish` 为止。带：

- 最大 50 步上限、连续失败 4 次中断
- 历史消息中图片自动剥离以节省 token
- 取消按钮可在任意中断点干净退出（含 HTTP 请求层取消）

### 2. 双路文本输入：解决"微信拦截 setText"问题

发现微信、QQ 等 App 会拦截 `AccessibilityNodeInfo.ACTION_SET_TEXT`，常规 `setText` 直接失败。

方案：

- **直接模式**：`setText` → 失败时降级走剪贴板 `ACTION_PASTE`
- **输入法模拟模式**：注册自定义 IME，模型驱动键盘事件输入
- 用户在设置页一键切换，默认直接模式，对微信等场景引导切换

### 3. 机型手势时序 profile：华为 / 荣耀点击不响应

定位到 OEM ROM 对 `dispatchGesture` 注入的过滤层会吞掉 100ms 短点击。

方案：

| Profile | 命中机型 | tap 时长 | 备注 |
|---|---|---|---|
| `default` | Pixel / 三星 / 未知品牌 | 180ms | 真人 tap 分布 p70 |
| `balanced` | 小米 / OPPO / vivo / 一加 / realme | 200ms | p78，给 MIUI / ColorOS 留余量 |
| `strict` | 华为 / 荣耀 | 220ms + `ACTION_CLICK` 兜底 | p85，已知故障机型最大化容错 |

时长依据：Asakawa et al. 2017 真人 tap 实测均值 133ms ± 83ms + Android `getLongPressTimeout()` 500ms 长按阈值。

### 4. 语音协同：离线唤醒 + 在线识别

- **唤醒词识别**：Vosk 离线 + JSON grammar 约束，只识别"小噜小噜"或 `[unk]`，不会乱触发
- **命令识别**：百度 REST API，准确率高、支持长文本
- 唤醒到识别切换有完整的麦克风释放 / 重新申请协议，避免冲突
- 按住说话模式，VAD 不自动停，60 秒上限

### 5. 悬浮窗执行架构

悬浮窗由 `AccessibilityService` 持有，不绑定 Activity，跨任务生命周期。

- 截图时自动 hide / restore，避免悬浮窗出现在截图里
- 手势执行时取消芯片临时 detach，避免遮挡触摸事件
- `withOverlayHidden` 工具确保 hide / restore 配对，已规避一次"执行中悬浮窗消失"的 bug

### 6. WebView 容器识别

H5 / 小程序内的手势注入常被吞、且节点树几乎为空。点击失败时检测当前页面是否含 WebView，把上下文反馈给模型，引导其改用 swipe 或提前 finish。

---

## 技术栈

- **平台**：Android (minSdk 24, targetSdk 36)
- **语言**：Kotlin
- **核心 Android API**：AccessibilityService、InputMethodService、`dispatchGesture`、`takeScreenshot`
- **网络**：Retrofit + OkHttp
- **存储**：Room（任务历史）+ DataStore（设置）
- **大模型**：智谱 AutoGLM-Phone（多模态）
- **语音**：Vosk（离线唤醒）+ 百度语音 REST API（在线识别）
- **UI**：Material Components + 自定义悬浮窗

---

## 快速开始

### 环境

- Android Studio Hedgehog 或更新版本
- JDK 11
- Android SDK 36
- 实机或 Android 11+ 模拟器（截图依赖 `AccessibilityService.takeScreenshot`，需 API 30+）

### API Key 配置

应用首次启动会进入设置页，需要填入：

- 智谱 AutoGLM API Key（[开放平台申请](https://open.bigmodel.cn/)）
- 百度语音识别 API Key + Secret Key（[语音控制台申请](https://console.bce.baidu.com/ai/#/ai/speech/overview/index)）

Key 保存在本地 DataStore，不上传任何第三方。

### Vosk 模型

将 [`vosk-model-cn-0.1`](https://alphacephei.com/vosk/models) 解压后放入 `app/src/main/assets/`，启动时会自动复制到 App 内部存储。

### 构建

```bash
# Debug 包，含调试日志
./gradlew assembleDebug

# Release 包（debug key 签名，可直接分发到手机）
./gradlew assembleRelease
```

输出在 `app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`。

### 权限

首次运行需手动开启：

- 无障碍服务（执行手势、获取节点）
- SpeakAssist 输入法（开启输入法模拟模式时需要）
- 录音权限（语音输入）
- 悬浮窗权限（圆形快捷按钮）

设置页有引导入口，状态实时显示。

---

## 已知边界

为了诚实，列出当前**真的搞不定**的场景：

- **WebView / 小程序内点击**：节点树为空 + OEM 过滤双重夹击，`ACTION_CLICK` 兜底也救不回，会建议用户手动操作
- **支付 / 银行类强风控 App**：会校验 `SOURCE_INJECTED`，时长再长也过不去
- **验证码 / 滑块 / 人脸**：system prompt 已硬编码遇到立即 finish 交还用户，禁止自动尝试
- **自定义 View / SurfaceView**：无障碍节点树取不到，定位失败
- **AutoGLM 模型本身的推理边界**：例如"找 7 分糖但店里没有"，模型偶尔不会自发放弃改选——属模型能力上限，`maxSteps=50` 兜底

---

## 文档导览

| 文件 | 内容 |
|---|---|
| [`CLAUDE.md`](./CLAUDE.md) | 架构总览（最权威的代码导览） |
| [`SpeakAssist_UI设计文档.md`](./SpeakAssist_UI设计文档.md) | UI 设计稿与交互规范 |
| [`docs/适配调整/`](./docs/适配调整/) | 机型点击适配的完整分析与方案 |
| [`docs/语音唤醒词技术方案.md`](./docs/语音唤醒词技术方案.md) | 唤醒词离线识别设计 |
| [`docs/语音噪声检测技术方案.md`](./docs/语音噪声检测技术方案.md) | 自适应 VAD 算法设计 |
| [`docs/微信文件传输助手问题分析记录_2026-04-24.md`](./docs/微信文件传输助手问题分析记录_2026-04-24.md) | 微信 setText 拦截的根因分析 |

---

## 项目结构

```
app/src/main/java/com/example/
├── speakassist/        主 Activity、设置页、历史页
├── service/            AccessibilityService、IME、机型 profile
├── input/              文本输入分发层（直接 / IME 双路）
├── speech/             百度在线识别 + Vosk 离线唤醒
├── network/            智谱 AutoGLM 客户端
├── register/           ActionExecutor 动作解析与分发
├── ui/viewmodel/       ChatViewModel 任务循环
├── floating/           悬浮窗管理与执行卡片
└── data/               Room + DataStore
```

---

## 致谢

- 智谱 [AutoGLM-Phone](https://open.bigmodel.cn/) 提供多模态推理能力
- [Vosk](https://alphacephei.com/vosk/) 提供离线语音识别框架
- 百度智能云提供在线语音识别 REST API
