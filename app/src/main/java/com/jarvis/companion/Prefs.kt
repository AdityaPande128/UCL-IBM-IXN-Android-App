package com.jarvis.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// The token never sits in plain storage and never rides a cloud backup:
// allowBackup is off and the prefs file is key-wrapped by the Keystore.
class Prefs(context: Context) {
    private val store: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "jarvis-prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var host: String
        get() = store.getString("host", "") ?: ""
        set(value) = store.edit().putString("host", value).apply()
    var port: Int
        get() = store.getInt("port", 8080)
        set(value) = store.edit().putInt("port", value).apply()
    var token: String
        get() = store.getString("token", "") ?: ""
        set(value) = store.edit().putString("token", value).apply()
    var secret: String
        get() = store.getString("secret", "") ?: ""
        set(value) = store.edit().putString("secret", value).apply()
    var turn: String
        get() = store.getString("turn", "") ?: ""
        set(value) = store.edit().putString("turn", value).apply()
    // The home router's mapped door, learned over sealed signaling and kept
    // for the next time the Mac is far away.
    var endpointHost: String
        get() = store.getString("endpoint-host", "") ?: ""
        set(value) = store.edit().putString("endpoint-host", value).apply()
    var endpointPort: Int
        get() = store.getInt("endpoint-port", 0)
        set(value) = store.edit().putInt("endpoint-port", value).apply()
    var theme: String
        get() = store.getString("theme", "system") ?: "system"
        set(value) = store.edit().putString("theme", value).apply()
    var lockEnabled: Boolean
        get() = store.getBoolean("lock", false)
        set(value) = store.edit().putBoolean("lock", value).apply()
    var speakReplies: Boolean
        get() = store.getBoolean("speak-replies", false)
        set(value) = store.edit().putBoolean("speak-replies", value).apply()
    var onboarded: Boolean
        get() = store.getBoolean("onboarded", false)
        set(value) = store.edit().putBoolean("onboarded", value).apply()

    val paired: Boolean get() = host.isNotBlank() && token.isNotBlank()

    fun forgetPairing() {
        store.edit().remove("host").remove("port").remove("token").remove("secret")
            .remove("turn").remove("endpoint-host").remove("endpoint-port")
            .putBoolean("onboarded", false).apply()
    }

    // Sign-out is total: pairing, settings, everything — the phone returns
    // to the state it was in before it ever met a Mac.
    fun wipe() {
        store.edit().clear().apply()
    }
}
