package com.example.speakassist

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 关于页面Activity
 *
 * 显示应用信息和版本号
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        setupBackToolbar(findViewById(R.id.toolbar), getString(R.string.nav_about))
    }
}
