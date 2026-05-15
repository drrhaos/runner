package com.example.runner.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runner.R
import com.example.runner.util.ThemeUtils
import com.example.runner.util.UserPreferences
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
    val autoPause: Boolean = true,
    val voiceFeedback: Boolean = false,
    val gpsAccuracy: String = "high",
    val themeMode: String = ThemeUtils.THEME_SYSTEM,
    val appLanguage: String = "en",
    val isFirstLaunch: Boolean = true
)

class SettingsViewModel(private val context: Context) : ViewModel() {

    private val userPreferences = UserPreferences(context)
    
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
                autoPause = userPreferences.autoPause,
                voiceFeedback = userPreferences.voiceFeedback,
                gpsAccuracy = userPreferences.gpsAccuracy,
                themeMode = userPreferences.themeMode,
                appLanguage = userPreferences.appLanguage,
                isFirstLaunch = userPreferences.isFirstLaunch
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

    fun updateAutoPause(autoPause: Boolean) {
        userPreferences.autoPause = autoPause
        _settingsState.value = _settingsState.value.copy(autoPause = autoPause)
    }

    fun updateVoiceFeedback(voiceFeedback: Boolean) {
        userPreferences.voiceFeedback = voiceFeedback
        _settingsState.value = _settingsState.value.copy(voiceFeedback = voiceFeedback)
    }

    fun updateGpsAccuracy(gpsAccuracy: String) {
        userPreferences.gpsAccuracy = gpsAccuracy
        _settingsState.value = _settingsState.value.copy(gpsAccuracy = gpsAccuracy)
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

    fun resetToDefaults() {
        userPreferences.resetToDefaults()
        loadSettings()
    }

    fun getUnitSystemOptions(): List<String> {
        return listOf("metric", "imperial")
    }

    fun getGpsAccuracyOptions(): List<String> {
        return listOf("high", "medium", "low")
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

    fun getGpsAccuracyDisplayName(gpsAccuracy: String): String {
        return when (gpsAccuracy) {
            "high" -> context.getString(R.string.settings_gps_accuracy_high)
            "medium" -> context.getString(R.string.settings_gps_accuracy_medium)
            "low" -> context.getString(R.string.settings_gps_accuracy_low)
            else -> gpsAccuracy
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

class SettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
