package com.example.speakassist

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val chatViewModel: ChatViewModel = ChatViewModel(application)

        // 发起请求
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "开始执行任务")
                delay(1000)
                chatViewModel.executeTaskLoop("打开微信", "autoglm-phone")
            } catch (e: Exception) {
                // 处理错误
            }
        }

    }
}