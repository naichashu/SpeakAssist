package com.example.speakassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.service.MyAccessibilityService
import com.example.service.MyInputMethodService
import com.example.speech.BaiduSpeechManager
import com.example.ui.viewmodel.ChatViewModel
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etInput: TextInputEditText
    private lateinit var btnVoice: ImageButton
    private lateinit var speechManager: BaiduSpeechManager

    // 百度语音 API 凭证
    private val BAIDU_API_KEY = "Xkmx5j1pbR3NvquUMOFnXo5u"
    private val BAIDU_SECRET_KEY = "I56pmB7DrQ1JNwoBMyjVBdJ6CUyIW49x"

    // 录音权限请求
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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        etInput = findViewById(R.id.etInput)
        btnVoice = findViewById(R.id.btnVoice)

        setupSpeechManager()

        val button = findViewById<Button>(R.id.btnExecute)
        button.setOnClickListener {
            openAccessibilitySettings()
        }

        val button2 = findViewById<Button>(R.id.btnStart)
        button2.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val inputText = etInput.text?.toString()?.trim()
                    Log.d("MainActivity", "开始执行任务：$inputText")
                    val chatViewModel = ChatViewModel(application)
                    delay(1000)
                    chatViewModel.executeTaskLoop("$inputText", "autoglm-phone")
                } catch (e: Exception) {
                    Log.e("MainActivity", "执行任务失败", e)
                    if (e.message?.contains("无障碍服务") == true) {
                        openAccessibilitySettings()
                    }
                }
            }
        }
    }

    private fun setupSpeechManager() {
        speechManager = BaiduSpeechManager(this)
        speechManager.setCredentials(BAIDU_API_KEY, BAIDU_SECRET_KEY)

        speechManager.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                updateVoiceButtonState(true)
                Toast.makeText(this@MainActivity, R.string.voice_listening, Toast.LENGTH_SHORT).show()
            }

            override fun onResult(text: String) {
                updateVoiceButtonState(false)
                etInput.setText(text)
                etInput.setSelection(text.length)
            }

            override fun onError(message: String) {
                updateVoiceButtonState(false)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }

            override fun onEnd() {
                updateVoiceButtonState(false)
            }

            override fun onVolumeChanged(volume: Int) {
                // 可以用来显示音量动画
            }
        })

        btnVoice.setOnClickListener {
            if (speechManager.isListening()) {
                speechManager.stop()
                updateVoiceButtonState(false)
            } else {
                checkPermissionAndStartSpeech()
            }
        }
    }

    private fun checkPermissionAndStartSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startSpeechRecognition()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechRecognition() {
        speechManager.start()
    }

    private fun updateVoiceButtonState(isListening: Boolean) {
        btnVoice.isActivated = isListening
        btnVoice.alpha = if (isListening) 0.5f else 1.0f
    }

    private fun openAccessibilitySettings() {
        if (!MyAccessibilityService.isServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        if (!MyInputMethodService.isEnabled(this)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}
