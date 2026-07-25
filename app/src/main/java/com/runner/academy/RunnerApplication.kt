package com.runner.academy

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.runner.academy.util.OsmMapConfig
import com.runner.academy.util.ThemeUtils
import com.runner.academy.util.UserPreferences

class RunnerApplication : Application() {

    companion object {
        lateinit var instance: RunnerApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        OsmMapConfig.apply(this)

        val userPreferences = UserPreferences(this)
        val localeList = LocaleListCompat.forLanguageTags(userPreferences.appLanguage)
        AppCompatDelegate.setApplicationLocales(localeList)
        ThemeUtils.applyTheme(userPreferences.themeMode)
    }
}
