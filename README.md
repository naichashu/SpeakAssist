# SpeakAssist
毕业设计：基于语音控制的Android智能助手APP

大模型：https://github.com/zai-org/Open-AutoGLM?tab=readme-ov-file

# 1.使用API

重新使用项目：下次打开终端想运行项目时，只需执行以下 3 步即可快速恢复环境：

（1）进入项目目录： cd Open-AutoGLM

（2）激活虚拟环境： source venv/bin/activate   # Windows: venv\Scripts\activate

（3）直接运行项目脚本： python3 main.py --base-url https://open.bigmodel.cn/api/paas/v4 --model "autoglm-phone" --apikey "3400b28973dbc4f62558def1f2053b96.kiUwPwhwyLvULvWt" "需要的操作"

# 2.数据类型

类型 1：任务结束型 JSON（_metadata: "finish"）

{
  "_metadata": "finish",
  "message": "自动化任务执行成功，已完成所有步骤"
}

类型 2：自动化动作执行型 JSON（_metadata: "do"）

// 示例 1：启动 App（action: "launch"）
{
  "_metadata": "do",
  "action": "launch",
  "app": "微信"
}

// 示例 2：点击屏幕（action: "tap"）
{
  "_metadata": "do",
  "action": "tap",
  "x1": 100.0,
  "y1": 200.0
}

// 示例 3：输入文本（action: "type"）
{
  "_metadata": "do",
  "action": "type",
  "text": "这是要输入的内容"
}

// 示例 4：滑动屏幕（action: "swipe"）
{
  "_metadata": "do",
  "action": "swipe",
  "x1": 500.0,
  "y1": 800.0,
  "x2": 500.0,
  "y2": 300.0
}
