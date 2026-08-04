package com.runner.academy

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.runner.academy.util.OsmMapConfig
import com.runner.academy.util.ThemeUtils

class RunnerApplication : Application() {

    lateinit var container: AppContainer
        private set

    companion object {
        lateinit var instance: RunnerApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)

        OsmMapConfig.apply(this)

        val userPreferences = container.userPreferences
        val localeList = LocaleListCompat.forLanguageTags(userPreferences.appLanguage)
        AppCompatDelegate.setApplicationLocales(localeList)
        ThemeUtils.applyTheme(userPreferences.themeMode)
    }
}
