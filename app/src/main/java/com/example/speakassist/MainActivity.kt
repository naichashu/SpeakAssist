package com.example.speakassist

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
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
import com.example.speech.BaiduSpeechManager
import com.example.ui.adapter.ChatMessageAdapter
import com.example.ui.viewmodel.ChatViewModel
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * MainActivity - 应用主界面
 *
 * 负责：
 * 1. 显示顶部导航栏（Toolbar + 侧栏菜单）
 * 2. 检查并提示权限状态
 * 3. 显示聊天消息列表
 * 4. 处理用户输入（文本/语音）
 * 5. 发起任务执行
 */
class MainActivity : AppCompatActivity() {

    // ==================== 视图组件 ====================
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout  // 侧栏布局
    private lateinit var toolbar: Toolbar                                         // 顶部导航栏
    private lateinit var permissionBanner: View                                  // 权限提示横幅
    private lateinit var tvPermissionHint: android.widget.TextView              // 权限提示文字
    private lateinit var btnPermissionSettings: Button                          // 权限设置按钮
    private lateinit var rvChatMessages: androidx.recyclerview.widget.RecyclerView  // 消息列表
    private lateinit var emptyState: View                                       // 空状态视图
    private lateinit var etInput: TextInputEditText                             // 文本输入框
    private lateinit var btnVoice: ImageButton                                  // 语音按钮
    private lateinit var btnSend: ImageButton                                    // 发送按钮

    // ==================== 数据和适配器 ====================
    private lateinit var chatAdapter: ChatMessageAdapter  // 聊天消息适配器
    private val chatMessages = mutableListOf<ChatMessageAdapter.ChatMessageItem>() // 消息列表（单一数据源）

    // ==================== 业务逻辑 ====================
    private lateinit var speechManager: BaiduSpeechManager  // 百度语音管理器
    private lateinit var chatViewModel: ChatViewModel       // 聊天业务逻辑

