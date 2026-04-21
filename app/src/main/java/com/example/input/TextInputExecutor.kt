package com.example.input

import android.content.Context
import com.example.data.SettingsPrefs
import com.example.service.MyAccessibilityService
import kotlinx.coroutines.flow.first

data class TextInputResult(
    val success: Boolean,
    val message: String
)

class TextInputExecutor(
    private val context: Context,
    private val service: MyAccessibilityService,
    private val accessibilityTextInput: AccessibilityTextInput = AccessibilityTextInput(service),
    private val imeTextInput: ImeTextInput = ImeTextInput(service)
) {

    suspend fun inputText(text: String): TextInputResult {
        return when (SettingsPrefs.textInputMode(context).first()) {
            TextInputMode.DIRECT -> accessibilityTextInput.inputText(text)
            TextInputMode.IME_SIMULATION -> imeTextInput.inputText(text)
        }
    }
}
