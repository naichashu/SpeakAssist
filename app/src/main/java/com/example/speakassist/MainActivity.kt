package com.example.speakassist

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.SettingsPrefs
import com.example.input.ImeActivationHelper
import com.example.input.TextInputMode
import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService
import com.example.speech.BaiduSpeechConfig
import com.example.speech.BaiduSpeechCredentials
import com.example.speech.BaiduSpeechManager
import com.example.speech.NoiseLevel
import com.example.ui.adapter.ChatMessageAdapter
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.widget.WaveformView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * MainActivity - 应用主界面
 *
 * 负责：
 * 1. 顶部导航栏 + 侧栏菜单
 * 2. 检查并提示权限状态
 * 3. 显示聊天消息列表
 * 4. 处理用户输入：文字（默认）+ 按住说话（语音模式）
 * 5. 发起任务执行
 *
 * 输入区交互：
 * - 默认键盘模式：[btnInputMode 麦克风] [文本输入框] [发送]
 * - 切到语音模式：[btnInputMode 键盘] [按住 说话按钮] （发送按钮 gone）
 * - 长按按钮录音 → 中央气泡浮起 + 音波动画；松手送识别；上滑超阈值松手则取消
 */
class MainActivity : AppCompatActivity() {

    enum class InputMode { KEYBOARD, VOICE }

    // ==================== 视图组件 ====================
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var permissionBanner: View
    private lateinit var tvPermissionHint: TextView
    private lateinit var btnPermissionSettings: Button
    private lateinit var rvChatMessages: androidx.recyclerview.widget.RecyclerView
    private lateinit var emptyState: View

    // 输入区
    private lateinit var btnInputMode: ImageButton                 // 麦克风/键盘 切换
    private lateinit var inputArea: FrameLayout                    // tilInput 与 btnPressToTalk 的容器
    private lateinit var tilInput: TextInputLayout                 // 文本输入框包装
    private lateinit var etInput: TextInputEditText                // 文本输入框
    private lateinit var btnPressToTalk: Button                    // 按住说话按钮
    private lateinit var noiseIndicator: View                      // 噪声指示小圆点
    private lateinit var btnSend: ImageButton                      // 发送按钮

    // 中央录音气泡
    private lateinit var voiceBubble: View
    private lateinit var ivBubbleIcon: ImageView
    private lateinit var waveformView: WaveformView
    private lateinit var tvBubbleHint: TextView

    private var noiseLevelJob: Job? = null

    // ==================== 数据和适配器 ====================
    private lateinit var chatAdapter: ChatMessageAdapter
    private val chatMessages = mutableListOf<ChatMessageAdapter.ChatMessageItem>()

    // ==================== 业务逻辑 ====================
    private lateinit var speechManager: BaiduSpeechManager
    private lateinit var chatViewModel: ChatViewModel

    private var inputMode: InputMode = InputMode.KEYBOARD
    private var pressToTalkController: PressToTalkController? = null

    // 最新的百度凭据快照：credentialsFlow collect 时写入，按住说话 ACTION_DOWN 同步读。
    @Volatile
    private var latestBaiduCredentials: BaiduSpeechCredentials? = null

    // ==================== 权限请求 ====================
    // 录音权限请求回调：仅 Toast 提示是否授予，不再自动 start。
    // 按住模式下用户被弹出系统对话框授权后，需要主动再按一次按钮。
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, R.string.voice_permission_granted_hint, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.voice_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_CHAT_MESSAGES = "chat_messages"

        /** 上滑超过该距离视为"将取消" */
        private const val SWIPE_UP_CANCEL_THRESHOLD_DP = 50f

        /** 按住时长 < 300ms 视为误触 */
        private const val MIN_PRESS_DURATION_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyAccessibilityService.resumeFloatingOverlays()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        setupToolbar()
        setupDrawer()
        checkPermissions()
        setupChatList()

