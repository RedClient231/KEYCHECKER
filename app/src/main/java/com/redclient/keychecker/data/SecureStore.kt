package com.redclient.keychecker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-256-GCM encrypted storage for Stripe API keys, backed by Android Keystore.
 *
 * Why this matters:
 *  - Keys are never written to disk in plaintext.
 *  - Keystore-bound master key cannot be exported off-device.
 *  - Backup is excluded via backup_rules.xml so keys never sync to Google Drive.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getKey(slot: KeySlot): String =
        prefs.getString(slot.prefKey, "").orEmpty().trim()

    fun setKey(slot: KeySlot, value: String) {
        prefs.edit().putString(slot.prefKey, value.trim()).apply()
    }

    fun getMode(): StripeMode {
        val name = prefs.getString(KEY_MODE, StripeMode.TEST.name)
        return runCatching { StripeMode.valueOf(name ?: StripeMode.TEST.name) }
            .getOrDefault(StripeMode.TEST)
    }

    fun setMode(mode: StripeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "keychecker_secure_prefs"
        private const val KEY_MODE = "stripe_mode"
    }
}
