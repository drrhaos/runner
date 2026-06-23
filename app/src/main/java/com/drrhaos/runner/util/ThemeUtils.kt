package com.drrhaos.runner.util

import androidx.appcompat.app.AppCompatDelegate

object ThemeUtils {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun applyTheme(themeMode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (ensureValidMode(themeMode)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun ensureValidMode(themeMode: String): String {
        return when (themeMode) {
            THEME_LIGHT, THEME_DARK, THEME_SYSTEM -> themeMode
            else -> THEME_SYSTEM
        }
    }
}

