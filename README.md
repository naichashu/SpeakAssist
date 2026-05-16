# SpeakAssist

> 跟手机说人话，它替你点。

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84?logo=android&logoColor=white)
![targetSdk](https://img.shields.io/badge/targetSdk-36-3DDC84?logo=android&logoColor=white)
![Multimodal](https://img.shields.io/badge/LLM-AutoGLM--Phone-5b3df1)

一个 Android 自动化助手。说一句"在美团点一杯奶茶少糖"或"给老板发微信说我五分钟到"，它截屏、调用多模态大模型、解析出下一步动作、再通过无障碍服务真的去点——直到任务完成。

不是 demo。**真机跑通了微信、QQ、美团、淘宝、B站、抖音、支付宝（非支付场景）**。也踩遍了华为点击不响应、微信拦截 setText、vivo 压制 App 日志这些坑，都有方案在代码里。

---

## 它真的能做的事

| 类别 | 任务样例 |
|---|---|
| 即时通讯 | "给小王发微信说我马上到"、"在文件传输助手发'收到'"、"QQ 给妈妈发个语音留言"（语音消息按钮可点） |
| 外卖点单 | "在美团点一份酸辣粉"、"打开饿了么搜麻辣烫，下单第一家" |
| 信息查询 | "查一下上海明天天气"、"百度搜一下今天新闻"、"在地图里搜最近的星巴克" |
| 内容浏览 | "刷一下小红书首页"、"在B站搜罗翔最新视频"、"翻一下朋友圈" |
| App 跳转 | "打开支付宝扫一扫"、"打开相机"（启动后由模型决定下一步） |

**输入方式两种**：键盘文本输入，或长按"按住说话"语音输入。
**唤醒词**：开启悬浮窗 + 语音唤醒后，对手机说"小噜小噜"即可免动手启动。

---

## 它真的做不到的事（避免被误解）

为了诚实，下面这些**模型再聪明也不会做**，因为底层没有对应的动作：

- 把音量调到最大 / 调节屏幕亮度 / 切换 WiFi 蓝牙——没有系统级控制 API，需要进系统设置点滑块，模型可能会尝试但很不稳定
- 拨打电话——可启动拨号应用，但不会自动按号码
- 输入支付密码 / 短信验证码——prompt 里硬编码遇到立即 `finish` 交还用户，禁止自动尝试
- 滑块验证码 / 人脸识别 / 真人核验——同上
- WebView 内的 H5、小程序——节点树为空，OEM 还会过滤手势注入，会建议用户接管
- 自定义 SurfaceView 的游戏 / 视频内交互——无障碍取不到节点

简单原则：**屏幕上能看到、能用手指点的，它大概率能做；藏在系统底层或专门做了反自动化的，做不到。**

---

## 这个项目工程上有意思的地方

每一条都是真实踩过的坑，对应一个写在代码里的解决方案。

### 多模态任务执行闭环

`截图 + 用户意图 → 多模态大模型 → 单步动作 → 执行 → 再截图`，循环到 `finish` 为止。带：

- 最大 50 步上限，连续 4 次动作格式错误自动中断
- 历史消息里旧图片自动剥离，避免 token 爆炸
- 取消按钮可在任意中断点干净退出，连 HTTP 请求层都会取消

### 双路文本输入：解决"微信拦截 setText"

发现微信、QQ 的搜索框会拦截 `AccessibilityNodeInfo.ACTION_SET_TEXT`，标准 `setText` 静默失败，但模型并不知道。

方案：
- **直接模式**：先 `ACTION_SET_TEXT`，失败时降级走剪贴板 `ACTION_PASTE`
- **输入法模拟模式**：注册 SpeakAssist 自定义 IME，模型驱动键盘事件输入
- 设置页可一键切换；prompt 里硬编码"微信搜索框禁用 Type"，再叠一层保险

### 机型手势时序 profile：华为 / 荣耀的点击不响应

定位到 EMUI / MagicOS 会过滤 `dispatchGesture` 注入的 100ms 短点击——回调说"成功"，App 完全没反应。

| Profile | 命中机型 | tap 时长 | 兜底策略 |
|---|---|---|---|
| `default` | Pixel / 三星 / 未知品牌 | 180ms | 仅 dispatchGesture |
| `balanced` | 小米 / OPPO / vivo / 一加 / realme | 200ms | 仅 dispatchGesture |
| `strict` | **华为 / 荣耀** | 220ms | **`ACTION_CLICK` 为主路径**，dispatchGesture 失败再退回 ACTION_CLICK |

时长依据：Asakawa et al. 2017 真人 tap 实测均值 133ms ± 83ms。详细分析见 [`docs/适配调整/`](./docs/适配调整/)。

### 离线唤醒 + 在线识别的语音协同

- **唤醒词**：Vosk 离线，JSON grammar 把识别空间约束到"小噜小噜"或 `[unk]`——**模型在语法约束下无法吐出任意中文**，不会乱触发
- **命令识别**：百度语音 REST API，准确率高、支持长文本
- 唤醒到识别的麦克风切换有完整的释放 / 重新申请协议，避免设备级冲突
- "按住说话"路径关闭 VAD 自动停，由 UP 事件触发结束，60 秒兜底
- 已实现自适应 VAD（EMA 学习噪声底）和环境分析，详见 [`docs/语音噪声检测技术方案.md`](./docs/语音噪声检测技术方案.md)

### 悬浮窗执行架构

悬浮窗由 `AccessibilityService` 持有，不绑定 Activity，可跨任务生命周期常驻。

- 截图时自动 hide / restore，避免悬浮窗出现在自己的截图里
- 执行手势时取消芯片临时 detach，避免遮挡触摸
- `withOverlayHidden` 工具确保 hide/restore 必定配对（修过一次"执行中悬浮窗永久消失"的偶发 bug）

### WebView 容器识别 + 模型反馈

H5 / 小程序里 tap 失败时，跑一次有界 DFS（深度 ≤4、节点 ≤200）检测当前页面是否含 WebView。若有，把这个信息回传给模型，引导它直接 `swipe` 或提前 `finish`，避免在节点树为空的页面里循环 tap 浪费 token。

### 自建云端诊断日志收集

**不依赖第三方崩溃服务**。`AppLog` 替代 `android.util.Log`，在 App 进程内直接写文件 + 走自建上传服务。设计上要绕过 OEM 系统级日志压制——**vivo / 华为 / 荣耀在 OS 层用 `setprop log.tag M` + UID 白名单**全局屏蔽 3rd-party App 日志写入 kernel logcat buffer，靠 `Runtime.exec("logcat --pid=$pid")` 子进程收日志的方案在这些机型上不可能成功。

可选开关，默认关闭。详见 [`docs/服务器文档/`](./docs/服务器文档/)。

---

## 系统要求

| 项 | 要求 |
|---|---|
| Android 版本 | **11 (API 30) 或更高**——截图依赖 `AccessibilityService.takeScreenshot`，30 以下没这个 API |
| 网络 | 联网调用 LLM API 与百度语音 REST API |
| 存储 | APK 约 100MB（含 Vosk 离线模型），运行期占用 < 50MB |
| 麦克风 | 使用语音输入或唤醒词时必需 |
| 测试机型 | 已在 vivo V2203A (Android 14)、华为 Mate / 荣耀机型上跑通；其他 OEM 机型未广泛测试 |

> 模拟器**不推荐**：`takeScreenshot` 在大部分模拟器上不工作，无障碍手势注入也常异常。

---

## 必需权限

| 权限 | 用途 | 必需性 |
|---|---|---|
| 无障碍服务 | 获取屏幕节点、注入手势、截图 | **必需**，不开整个项目跑不起来 |
| 悬浮窗 | 圆形快捷按钮 + 执行卡片 | 不开则只能在主页面用，唤醒词也用不了 |
| 录音 | 语音输入、唤醒词识别 | 仅用文本输入时可不开 |
| SpeakAssist 输入法 | 切到"输入法模拟"模式时才需要 | 默认走"直接模式"，可不开 |
| 后台运行 / 电池不限制 | 防止任务执行中被系统杀掉 | **强烈建议开启**，否则切到后台会断 |

设置页有引导入口和实时状态显示。

---

## 快速开始

### 1. 构建 APK

目前没有发布预编译包，请自己用 Gradle 构建（release 用 debug key 签名，构建产物可以直接装手机分发，不需要管 keystore）：

```bash
git clone https://github.com/naichashu/SpeakAssist.git
cd SpeakAssist

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

也可以连着手机直接装：

```bash
./gradlew installDebug
```

### 2. 配置 API

首次启动后，从主页面顶部权限横幅或侧边栏进入"API 配置"。

#### LLM（必填）

应用默认配置好了智谱 AutoGLM 的 Base URL 和模型名，只需填 API Key：

| 字段 | 默认值 | 说明 |
|---|---|---|
| Base URL | `https://open.bigmodel.cn/api/paas/v4` | 已预设，智谱的 OpenAI 兼容端点 |
| Model Name | `autoglm-phone` | 已预设 |
| API Key | （空） | 在 [智谱开放平台](https://open.bigmodel.cn/) 申请 |

也可以换成任何 OpenAI 兼容的视觉多模态接口（如 MiniMax 的 abab vision）：把 Base URL 和 Model Name 改掉即可，应用会自动检测 provider 并适配请求参数。

#### 百度语音（可选）

只在需要"按住说话"或语音唤醒时才需要。在 [百度智能云语音控制台](https://console.bce.baidu.com/ai/#/ai/speech/overview/index) 申请短语音识别的 API Key 和 Secret Key。

> Key 全部保存在设备本地 DataStore，不会传到任何第三方。

### 3. 开权限

进设置页，按顺序：

1. 无障碍服务 → 跳转到系统设置 → 找到"SpeakAssist" → 开启
2. 悬浮窗权限 → 跳转到系统设置 → 开启
3. 录音权限 → 弹窗授予
4. （可选）SpeakAssist 输入法 → 跳转到输入法设置 → 启用并切换
5. 系统设置 → 应用管理 → SpeakAssist → 电池策略改成"无限制 / 不受限制"

### 4. 开始用

#### 文本模式（默认）

主界面输入框打字：

```
打开美团搜索酸辣粉，找一家评分最高的下单
```

点发送按钮。状态栏会出现执行卡片，模型一步步操作，完成后弹 Toast。

#### 语音模式

点输入框右侧的麦克风图标切到"按住说话"模式。

- 按住按钮 → 开始录音
- 说话
- 松开 → 识别结果填入输入框
- （手指上滑松开 = 取消，不发送）

#### 悬浮窗 + 唤醒词

设置页打开"悬浮窗"。一个圆形按钮会常驻屏幕。

- 点圆形按钮 → 调出语音输入
- 再打开"语音唤醒" → 直接对手机说"**小噜小噜**" → 唤醒后自动开始录音 → 说出任务

### 5. 查看历史

侧边栏 → "历史" → 看到每次任务的标题、步骤数、最终结果。点进去能看到每一步的截图、模型的 thinking 和实际执行的动作。

---

## 支持的动作（白名单）

模型只能输出下面这 10 种动作之一。其他都视为格式错误，触发重试或中断。

| 动作 | 含义 |
|---|---|
| `launch` | 启动指定 App |
| `tap` | 点击坐标（千分比） |
| `type` | 在当前聚焦控件输入文本 |
| `swipe` | 从一点滑到另一点 |
| `longpress` | 长按坐标 |
| `doubletap` | 双击坐标 |
| `back` | 系统返回键 |
| `home` | 系统 Home 键 |
| `wait` | 等待页面加载（最长 30 秒，最多连续 3 次） |
| `finish` | 任务结束，附带结果消息 |

坐标用千分比（0–1000）表示屏幕宽高，由 `ActionExecutor` 换算成绝对像素，避免不同分辨率写死。

---

## 常见问题

#### Q: 任务跑到一半卡住 / 模型一直 wait

可能是页面真的没加载完（弱网时常见），或目标元素被 WebView 包裹模型识别不到。看历史详情里模型的 thinking 通常能判断。设置里换更稳定的网络环境重试，或换到 Tap 后用人工接管。

#### Q: 华为 / 荣耀手机点击没反应

应用启动时会按 `Build.BRAND` 自动选 `strict` profile，tap 时长加到 220ms 并以 `ACTION_CLICK` 为主路径。如果仍然不响应，检查无障碍服务是否在"系统设置 → 安全 → 更多设置 → 无障碍"里被关闭——EMUI 偶尔会自动关闭。

#### Q: 微信搜索框输入文字失败

是微信主动拦截 `ACTION_SET_TEXT` 的已知行为。已实现两层兜底：剪贴板 paste、IME 模拟。在设置页把"文本输入方式"切到"输入法模拟（最强）"，并启用 SpeakAssist 输入法。

#### Q: 唤醒词不灵 / 误触

- 不灵：用 Vosk 的 grammar 模式约束识别，环境噪声大时会卡在 `[unk]`。在安静环境再试；或考虑唤醒词换更长更独特的发音
- 误触：grammar 模式下不会输出任意中文，不会误触；但相近发音的词（如"小卤小卤"）可能命中。需要换唤醒词的话改 `WakeWordListeningManager` 里的列表，重新构建

#### Q: 应用切到后台就停止执行

Android 厂商的后台清理策略问题。把 SpeakAssist 的电池策略设为"无限制 / 不受限制"。MIUI / EMUI / ColorOS 通常还要再勾一个"允许后台活动"。

#### Q: 模型说"已完成"但实际没成功

模型的判断边界问题。已经做了几层防御：每一步执行后检查页面变化、`finish` 前 prompt 要求自检、连续相同 thinking 视为卡死。但确实仍然可能 false positive。这种情况只能看历史详情里的最后几步截图人工判断。

#### Q: 我不想用智谱，能换别的模型吗

能。任何 OpenAI 兼容的视觉多模态 API 都行。在"API 配置"里改 Base URL 和 Model Name。已自动适配的 provider：智谱 (`bigmodel.cn`)、MiniMax (`minimax`)、其他走通用 OpenAI 协议。

#### Q: 想自己研究代码

请看 [`CLAUDE.md`](./CLAUDE.md)，那是项目架构的权威导览（写给 AI 代码助手用的，所以信息密度高、结构清晰）。

#### Q: API Key 安全吗

保存在 DataStore（App 私有目录，root 之外读不到），可通过设置页"导出 / 导入"备份到 SAF 选择的位置。不会上传到任何第三方。诊断日志默认关闭，开启后也只上传应用日志，不上传截图、聊天内容等。

---

## 已知边界

- **WebView / 小程序内点击**：节点树为空 + OEM 过滤双重夹击，`ACTION_CLICK` 兜底也救不回，会建议用户手动操作
- **支付 / 银行类强风控 App**：会校验 `SOURCE_INJECTED`，时长再长也过不去
- **验证码 / 滑块 / 人脸**：硬编码遇到立即 finish 交还用户，禁止自动尝试
- **自定义 View / SurfaceView**：无障碍取不到节点，定位失败
- **模型推理边界**：例如"找 7 分糖但店里没有"，模型偶尔不会自发放弃改选——属模型能力上限，`maxSteps=50` 兜底

---

## 项目结构

```
app/src/main/java/com/example/
├── speakassist/        主 Activity、设置页、历史页、API 配置页
├── service/            AccessibilityService、IME、机型 profile
├── input/              文本输入分发（直接 / IME 双路）
├── speech/             百度在线识别 + Vosk 离线唤醒 + 自适应 VAD
├── network/            LLM 客户端（OpenAI 兼容）
├── register/           ActionExecutor 动作解析与分发
├── ui/viewmodel/       ChatViewModel 任务循环
├── floating/           悬浮窗管理与执行卡片
├── diagnostics/        AppLog / LogcatTee / 云端上传
└── data/               Room + DataStore
```

---

## 文档导览

| 文件 | 内容 |
|---|---|
| [`CLAUDE.md`](./CLAUDE.md) | **架构总览**——最权威的代码导览 |
| [`AGENTS.md`](./AGENTS.md) | 贡献约定、提交规范、Review checklist |
| [`SpeakAssist_UI设计文档.md`](./SpeakAssist_UI设计文档.md) | UI 设计稿与交互规范 |
| [`docs/适配调整/`](./docs/适配调整/) | 机型点击适配的完整分析 |
| [`docs/语音唤醒词技术方案.md`](./docs/语音唤醒词技术方案.md) | 唤醒词离线识别设计 |
| [`docs/语音噪声检测技术方案.md`](./docs/语音噪声检测技术方案.md) | 自适应 VAD 算法 |
| [`docs/微信文件传输助手问题分析记录_2026-04-24.md`](./docs/微信文件传输助手问题分析记录_2026-04-24.md) | 微信 setText 拦截根因 |
| [`docs/查看型任务_finish过早与输出过短修复方案.md`](./docs/查看型任务_finish过早与输出过短修复方案.md) | 查看型任务的 finish 修正 |

---

## 技术栈

- **平台**：Android (minSdk 24, targetSdk 36)
- **语言**：Kotlin / JVM 11
- **核心 Android API**：`AccessibilityService`、`InputMethodService`、`dispatchGesture`、`takeScreenshot`、Foreground Service
- **网络**：OkHttp（自管 `suspendCancellableCoroutine` 包装）
- **存储**：Room（任务历史）+ DataStore（设置 / API Key）
- **大模型**：智谱 AutoGLM-Phone / 任何 OpenAI 兼容多模态接口
- **语音**：Vosk（离线唤醒）+ 百度语音 REST（在线识别）
- **UI**：Material Components 3 + 自定义悬浮窗（无 Compose）

---

## 致谢

- [智谱 AutoGLM-Phone](https://open.bigmodel.cn/) 提供多模态推理能力
- [Vosk](https://alphacephei.com/vosk/) 提供离线语音识别框架
- 百度智能云提供在线语音识别 REST API
- [Material Components for Android](https://github.com/material-components/material-components-android)

---

> 这是一个毕业设计项目，但写得不像毕设。代码、文档、模型适配都按生产标准做。欢迎 issue / PR。
