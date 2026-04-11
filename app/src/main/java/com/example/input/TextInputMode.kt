package com.example.input

enum class TextInputMode(val storageValue: String) {
    DIRECT("direct"),
    IME_SIMULATION("ime_simulation");

    companion object {
        fun fromStorageValue(value: String?): TextInputMode {
            return values().firstOrNull { it.storageValue == value } ?: DIRECT
        }
    }
}
