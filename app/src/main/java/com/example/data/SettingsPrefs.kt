package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.input.TextInputMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsPrefs {

    private val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
    private val TEXT_INPUT_MODE = stringPreferencesKey("text_input_mode")

    fun floatingWindowEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] ?: false
        }
    }

    fun textInputMode(context: Context): Flow<TextInputMode> {
        return context.dataStore.data.map { prefs ->
            TextInputMode.fromStorageValue(prefs[TEXT_INPUT_MODE])
        }
    }

    suspend fun setFloatingWindowEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] = enabled
        }
    }

    suspend fun setTextInputMode(context: Context, mode: TextInputMode) {
        context.dataStore.edit { prefs ->
            prefs[TEXT_INPUT_MODE] = mode.storageValue
        }
    }
}
