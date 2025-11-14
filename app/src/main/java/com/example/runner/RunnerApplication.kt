package com.example.runner

import android.app.Application
import com.example.runner.util.ThemeUtils
import com.example.runner.util.UserPreferences

class RunnerApplication : Application() {
    
    companion object {
        lateinit var instance: RunnerApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        val userPreferences = UserPreferences(this)
        ThemeUtils.applyTheme(userPreferences.themeMode)
    }
}
