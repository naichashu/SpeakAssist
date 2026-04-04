package com.example.ui

import android.widget.TextView
import androidx.annotation.DrawableRes
import com.example.speakassist.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionStatusUi(
    val text: String,
    @DrawableRes val backgroundRes: Int
)

fun getSessionStatusUi(status: String): SessionStatusUi {
    return when (status) {
        "success" -> SessionStatusUi("成功", R.drawable.bg_status_success)
        "fail" -> SessionStatusUi("失败", R.drawable.bg_status_fail)
        "cancelled" -> SessionStatusUi("已取消", R.drawable.bg_status_running)
        else -> SessionStatusUi("进行中", R.drawable.bg_status_running)
    }
}

fun TextView.bindSessionStatus(status: String) {
    val ui = getSessionStatusUi(status)
    text = ui.text
    setBackgroundResource(ui.backgroundRes)
}

fun formatSessionTime(timestamp: Long, pattern: String): String {
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
