package com.drrhaos.runner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.drrhaos.runner.util.ThemeUtils
import com.drrhaos.runner.util.UserPreferences
import org.osmdroid.config.Configuration

class RunnerApplication : Application() {

    companion object {
        lateinit var instance: RunnerApplication
            private set

        private const val OSM_USER_AGENT = "Runner/1.0 (com.drrhaos.runner; open-source fitness tracker)"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Configuration.getInstance().apply {
            load(this@RunnerApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = OSM_USER_AGENT
        }

        val userPreferences = UserPreferences(this)
        val localeList = LocaleListCompat.forLanguageTags(userPreferences.appLanguage)
        AppCompatDelegate.setApplicationLocales(localeList)
        ThemeUtils.applyTheme(userPreferences.themeMode)
    }
}
