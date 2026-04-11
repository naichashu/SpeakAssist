package com.example.input

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.example.service.MyInputMethodService

enum class ImeActivationStatus {
    READY,
    NEED_ENABLE,
    NEED_SWITCH
}

object ImeActivationHelper {

    fun getStatus(context: Context): ImeActivationStatus {
        if (!MyInputMethodService.isEnabled(context)) {
            return ImeActivationStatus.NEED_ENABLE
        }
        if (!MyInputMethodService.isCurrentInputMethod(context)) {
            return ImeActivationStatus.NEED_SWITCH
        }
        return ImeActivationStatus.READY
    }

    fun ensureImeReady(context: Context): ImeActivationStatus {
        return when (val status = getStatus(context)) {
            ImeActivationStatus.NEED_ENABLE -> {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                status
            }

            ImeActivationStatus.NEED_SWITCH -> {
                showInputMethodPicker(context)
                status
            }

            ImeActivationStatus.READY -> status
        }
    }

    fun promptSwitchAwayFromIme(context: Context): Boolean {
        if (!MyInputMethodService.isCurrentInputMethod(context)) {
            return false
        }
        showInputMethodPicker(context)
        return true
    }

    private fun showInputMethodPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}