    // ==================== 权限请求 ====================
    // 录音权限请求回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startSpeechRecognition()
        } else {
            Toast.makeText(this, R.string.voice_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyAccessibilityService.resumeFloatingOverlays()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 初始化视图组件
        initViews()

        // 设置Toolbar
        setupToolbar()

        // 设置侧栏
        setupDrawer()

        // 设置权限检查
        checkPermissions()

        // 设置聊天列表
        setupChatList()

        // 设置语音识别
        setupSpeechManager()

        // 设置输入框和发送按钮
        setupInputArea()

        // 处理窗口Insets
        setupWindowInsets()

        setupBackPressedHandler()

        // 观察悬浮窗发起的任务执行状态
        observeExecutionState()
    }

    /**
     * 初始化视图组件
     * 从布局文件中获取所有必要的UI组件引用
     */
    private fun initViews() {
        drawerLayout = findViewById(R.id.main)
        toolbar = findViewById(R.id.toolbar)
        permissionBanner = findViewById(R.id.permissionBanner)
        tvPermissionHint = findViewById(R.id.tvPermissionHint)
        btnPermissionSettings = findViewById(R.id.btnPermissionSettings)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        emptyState = findViewById(R.id.emptyState)
        etInput = findViewById(R.id.etInput)
        btnVoice = findViewById(R.id.btnVoice)
        btnSend = findViewById(R.id.btnSend)
    }

    /**
     * 设置Toolbar
     * 配置顶部导航栏，包括标题和侧栏菜单开关
     */
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // 隐藏默认标题，使用自定义

        // 设置汉堡菜单按钮点击事件
        val ivMenu = findViewById<android.widget.ImageView>(R.id.ivMenu)
        ivMenu?.setOnClickListener {
            clearInputFocus()
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * 设置侧栏菜单
     * 配置NavigationView的菜单项点击事件
     */
    private fun setupDrawer() {
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { menuItem ->
            clearInputFocus()
            when (menuItem.itemId) {
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                }
                R.id.nav_settings -> {
                    // 跳转到设置页面
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.nav_about -> {
                    // 跳转到关于页面
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            }
            // 关闭侧栏
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    /**
     * 检查权限状态
     * 检查应用所需的各种权限，并在权限缺失时显示提示横幅
     */
    private fun checkPermissions() {
        lifecycleScope.launch {
            // 检查无障碍服务是否启用
            val accessibilityEnabled = isAccessibilityServiceEnabled()

            // 检查输入法是否启用
            val inputMethodEnabled = MyInputMethodService.isEnabled(this@MainActivity)
            val textInputMode = SettingsPrefs.textInputMode(this@MainActivity).first()

            // 检查录音权限
            val audioPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

            // 检查悬浮窗权限
            val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this@MainActivity)
            } else {
                true
            }

            // 收集未开启的权限
            val missingPermissions = mutableListOf<String>()
            if (!accessibilityEnabled) missingPermissions.add(getString(R.string.permission_accessibility))
            if (textInputMode == TextInputMode.IME_SIMULATION && !inputMethodEnabled) {
                missingPermissions.add(getString(R.string.permission_input_method))
            }
            if (!audioPermission) missingPermissions.add(getString(R.string.permission_audio))
            if (!overlayPermission) missingPermissions.add(getString(R.string.permission_overlay))

            // 根据权限状态显示/隐藏横幅
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
     * 配置RecyclerView和适配器
     */
    private fun setupChatList() {
        chatAdapter = ChatMessageAdapter()

        rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }

        updateEmptyState()
    }

    /**
     * 更新空状态显示
     * 根据消息列表是否为空来显示/隐藏空状态提示
     */
    private fun updateEmptyState() {
        emptyState.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
        rvChatMessages.visibility = if (chatAdapter.itemCount == 0) View.GONE else View.VISIBLE
    }

    /**
     * 设置语音识别
     * 初始化百度语音管理器并设置回调
     */
    private fun setupSpeechManager() {
        speechManager = BaiduSpeechManager(this)
        val credentials = BaiduSpeechConfig.credentials()
        speechManager.setCredentials(credentials.apiKey, credentials.secretKey)

        speechManager.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                runOnUiThread {
                    updateVoiceButtonState(true)
                    Toast.makeText(this@MainActivity, R.string.voice_listening, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResult(text: String) {
                runOnUiThread {
                    updateVoiceButtonState(false)
                    etInput.setText(text)
                    etInput.setSelection(text.length)
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    updateVoiceButtonState(false)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onEnd() {
                runOnUiThread {
                    updateVoiceButtonState(false)
                }
            }

            override fun onVolumeChanged(volume: Int) {
                // 可以用来显示音量动画
            }
        })

        // 语音按钮点击事件
        btnVoice.setOnClickListener {
            clearInputFocus()
            if (speechManager.isListening()) {
                speechManager.stop()
                updateVoiceButtonState(false)
            } else {
                checkPermissionAndStartSpeech()
            }
        }
    }

    /**
     * 设置输入区域
     * 配置文本输入框和发送按钮的行为
     */
    private fun setupInputArea() {
        // 文本输入框监听
        etInput.addTextChangedListener {
            // 可以在这里启用/禁用发送按钮
            btnSend.isEnabled = !it.isNullOrBlank()
        }

        // 发送按钮点击事件
        btnSend.setOnClickListener {
            val inputText = etInput.text?.toString()?.trim()
            if (!inputText.isNullOrBlank()) {
                sendMessage(inputText)
            }
        }

        // 输入框回车键发送（可选）
        etInput.setOnEditorActionListener { _, _, _ ->
            btnSend.performClick()
            true
        }
    }

    /**
     * 发送消息
     * 清空输入并触发任务执行，消息显示统一由 observeExecutionState 处理
     */
    private fun sendMessage(text: String) {
        etInput.setText("")
        clearInputFocus()
        executeTask(text)
    }

    /**
     * 执行任务
     * 调用ChatViewModel执行AI任务
     *
     * @param command 用户指令
     */
    private fun executeTask(command: String) {
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "开始执行任务：$command")
                chatViewModel = ChatViewModel(application)
                val result = chatViewModel.executeTaskLoop(command, "autoglm-phone")
                if (!result.success && result.message.contains("无障碍服务")) {
                    openAccessibilitySettings()
                }
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
     * 显示每次任务的用户指令、执行过程和结果（统一入口，避免与本地发送重复）
     */
    private fun observeExecutionState() {
        lifecycleScope.launch {
            var lastTaskTitle = ""
            var lastCompletionKey = ""
            ChatViewModel.executionState.collect { state ->
                // 任务开始时，显示用户指令（作为用户消息）
                if (state.isRunning && state.taskTitle.isNotBlank() && state.taskTitle != lastTaskTitle) {
                    lastTaskTitle = state.taskTitle
                    addUserMessage(state.taskTitle)
                    addSystemMessage("正在执行：${state.taskTitle}")
                }
                // 任务完成时，显示结果（同一结果只显示一次，避免 Activity 重建后重放）
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

    /**
     * 添加用户消息到聊天列表
     */
    private fun addUserMessage(text: String) {
        chatMessages.add(ChatMessageAdapter.ChatMessageItem(content = text, isUser = true))
        chatAdapter.submitList(chatMessages.toList())
        rvChatMessages.scrollToPosition(chatMessages.size - 1)
        updateEmptyState()
    }

    /**
     * 添加系统消息到聊天列表
     */
    private fun addSystemMessage(text: String) {
        val isComplete = text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
                text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
        val displayText = if (isComplete) text else "$text..."
        chatMessages.add(ChatMessageAdapter.ChatMessageItem(content = displayText, isUser = false))
        chatAdapter.submitList(chatMessages.toList())
        rvChatMessages.scrollToPosition(chatMessages.size - 1)
        updateEmptyState()
    }

    /**
     * 检查权限并启动语音识别
     * 如果有录音权限则直接启动，否则请求权限
     */
    private fun checkPermissionAndStartSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startSpeechRecognition()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 启动语音识别
     */
    private fun startSpeechRecognition() {
        speechManager.start()
    }

    /**
     * 更新语音按钮状态
     *
     * @param isListening 是否正在聆听
     */
    /**
     * 隐藏软键盘
     */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: etInput.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    /**
     * 清除输入框焦点并收起键盘
     * 点击其它按钮、返回页面、切 Activity 时统一调用，避免输入框残留 focus
     */
    private fun clearInputFocus() {
        // 先收键盘（依赖 focus 取 windowToken），再清 focus
        hideKeyboard()
        etInput.clearFocus()
    }

    private fun updateVoiceButtonState(isListening: Boolean) {
        btnVoice.isActivated = isListening
        btnVoice.alpha = if (isListening) 0.5f else 1.0f
    }

    /**
     * 检查无障碍服务是否已启用
     * 使用AccessibilityManager检查系统中是否启用了当前应用的无障碍服务
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
     * 跳转至无障碍服务和输入法设置页面
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
     * 处理系统状态栏和导航栏的适配，以及键盘弹出时的布局调整
     */
    private fun setupWindowInsets() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val contentLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.contentLayout)

        // 为Toolbar设置顶部padding，避免被状态栏覆盖
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // 处理键盘弹出时为底部输入栏留出空间
        ViewCompat.setOnApplyWindowInsetsListener(contentLayout) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 设置底部padding以避开键盘
            v.setPadding(
                systemBarsInsets.left,
                0,
                systemBarsInsets.right,
                if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            )
            insets
        }
    }

    /**
     * 生命周期 - onResume
     * 返回界面时由 onWindowFocusChanged 统一刷新权限状态
     */
    override fun onResume() {
        super.onResume()
        // 返回页面时，避免系统把上次的焦点还原到输入框
        clearInputFocus()
    }

    /**
     * 生命周期 - onPause
     * 离开页面前先卸掉输入焦点，防止系统在 onResume 时自动恢复
     */
    override fun onPause() {
        super.onPause()
        clearInputFocus()
    }

    /**
     * 窗口焦点变化时刷新权限状态
     * 当用户从设置页面返回时，及时更新权限横幅显示
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkPermissions()
        }
    }

    /**
     * 处理返回手势/返回键：优先关闭侧栏
     */
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

    /**
     * 生命周期 - onDestroy
     * 释放语音管理器资源
     */
    override fun onDestroy() {
        MyAccessibilityService.suspendFloatingOverlays()
        super.onDestroy()
        speechManager.destroy()
    }
}
