package com.drrhaos.runner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.drrhaos.runner.util.ThemeUtils
import com.drrhaos.runner.util.UserPreferences

class RunnerApplication : Application() {

    companion object {
        lateinit var instance: RunnerApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val userPreferences = UserPreferences(this)
        val localeList = LocaleListCompat.forLanguageTags(userPreferences.appLanguage)
        AppCompatDelegate.setApplicationLocales(localeList)
        ThemeUtils.applyTheme(userPreferences.themeMode)
    }
}
