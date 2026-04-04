package com.example.speakassist

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

fun AppCompatActivity.setupBackToolbar(toolbar: Toolbar, title: String) {
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = title
    toolbar.setNavigationOnClickListener { finish() }
}
