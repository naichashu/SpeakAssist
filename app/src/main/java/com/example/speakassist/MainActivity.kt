package com.example.speakassist

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ui.viewmodel.ChatViewModel
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val etInput: TextInputEditText = findViewById(R.id.etInput)

        val button = findViewById<Button>(R.id.btnExecute)
        button.setOnClickListener {
            // 启动无障碍服务
            openAccessibilitySettings()
        }

        val button2 = findViewById<Button>(R.id.btnStart)
        button2.setOnClickListener {
            // 发起请求
            lifecycleScope.launch {
                try {
                    val inputText = etInput.text?.toString()?.trim()
                    Log.d("MainActivity", "开始执行任务：$inputText")
                    val chatViewModel = ChatViewModel(application)
                    delay(1000)
                    chatViewModel.executeTaskLoop("$inputText", "autoglm-phone")
                } catch (e: Exception) {
                    // 处理错误：提示用户开启无障碍服务
                    Log.e("MainActivity", "执行任务失败", e)
                    if (e.message?.contains("无障碍服务") == true) {
                        openAccessibilitySettings()
                    }
                }
            }
        }

    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}