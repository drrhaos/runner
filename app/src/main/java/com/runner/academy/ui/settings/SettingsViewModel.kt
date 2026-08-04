package com.runner.academy.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.R
import com.runner.academy.util.ThemeUtils
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val userWeight: Float = 70f,
    val userHeight: Float = 175f,
    val userBirthDate: Long = 0L,
    val userGender: String = "male",
    val userAge: Int = 0,
    val unitSystem: String = "metric",
    val voiceFeedback: Boolean = false,
    val themeMode: String = ThemeUtils.THEME_SYSTEM,
    val appLanguage: String = "en",
    val isFirstLaunch: Boolean = true,
    val startCountdownSeconds: Int = 5
)

class SettingsViewModel(
    private val context: Context,
    private val userPreferences: UserPreferences
) : ViewModel() {
    
    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _settingsState.value = SettingsState(
                userWeight = userPreferences.userWeight,
                userHeight = userPreferences.userHeight,
                userBirthDate = userPreferences.userBirthDate,
                userGender = userPreferences.userGender,
                userAge = userPreferences.userAge,
                unitSystem = userPreferences.unitSystem,
                voiceFeedback = userPreferences.voiceFeedback,
                themeMode = userPreferences.themeMode,
                appLanguage = userPreferences.appLanguage,
                isFirstLaunch = userPreferences.isFirstLaunch,
                startCountdownSeconds = userPreferences.startCountdownSeconds
            )
            applyTheme(userPreferences.themeMode)
        }
    }

    fun updateUserWeight(weight: Float) {
        userPreferences.userWeight = weight
        _settingsState.value = _settingsState.value.copy(userWeight = weight)
    }
    
    fun updateUserHeight(height: Float) {
        userPreferences.userHeight = height
        _settingsState.value = _settingsState.value.copy(userHeight = height)
    }
    
    fun updateUserBirthDate(birthDate: Long) {
        userPreferences.userBirthDate = birthDate
        val age = userPreferences.userAge
        _settingsState.value = _settingsState.value.copy(
            userBirthDate = birthDate,
            userAge = age
        )
    }
    
    fun updateUserGender(gender: String) {
        userPreferences.userGender = gender
        _settingsState.value = _settingsState.value.copy(userGender = gender)
    }

    fun updateUnitSystem(unitSystem: String) {
        userPreferences.unitSystem = unitSystem
        _settingsState.value = _settingsState.value.copy(unitSystem = unitSystem)
    }

    fun updateVoiceFeedback(voiceFeedback: Boolean) {
        userPreferences.voiceFeedback = voiceFeedback
        _settingsState.value = _settingsState.value.copy(voiceFeedback = voiceFeedback)
    }

    fun updateThemeMode(themeMode: String) {
        val sanitized = ThemeUtils.ensureValidMode(themeMode)
        userPreferences.themeMode = sanitized
        _settingsState.value = _settingsState.value.copy(themeMode = sanitized)
        applyTheme(sanitized)
    }

    fun updateAppLanguage(language: String) {
        userPreferences.appLanguage = language
        _settingsState.value = _settingsState.value.copy(appLanguage = language)
        val localeList = LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun markFirstLaunchCompleted() {
        userPreferences.isFirstLaunch = false
        _settingsState.value = _settingsState.value.copy(isFirstLaunch = false)
    }

    fun updateStartCountdownSeconds(seconds: Int) {
        userPreferences.startCountdownSeconds = seconds
        _settingsState.value = _settingsState.value.copy(startCountdownSeconds = seconds)
    }

    fun getStartCountdownOptions(): List<Int> {
        return listOf(3, 5, 10, 15, 30)
    }

    fun resetToDefaults() {
        userPreferences.resetToDefaults()
        loadSettings()
    }

    fun getUnitSystemOptions(): List<String> {
        return listOf("metric", "imperial")
    }

    fun getThemeModeOptions(): List<String> {
        return listOf(ThemeUtils.THEME_SYSTEM, ThemeUtils.THEME_LIGHT, ThemeUtils.THEME_DARK)
    }

    fun getLanguageOptions(): List<String> {
        return listOf("en", "ru")
    }

    fun getUnitSystemDisplayName(unitSystem: String): String {
        return when (unitSystem) {
            "metric" -> context.getString(R.string.settings_units_metric)
            "imperial" -> context.getString(R.string.settings_units_imperial)
            else -> unitSystem
        }
    }

    fun getThemeModeDisplayName(themeMode: String): String {
        return when (ThemeUtils.ensureValidMode(themeMode)) {
            ThemeUtils.THEME_LIGHT -> context.getString(R.string.settings_theme_light)
            ThemeUtils.THEME_DARK -> context.getString(R.string.settings_theme_dark)
            else -> context.getString(R.string.settings_theme_system)
        }
    }

    fun getLanguageDisplayName(language: String): String {
        return when (language) {
            "en" -> context.getString(R.string.settings_language_en)
            "ru" -> context.getString(R.string.settings_language_ru)
            else -> language
        }
    }

    private fun applyTheme(themeMode: String) {
        ThemeUtils.applyTheme(themeMode)
    }
}

class SettingsViewModelFactory(
    private val context: Context,
    private val userPreferences: UserPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(context.applicationContext, userPreferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