        // Activity 重建时恢复聊天记录（避免横竖屏切换丢失）
        savedInstanceState?.getParcelableArrayList<ChatMessageAdapter.ChatMessageItem>(KEY_CHAT_MESSAGES)?.let { restored ->
            chatMessages.clear()
            chatMessages.addAll(restored)
            chatAdapter.submitList(chatMessages.toList())
            rvChatMessages.scrollToPosition(chatMessages.size - 1)
            updateEmptyState()
        }

        setupSpeechManager()
        setupInputArea()
        setupInputModeToggle()
        setupPressToTalk()
        setupWindowInsets()
        setupBackPressedHandler()
        observeExecutionState()
    }

    /**
     * 初始化视图组件
     */
    private fun initViews() {
        drawerLayout = findViewById(R.id.main)
        toolbar = findViewById(R.id.toolbar)
        permissionBanner = findViewById(R.id.permissionBanner)
        tvPermissionHint = findViewById(R.id.tvPermissionHint)
        btnPermissionSettings = findViewById(R.id.btnPermissionSettings)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        emptyState = findViewById(R.id.emptyState)

        btnInputMode = findViewById(R.id.btnInputMode)
        inputArea = findViewById(R.id.inputArea)
        tilInput = findViewById(R.id.tilInput)
        etInput = findViewById(R.id.etInput)
        btnPressToTalk = findViewById(R.id.btnPressToTalk)
        noiseIndicator = findViewById(R.id.noiseIndicator)
        btnSend = findViewById(R.id.btnSend)

        voiceBubble = findViewById(R.id.voiceBubble)
        ivBubbleIcon = findViewById(R.id.ivBubbleIcon)
        waveformView = findViewById(R.id.waveformView)
        tvBubbleHint = findViewById(R.id.tvBubbleHint)
    }

    /**
     * 设置Toolbar
     */
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val ivMenu = findViewById<android.widget.ImageView>(R.id.ivMenu)
        ivMenu?.setOnClickListener {
            clearInputFocus()
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * 设置侧栏菜单
     */
    private fun setupDrawer() {
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { menuItem ->
            clearInputFocus()
            when (menuItem.itemId) {
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                }
                R.id.nav_api_config -> {
                    startActivity(Intent(this, ApiConfigActivity::class.java))
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.nav_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    /**
     * 检查权限状态
     */
    private fun checkPermissions() {
        lifecycleScope.launch {
            val accessibilityEnabled = isAccessibilityServiceEnabled()
            val inputMethodEnabled = MyInputMethodService.isEnabled(this@MainActivity)
            val textInputMode = SettingsPrefs.textInputMode(this@MainActivity).first()
            val audioPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this@MainActivity)
            } else {
                true
            }

            val missingPermissions = mutableListOf<String>()
            if (!accessibilityEnabled) missingPermissions.add(getString(R.string.permission_accessibility))
            if (textInputMode == TextInputMode.IME_SIMULATION && !inputMethodEnabled) {
                missingPermissions.add(getString(R.string.permission_input_method))
            }
            if (!audioPermission) missingPermissions.add(getString(R.string.permission_audio))
            if (!overlayPermission) missingPermissions.add(getString(R.string.permission_overlay))

            if (missingPermissions.isNotEmpty()) {
                permissionBanner.visibility = View.VISIBLE
                tvPermissionHint.text = getString(R.string.permission_required_hint) + "\n" +
                        missingPermissions.joinToString("、")
            } else {
                permissionBanner.visibility = View.GONE
            }

            Log.d(
                "MainActivity",
                "权限状态: 无障碍=$accessibilityEnabled, 输入法=$inputMethodEnabled, 输入方式=$textInputMode, 录音=$audioPermission, 悬浮窗=$overlayPermission"
            )

            btnPermissionSettings.setOnClickListener {
                openAccessibilitySettings()
            }
        }
    }

    /**
     * 设置聊天消息列表
     */
    private fun setupChatList() {
        chatAdapter = ChatMessageAdapter()
        rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        updateEmptyState()
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
        rvChatMessages.visibility = if (chatAdapter.itemCount == 0) View.GONE else View.VISIBLE
    }

    /**
     * 设置语音识别。
     *
     * 与悬浮窗的差异：
     * - 主界面 PressToTalkController 调 start(autoStopOnSilence=false) → 录音不会被 VAD 静音自动结束
     * - 悬浮窗 / 唤醒词链路调默认 start() → autoStopOnSilence=true，VAD 自动停
     */
    private fun setupSpeechManager() {
        speechManager = BaiduSpeechManager(this)
        // 百度凭据来自 DataStore（用户在 API 配置页填写），用 Flow 观察以便用户保存后立即生效
        lifecycleScope.launch {
            BaiduSpeechConfig.credentialsFlow(this@MainActivity).collect { credentials ->
                latestBaiduCredentials = credentials
                speechManager.setCredentials(credentials.apiKey, credentials.secretKey)
            }
        }

        speechManager.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                // 主界面用中央气泡作为"正在录音"的主反馈，不再额外弹 Toast；
                // 避免 Toast 叠在气泡上重复打扰。
            }

            override fun onResult(text: String) {
                runOnUiThread {
                    // 兜底：60s maxRecordingDurationMs 触发的自动结束会在用户仍按住时
                    // 直接到这里。手动 stop() 路径下气泡已被 handleUp 隐藏，再 hide 一次是 no-op。
                    hideVoiceBubble()
                    // 识别成功后自动切回键盘模式，让用户立刻看到回填文字、可编辑
                    if (inputMode == InputMode.VOICE) {
                        switchInputMode(InputMode.KEYBOARD)
                    }
                    etInput.setText(text)
                    etInput.setSelection(text.length)
                    btnSend.isEnabled = text.isNotBlank()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    // 同上：兜底关掉可能仍在显示的气泡（罕见，但避免出错时气泡卡住）
                    hideVoiceBubble()
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onEnd() {
                // 录音流程结束（识别请求前）。气泡的隐藏由 PressToTalkController
                // 在 ACTION_UP/CANCEL 主动控制，这里不重复操作 UI。
            }

            override fun onVolumeChanged(volume: Int) {
                runOnUiThread {
                    // 气泡音波（仅在按住模式且气泡可见时驱动）
                    if (voiceBubble.visibility == View.VISIBLE) {
                        waveformView.setVolume(volume)
                    }
                    // 按住按钮的轻微缩放律动
                    if (btnPressToTalk.isPressed) {
                        val scale = 1f + (volume.coerceIn(0, 100) / 400f)  // 1.0–1.25
                        btnPressToTalk.animate().scaleX(scale).scaleY(scale).setDuration(80L).start()
                    }
                }
            }
        })

        // 订阅噪声级别 → 右上小圆点变色（低通滤波：颜色稳定 1.5s 才切，避免说话时 SNR 跳变频繁闪烁）
        noiseLevelJob?.cancel()
        noiseLevelJob = lifecycleScope.launch {
            var lastAppliedLevel: NoiseLevel? = null
            var stableSince = 0L
            speechManager.noiseLevel.collect { level ->
                if (level == lastAppliedLevel) {
                    stableSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - stableSince >= 1500L) {
                    stableSince = System.currentTimeMillis()
                    lastAppliedLevel = level
                    runOnUiThread { applyNoiseLevel(level) }
                }
            }
        }
    }

    /**
     * 设置输入区域：文本框监听 + 发送按钮 + 回车发送。
     */
    private fun setupInputArea() {
        etInput.addTextChangedListener {
            btnSend.isEnabled = !it.isNullOrBlank()
        }

        btnSend.setOnClickListener {
            val inputText = etInput.text?.toString()?.trim()
            if (!inputText.isNullOrBlank()) {
                sendMessage(inputText)
            }
        }

        etInput.setOnEditorActionListener { _, _, _ ->
            btnSend.performClick()
            true
        }
    }

    /**
     * 输入模式切换按钮点击：键盘 ↔ 语音
     */
    private fun setupInputModeToggle() {
        btnInputMode.setOnClickListener {
            switchInputMode(if (inputMode == InputMode.KEYBOARD) InputMode.VOICE else InputMode.KEYBOARD)
        }
    }

    /**
     * 给"按住说话"按钮装上触摸状态机
     */
    private fun setupPressToTalk() {
        val threshold = resources.displayMetrics.density * SWIPE_UP_CANCEL_THRESHOLD_DP
        pressToTalkController = PressToTalkController(btnPressToTalk, threshold)
        btnPressToTalk.setOnTouchListener(pressToTalkController)
    }

    /**
     * 切换输入模式：键盘 ↔ 语音。
     * - KEYBOARD：tilInput 可见，btnPressToTalk gone，btnSend 可见，btnInputMode 显示麦克风
     * - VOICE：tilInput gone，btnPressToTalk 可见，btnSend gone，btnInputMode 显示键盘；自动收键盘
     */
    private fun switchInputMode(target: InputMode) {
        if (inputMode == target) return
        inputMode = target
        when (target) {
            InputMode.KEYBOARD -> {
                tilInput.visibility = View.VISIBLE
                btnPressToTalk.visibility = View.GONE
                btnSend.visibility = View.VISIBLE
                btnInputMode.setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            InputMode.VOICE -> {
                clearInputFocus()
                tilInput.visibility = View.GONE
                btnPressToTalk.visibility = View.VISIBLE
                btnSend.visibility = View.GONE
                btnInputMode.setImageResource(R.drawable.ic_keyboard)
            }
        }
    }

    /**
     * 显示中央录音气泡：弹出 + 启动音波 + 噪声指示。
     * 在 PressToTalkController.handleDown 调用。
     */
    private fun showVoiceBubble() {
        setVoiceBubbleCancelMode(false)
        voiceBubble.visibility = View.VISIBLE
        voiceBubble.alpha = 0f
        voiceBubble.scaleX = 0.85f
        voiceBubble.scaleY = 0.85f
        voiceBubble.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(150L)
            .start()
        waveformView.start()
        noiseIndicator.visibility = View.VISIBLE
        applyNoiseLevel(speechManager.noiseLevel.value)
    }

    /**
     * 隐藏中央录音气泡：淡出 + 停止音波 + 噪声指示。
     * 在 PressToTalkController.handleUp/handleCancel 以及 onPause/onDestroy 调用。
     */
    private fun hideVoiceBubble() {
        if (voiceBubble.visibility != View.VISIBLE) return
        waveformView.stop()
        voiceBubble.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(120L)
            .withEndAction {
                voiceBubble.visibility = View.GONE
                voiceBubble.scaleX = 1f
                voiceBubble.scaleY = 1f
                voiceBubble.alpha = 1f
            }
            .start()
        noiseIndicator.visibility = View.GONE
    }

    /**
     * 切换气泡的"将取消"态视觉。上滑切 true，滑回切 false。
     */
    private fun setVoiceBubbleCancelMode(willCancel: Boolean) {
        if (willCancel) {
            voiceBubble.setBackgroundResource(R.drawable.bg_voice_bubble_cancel)
            ivBubbleIcon.setImageResource(R.drawable.ic_voice_cancel)
            tvBubbleHint.setText(R.string.voice_release_to_cancel)
        } else {
            voiceBubble.setBackgroundResource(R.drawable.bg_voice_bubble)
            ivBubbleIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
            tvBubbleHint.setText(R.string.voice_swipe_up_hint)
        }
    }

    /**
     * 「按住说话」按钮的触摸状态机。
     *
     * 状态：
     * - DOWN：检查权限/凭据/任务态，通过则 start(autoStopOnSilence=false) + 弹气泡
     * - MOVE：上滑超过阈值切"将取消"态，滑回切回"将提交"
     * - UP：松手前 willCancel=true 走 cancel()，否则 stop() 让录音送识别
     * - CANCEL：系统中断（来电、抢焦点）等同 cancel
     *
     * 防误触：按压 < 300ms 视为误操作，cancel + Toast。
     * 兜底：BaiduSpeechManager 内 HOLD_TO_TALK_MAX_DURATION_MS = 60s 防忘记松手。
     */
    private inner class PressToTalkController(
        private val button: Button,
        private val swipeUpThresholdPx: Float,
    ) : View.OnTouchListener {

        private var downY = 0f
        private var downTimeMs = 0L
        private var isRecording = false
        private var willCancel = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> handleDown(ev)
                MotionEvent.ACTION_MOVE -> handleMove(ev)
                MotionEvent.ACTION_UP -> handleUp()
                MotionEvent.ACTION_CANCEL -> handleCancel()
            }
            return true
        }

        private fun handleDown(ev: MotionEvent) {
            // 任务执行中拒绝，避免与 ChatViewModel 状态冲突
            if (ChatViewModel.executionState.value.isRunning) {
                Toast.makeText(this@MainActivity, R.string.task_already_running, Toast.LENGTH_SHORT).show()
                return
            }
            // 凭据未配则引导去 API 配置页
            if (latestBaiduCredentials?.isValid != true) {
                Toast.makeText(this@MainActivity, R.string.api_config_voice_needs_baidu, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@MainActivity, ApiConfigActivity::class.java))
                return
            }
            // 权限未给则申请；用户授予后需主动再按一次
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }

            downY = ev.rawY
            downTimeMs = System.currentTimeMillis()
            willCancel = false
            isRecording = true

            button.isPressed = true
            button.isActivated = false
            button.text = getString(R.string.voice_release_to_finish)

            showVoiceBubble()
            speechManager.start(autoStopOnSilence = false)
        }

        private fun handleMove(ev: MotionEvent) {
            if (!isRecording) return
            val dy = downY - ev.rawY  // 上滑为正
            val nextWillCancel = dy > swipeUpThresholdPx
            if (nextWillCancel != willCancel) {
                willCancel = nextWillCancel
                button.isActivated = willCancel
                button.text = getString(
                    if (willCancel) R.string.voice_release_to_cancel
                    else R.string.voice_release_to_finish
                )
                setVoiceBubbleCancelMode(willCancel)
            }
        }

        private fun handleUp() {
            if (!isRecording) return
            isRecording = false
            resetButtonVisual()
            hideVoiceBubble()

            val pressDuration = System.currentTimeMillis() - downTimeMs
            if (pressDuration < MIN_PRESS_DURATION_MS) {
                speechManager.cancel()
                Toast.makeText(this@MainActivity, R.string.voice_press_too_short, Toast.LENGTH_SHORT).show()
                return
            }
            if (willCancel) {
                speechManager.cancel()
            } else {
                // stop 让录音循环退出，已录音频送识别 → onResult 回填
                speechManager.stop()
            }
        }

        private fun handleCancel() {
            if (!isRecording) return
            isRecording = false
            resetButtonVisual()
            hideVoiceBubble()
            speechManager.cancel()
        }

        /** 录音结束统一恢复按钮视觉 */
        private fun resetButtonVisual() {
            button.isPressed = false
            button.isActivated = false
            button.text = getString(R.string.voice_hold_to_speak)
            button.animate().cancel()
            button.scaleX = 1f
            button.scaleY = 1f
        }

        /** Activity 生命周期切走时强制结束录音，对应 onPause/onDestroy。 */
        fun forceCancel() {
            if (!isRecording) return
            isRecording = false
            resetButtonVisual()
            hideVoiceBubble()
            speechManager.cancel()
        }
    }

    /**
     * 发送消息
     * 清空输入并触发任务执行，消息显示统一由 observeExecutionState 处理
     */
    private fun sendMessage(text: String) {
        // 并发护栏：companion StateFlow 是进程级单例，主界面与悬浮窗共享。
        // 已有任务执行中再点发送会启动第二条 executeTaskLoop，两条循环并行写
        // _executionState、并行调 dispatchGesture，状态错乱。
        // 守门必须在 setText("") 之前，否则用户被拒绝时输入框已清空，得重打。
        if (ChatViewModel.executionState.value.isRunning) {
            Toast.makeText(this, R.string.task_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        etInput.setText("")
        clearInputFocus()
        executeTask(text)
    }

    /**
     * 执行任务
     */
    private fun executeTask(command: String) {
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "开始执行任务：$command")
                chatViewModel = ChatViewModel(application)
                val result = chatViewModel.executeTaskLoop(command)
                if (!result.success && result.message.contains("无障碍服务")) {
                    openAccessibilitySettings()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "执行任务失败", e)
                addSystemMessage("执行失败：${e.message}")

                if (e.message?.contains("无障碍服务") == true) {
                    openAccessibilitySettings()
                }
            }
        }
    }

    /**
     * 观察任务执行状态
     */
    private fun observeExecutionState() {
        lifecycleScope.launch {
            var lastTaskTitle = ""
            var lastCompletionKey = ""
            ChatViewModel.executionState.collect { state ->
                if (state.isRunning && state.taskTitle.isNotBlank() && state.taskTitle != lastTaskTitle) {
                    lastCompletionKey = ""
                    lastTaskTitle = state.taskTitle
                    addUserMessage(state.taskTitle)
                    addSystemMessage("正在执行：${state.taskTitle}")
                }
                if (state.isCompleted && state.resultMessage.isNotBlank()) {
                    val key = "${state.isSuccess}|${state.isCancelled}|${state.resultMessage}"
                    if (key != lastCompletionKey) {
                        lastCompletionKey = key
                        val prefix = when {
                            state.isCancelled -> "已取消"
                            state.isSuccess -> "执行完成"
                            else -> "执行失败"
                        }
                        addSystemMessage("$prefix：${state.resultMessage}")
                    }
                    lastTaskTitle = ""
                }
            }
        }
    }

    private fun addUserMessage(text: String) {
        chatMessages.add(ChatMessageAdapter.ChatMessageItem(content = text, isUser = true))
        chatAdapter.submitList(chatMessages.toList())
        rvChatMessages.scrollToPosition(chatMessages.size - 1)
        updateEmptyState()
    }

    private fun addSystemMessage(text: String) {
        chatMessages.add(ChatMessageAdapter.ChatMessageItem(content = text, isUser = false))
        chatAdapter.submitList(chatMessages.toList())
        rvChatMessages.scrollToPosition(chatMessages.size - 1)
        updateEmptyState()
    }

    /** 隐藏软键盘 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: etInput.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    /** 清除输入框焦点并收起键盘 */
    private fun clearInputFocus() {
        hideKeyboard()
        etInput.clearFocus()
    }

    private fun applyNoiseLevel(level: NoiseLevel) {
        if (!::noiseIndicator.isInitialized) return
        val colorRes = when (level) {
            NoiseLevel.LOW -> R.color.noise_low
            NoiseLevel.MEDIUM -> R.color.noise_medium
            NoiseLevel.HIGH -> R.color.noise_high
        }
        noiseIndicator.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val myPackageName = packageName
            return enabledServices.any { serviceInfo ->
                serviceInfo.resolveInfo?.serviceInfo?.packageName == myPackageName
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "检查无障碍服务失败", e)
            return false
        }
    }

    /**
     * 打开系统权限设置页面
     */
    private fun openAccessibilitySettings() {
        lifecycleScope.launch {
            val textInputMode = runCatching {
                SettingsPrefs.textInputMode(this@MainActivity).first()
            }.getOrDefault(TextInputMode.DIRECT)

            if (!isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            if (textInputMode == TextInputMode.IME_SIMULATION) {
                ImeActivationHelper.ensureImeReady(this@MainActivity)
            }
        }
    }

    /**
     * 设置窗口Insets
     */
    private fun setupWindowInsets() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val contentLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.contentLayout)

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentLayout) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.setPadding(
                systemBarsInsets.left,
                0,
                systemBarsInsets.right,
                if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        clearInputFocus()
    }

    override fun onPause() {
        super.onPause()
        // 录音中切走 Activity 时强制结束，避免悬挂的 mic + 残留气泡
        pressToTalkController?.forceCancel()
        clearInputFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkPermissions()
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && ChatViewModel.executionState.value.isRunning) {
            ChatViewModel.requestCancel()
        }
        pressToTalkController?.forceCancel()
        speechManager.destroy()
        noiseLevelJob?.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(KEY_CHAT_MESSAGES, ArrayList(chatMessages))
    }
}
