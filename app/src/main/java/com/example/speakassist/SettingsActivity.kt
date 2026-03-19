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
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService

/**
 * 设置页面Activity
 *
 * 显示应用设置项：
 * 1. 权限管理（无障碍服务、输入法、录音、悬浮窗）
 * 2. 悬浮窗开关
 * 3. 关于/反馈/清空历史
 */
class SettingsActivity : AppCompatActivity() {

    // 权限状态文本视图
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvInputMethodStatus: TextView
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvOverlayStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 设置Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.nav_settings)

        // 返回按钮点击事件
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // 初始化权限状态视图
        initPermissionViews()

        // 设置点击事件
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // 刷新权限状态
        refreshPermissionStatus()
    }

    /**
     * 初始化权限状态视图
     */
    private fun initPermissionViews() {
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvInputMethodStatus = findViewById(R.id.tvInputMethodStatus)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
    }

    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 无障碍服务
        findViewById<android.view.View>(R.id.itemAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // 输入法
        findViewById<android.view.View>(R.id.itemInputMethod).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // 录音权限
        findViewById<android.view.View>(R.id.itemAudioPermission).setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    1001
                )
            } else {
                Toast.makeText(this, "录音权限已开启", Toast.LENGTH_SHORT).show()
            }
        }

        // 悬浮窗权限
        findViewById<android.view.View>(R.id.itemOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 清空历史
        findViewById<android.view.View>(R.id.itemClearHistory).setOnClickListener {
            Toast.makeText(this, "功能开发中", Toast.LENGTH_SHORT).show()
        }

        // 关于
        findViewById<android.view.View>(R.id.itemAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /**
     * 刷新权限状态显示
     */
    private fun refreshPermissionStatus() {
        // 检查无障碍服务
        val accessibilityEnabled = MyAccessibilityService.isServiceEnabled()
        tvAccessibilityStatus.text = if (accessibilityEnabled) "已开启" else "未开启"
        tvAccessibilityStatus.setTextColor(
            if (accessibilityEnabled) getColor(R.color.success) else getColor(R.color.error)
        )

        // 检查输入法
        val inputMethodEnabled = MyInputMethodService.isEnabled(this)
        tvInputMethodStatus.text = if (inputMethodEnabled) "已开启" else "未开启"
        tvInputMethodStatus.setTextColor(
            if (inputMethodEnabled) getColor(R.color.success) else getColor(R.color.error)
        )

        // 检查录音权限
        val audioPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        tvAudioStatus.text = if (audioPermission) "已授权" else "未授权"
        tvAudioStatus.setTextColor(
            if (audioPermission) getColor(R.color.success) else getColor(R.color.error)
        )

        // 检查悬浮窗权限
        val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        tvOverlayStatus.text = if (overlayPermission) "已授权" else "未授权"
        tvOverlayStatus.setTextColor(
            if (overlayPermission) getColor(R.color.success) else getColor(R.color.error)
        )
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
