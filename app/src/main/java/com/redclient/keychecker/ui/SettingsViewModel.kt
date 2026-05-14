package com.redclient.keychecker.ui

import androidx.lifecycle.ViewModel
import com.redclient.keychecker.KeyCheckerApp
import com.redclient.keychecker.data.KeySlot
import com.redclient.keychecker.data.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val keys: Map<KeySlot, String> = KeySlot.values().associateWith { "" },
    val saved: Boolean = false,
)

class SettingsViewModel : ViewModel() {

    private val store: SecureStore = KeyCheckerApp.get().secureStore

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun loadState(): SettingsUiState {
        val map = KeySlot.values().associateWith { store.getKey(it) }
        return SettingsUiState(keys = map, saved = false)
    }

    fun update(slot: KeySlot, value: String) {
        _state.update { it.copy(keys = it.keys + (slot to value), saved = false) }
    }

    fun save(): Boolean {
        val s = _state.value
        // Validate prefixes (allow empty = unset).
        s.keys.forEach { (slot, value) ->
            val v = value.trim()
            if (v.isNotEmpty() && !v.startsWith(slot.expectedPrefix)) {
                return false
            }
        }
        s.keys.forEach { (slot, value) -> store.setKey(slot, value.trim()) }
        _state.update { it.copy(saved = true) }
        return true
    }

    fun clearAll() {
        store.clearAll()
        _state.value = loadState()
    }
}
