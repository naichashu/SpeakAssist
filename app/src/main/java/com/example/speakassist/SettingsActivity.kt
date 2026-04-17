package com.example.speakassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.SettingsPrefs
import com.example.input.ImeActivationHelper
import com.example.input.ImeActivationStatus
import com.example.input.TextInputMode
import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页面Activity
 *
 * 显示应用设置项：
 * 1. 权限管理（无障碍服务、输入法、录音、悬浮窗）
 * 2. 悬浮窗开关
 * 3. 关于/反馈/清空历史
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvInputMethodStatus: TextView
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvTextInputModeValue: TextView
    private lateinit var switchFloatingWindow: SwitchMaterial
    private lateinit var switchVoiceWake: SwitchMaterial
    private var pendingImeModeActivation = false
    private var pendingImeModeDeactivation = false
    private var suppressSwitchCallbacks = false
    private var activeToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupBackToolbar(findViewById(R.id.toolbar), getString(R.string.nav_settings))
        initPermissionViews()
        setupClickListeners()
        setupTextInputModeRow()
        setupFloatingWindowSwitch()
        setupVoiceWakeSwitch()
    }

    private fun onImeStateChanged() {
        completePendingImeModeActivation()
        completePendingImeModeDeactivation()
        syncTextInputModeWithSystemState()
        lifecycleScope.launch {
            syncToggleStates()
        }
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        onImeStateChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            return
        }
        onImeStateChanged()
    }

    private fun initPermissionViews() {
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvInputMethodStatus = findViewById(R.id.tvInputMethodStatus)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvTextInputModeValue = findViewById(R.id.tvTextInputModeValue)
        switchFloatingWindow = findViewById(R.id.switchFloatingWindow)
        switchVoiceWake = findViewById(R.id.switchVoiceWake)
    }

    private fun showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, message, duration).also { it.show() }
    }

    private fun setupClickListeners() {
        findViewById<android.view.View>(R.id.itemAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        findViewById<android.view.View>(R.id.itemInputMethod).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        findViewById<android.view.View>(R.id.itemTextInputMode).setOnClickListener {
            showTextInputModeDialog()
        }

        findViewById<android.view.View>(R.id.itemAudioPermission).setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    1001
                )
            } else {
                showToast("录音权限已开启")
            }
        }

        findViewById<android.view.View>(R.id.itemOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    showToast("悬浮窗权限已开启")
                }
            }
        }

        findViewById<android.view.View>(R.id.itemClearHistory).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空历史记录")
                .setMessage("确定要清空所有历史记录吗？此操作不可撤销。")
                .setPositiveButton("确定") { _, _ ->
                    lifecycleScope.launch {
                        AppDatabase.getInstance(applicationContext).taskSessionDao().deleteAll()
                        showToast("历史记录已清空")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<android.view.View>(R.id.itemAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun setupTextInputModeRow() {
        lifecycleScope.launch {
            val mode = resolveEffectiveTextInputMode()
            tvTextInputModeValue.text = textInputModeLabel(mode)
        }
    }

    private fun showTextInputModeDialog() {
        lifecycleScope.launch {
            val currentMode = resolveEffectiveTextInputMode()
            val options = arrayOf(
                getString(R.string.text_input_mode_direct),
                getString(R.string.text_input_mode_ime)
            )
            val checkedItem = if (currentMode == TextInputMode.IME_SIMULATION) 1 else 0
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.text_input_mode_title)
                .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                    val selectedMode = if (which == 1) TextInputMode.IME_SIMULATION else TextInputMode.DIRECT
                    lifecycleScope.launch {
                        if (selectedMode == TextInputMode.IME_SIMULATION) {
                            when (ImeActivationHelper.ensureImeReady(this@SettingsActivity)) {
                                ImeActivationStatus.READY -> {
                                    pendingImeModeActivation = false
                                    applyTextInputMode(selectedMode)
                                    showToast("SpeakAssist 输入法已就绪")
                                }

                                ImeActivationStatus.NEED_ENABLE -> {
                                    pendingImeModeActivation = true
                                    showToast(getString(R.string.text_input_mode_ime_hint), Toast.LENGTH_LONG)
                                }

                                ImeActivationStatus.NEED_SWITCH -> {
                                    pendingImeModeActivation = true
                                    showToast("请选择 SpeakAssist 输入法以完成切换", Toast.LENGTH_LONG)
                                }
                            }
                        } else {
                            pendingImeModeActivation = false
                            val prompted = ImeActivationHelper.promptSwitchAwayFromIme(this@SettingsActivity)
                            pendingImeModeDeactivation = prompted
                            if (prompted) {
                                showToast("请切回你的常用输入法", Toast.LENGTH_LONG)
                            } else {
                                applyTextInputMode(TextInputMode.DIRECT)
                            }
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun textInputModeLabel(mode: TextInputMode): String {
        return if (mode == TextInputMode.IME_SIMULATION) {
            getString(R.string.text_input_mode_ime)
        } else {
            getString(R.string.text_input_mode_direct)
        }
    }

    private suspend fun resolveEffectiveTextInputMode(): TextInputMode {
        return if (MyInputMethodService.isCurrentInputMethod(this@SettingsActivity)) {
            TextInputMode.IME_SIMULATION
        } else {
            TextInputMode.DIRECT
        }
    }

    private fun completePendingImeModeActivation() {
        if (!pendingImeModeActivation) {
            return
        }
        lifecycleScope.launch {
            if (ImeActivationHelper.getStatus(this@SettingsActivity) == ImeActivationStatus.READY) {
                pendingImeModeActivation = false
                applyTextInputMode(TextInputMode.IME_SIMULATION)
                showToast("已切换到 SpeakAssist 输入法")
            }
        }
    }

    private fun completePendingImeModeDeactivation() {
        if (!pendingImeModeDeactivation) {
            return
        }
        lifecycleScope.launch {
            if (!MyInputMethodService.isCurrentInputMethod(this@SettingsActivity)) {
                pendingImeModeDeactivation = false
                applyTextInputMode(TextInputMode.DIRECT)
                showToast("已切回默认输入法模式")
            }
        }
    }

    private fun syncTextInputModeWithSystemState() {
        lifecycleScope.launch {
            pendingImeModeActivation = false
            pendingImeModeDeactivation = false
            val effectiveMode = resolveEffectiveTextInputMode()
            applyTextInputMode(effectiveMode)
        }
    }

    private suspend fun applyTextInputMode(mode: TextInputMode) {
        SettingsPrefs.setTextInputMode(this@SettingsActivity, mode)
        tvTextInputModeValue.text = textInputModeLabel(mode)
        refreshPermissionStatus()
    }

    private suspend fun syncToggleStates() {
        val floatingEnabled = SettingsPrefs.floatingWindowEnabled(this@SettingsActivity).first()
        val voiceWakeEnabled = SettingsPrefs.voiceWakeEnabled(this@SettingsActivity).first()
        if (!floatingEnabled && voiceWakeEnabled) {
            SettingsPrefs.setVoiceWakeEnabled(this@SettingsActivity, false)
        }
        updateSwitchStates(floatingEnabled, floatingEnabled && voiceWakeEnabled)
    }

    private fun updateSwitchStates(floatingEnabled: Boolean, voiceWakeEnabled: Boolean) {
        suppressSwitchCallbacks = true
        switchFloatingWindow.isChecked = floatingEnabled
        switchVoiceWake.isChecked = voiceWakeEnabled
        suppressSwitchCallbacks = false
    }

    private fun setupFloatingWindowSwitch() {
        lifecycleScope.launch {
            syncToggleStates()
        }

        switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !MyAccessibilityService.isServiceEnabled()) {
                updateSwitchStates(false, false)
                showToast("请先开启无障碍服务，悬浮窗才能正常显示", Toast.LENGTH_LONG)
                lifecycleScope.launch {
                    SettingsPrefs.setFloatingWindowEnabled(this@SettingsActivity, false)
                    SettingsPrefs.setVoiceWakeEnabled(this@SettingsActivity, false)
                }
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                SettingsPrefs.setFloatingWindowEnabled(this@SettingsActivity, isChecked)
                if (isChecked) {
                    MyAccessibilityService.getInstance()?.floatingWindowManager?.showCircle()
                } else {
                    SettingsPrefs.setVoiceWakeEnabled(this@SettingsActivity, false)
                    updateSwitchStates(false, false)
                    MyAccessibilityService.getInstance()?.floatingWindowManager?.hideCircle()
                }
            }
        }
    }

    private fun setupVoiceWakeSwitch() {
        lifecycleScope.launch {
            syncToggleStates()
        }

        switchVoiceWake.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !switchFloatingWindow.isChecked) {
                updateSwitchStates(false, false)
                showToast("请先开启悬浮窗，再启用语音唤醒", Toast.LENGTH_LONG)
                lifecycleScope.launch {
                    SettingsPrefs.setVoiceWakeEnabled(this@SettingsActivity, false)
                }
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                SettingsPrefs.setVoiceWakeEnabled(this@SettingsActivity, isChecked)
            }
        }
    }

    private fun refreshPermissionStatus() {
        lifecycleScope.launch {
            val accessibilityEnabled = MyAccessibilityService.isServiceEnabled()
            tvAccessibilityStatus.text = if (accessibilityEnabled) "已开启" else "未开启"
            tvAccessibilityStatus.setTextColor(
                if (accessibilityEnabled) getColor(R.color.success) else getColor(R.color.error)
            )

            val inputMethodEnabled = MyInputMethodService.isEnabled(this@SettingsActivity)
            tvInputMethodStatus.text = if (inputMethodEnabled) "已开启" else "未开启"
            tvInputMethodStatus.setTextColor(
                if (inputMethodEnabled) getColor(R.color.success) else getColor(R.color.error)
            )

            val textInputMode = resolveEffectiveTextInputMode()
            tvTextInputModeValue.text = textInputModeLabel(textInputMode)

            val audioPermission = ActivityCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            tvAudioStatus.text = if (audioPermission) "已授权" else "未授权"
            tvAudioStatus.setTextColor(
                if (audioPermission) getColor(R.color.success) else getColor(R.color.error)
            )

            val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this@SettingsActivity)
            } else {
                true
            }
            tvOverlayStatus.text = if (overlayPermission) "已授权" else "未授权"
            tvOverlayStatus.setTextColor(
                if (overlayPermission) getColor(R.color.success) else getColor(R.color.error)
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            refreshPermissionStatus()
        }
    }
}
