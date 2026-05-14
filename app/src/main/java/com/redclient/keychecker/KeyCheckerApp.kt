package com.redclient.keychecker

import android.app.Application
import com.redclient.keychecker.data.SecureStore

class KeyCheckerApp : Application() {

    val secureStore: SecureStore by lazy { SecureStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: KeyCheckerApp? = null

        fun get(): KeyCheckerApp = instance
            ?: error("KeyCheckerApp not initialized. Make sure it is set as android:name in the manifest.")
    }
}
