package com.homerelay.app.data

import android.content.Context
import android.content.SharedPreferences

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Persisted user settings. All fields have safe, explicit defaults. */
data class Settings(
    val proxyPort: Int = 8080,
    val pacEnabled: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val keepScreenOn: Boolean = false,
    val showPassphrase: Boolean = true,
    val firstRunDone: Boolean = false,
)

/** Tiny SharedPreferences-backed store. No secrets are stored here. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("homerelay_settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        proxyPort = prefs.getInt(KEY_PORT, 8080).let { if (Validation.isValidProxyPort(it)) it else 8080 },
        pacEnabled = prefs.getBoolean(KEY_PAC, true),
        theme = runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeChoice.SYSTEM),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        showPassphrase = prefs.getBoolean(KEY_SHOW_PASSPHRASE, true),
        firstRunDone = prefs.getBoolean(KEY_FIRST_RUN, false),
    )

    fun setProxyPort(port: Int) { prefs.edit().putInt(KEY_PORT, port).apply() }
    fun setPacEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_PAC, enabled).apply() }
    fun setTheme(theme: ThemeChoice) { prefs.edit().putString(KEY_THEME, theme.name).apply() }
    fun setKeepScreenOn(enabled: Boolean) { prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply() }
    fun setShowPassphrase(enabled: Boolean) { prefs.edit().putBoolean(KEY_SHOW_PASSPHRASE, enabled).apply() }
    fun setFirstRunDone() { prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply() }
    fun clearAll() { prefs.edit().clear().apply() }

    private companion object {
        const val KEY_PORT = "proxy_port"
        const val KEY_PAC = "pac_enabled"
        const val KEY_THEME = "theme"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_PASSPHRASE = "show_passphrase"
        const val KEY_FIRST_RUN = "first_run_done"
    }
}
