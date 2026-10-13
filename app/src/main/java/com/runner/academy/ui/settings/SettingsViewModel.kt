package com.runner.academy.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.R
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorStatus
import com.runner.academy.sensor.SensorType
import com.runner.academy.ui.devices.DevicesSummary
import com.runner.academy.util.HrMax
import com.runner.academy.util.StepTrackingAccess
import com.runner.academy.util.ThemeUtils
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class SettingsState(
    val userWeight: Float = 70f,
    val userHeight: Float = 175f,
    val userBirthDate: Long = 0L,
    val userGender: String = "male",
    val userAge: Int = 0,
    val unitSystem: String = "metric",
    val voiceFeedback: Boolean = false,
    val gpsDiagnostics: Boolean = false,
    /** Steps really in use: enabled and permission granted (or not needed). */
    val stepsForDistance: Boolean = false,
    val hasStepSensor: Boolean = true,
    val autoPause: Boolean = false,
    val themeMode: String = ThemeUtils.THEME_SYSTEM,
    val appLanguage: String = "en",
    val isFirstLaunch: Boolean = true,
    val startCountdownSeconds: Int = 5,
    /** What the zones are built on; null: they are off. */
    val hrMax: HrMax? = null,
    /** 220 − age whatever is set by hand; null without a birth date. */
    val hrMaxByAge: HrMax? = null
)

class SettingsViewModel(
    private val context: Context,
    private val userPreferences: UserPreferences,
    pairedSensors: StateFlow<Map<SensorType, PairedSensor>>,
    sensorStatus: StateFlow<SensorStatus>
) : ViewModel() {

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    /** The Devices row's subtitle; no lease: the status is whatever another holder keeps up. */
    val devicesSummary: StateFlow<DevicesSummary> =
        combine(pairedSensors, sensorStatus) { sensors, status ->
            DevicesSummary.of(sensors[SensorType.HEART_RATE], status.link)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DevicesSummary.of(pairedSensors.value[SensorType.HEART_RATE], sensorStatus.value.link)
        )

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
                gpsDiagnostics = userPreferences.gpsDiagnostics,
                stepsForDistance = StepTrackingAccess.isStepTrackingAllowed(context),
                hasStepSensor = StepTrackingAccess.hasStepSensor(context),
                autoPause = userPreferences.autoPause,
                themeMode = userPreferences.themeMode,
                appLanguage = userPreferences.appLanguage,
                isFirstLaunch = userPreferences.isFirstLaunch,
                startCountdownSeconds = userPreferences.startCountdownSeconds
            ).withHrMax()
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
        ).withHrMax()
    }

    /** [bpm] 0: back to "by age". */
    fun updateHrMaxManual(bpm: Int) {
        userPreferences.hrMaxManual = bpm
        _settingsState.value = _settingsState.value.withHrMax()
    }

    /** The maximum heart rate as the preferences resolve it today. */
    private fun SettingsState.withHrMax(): SettingsState = copy(
        hrMax = userPreferences.hrMax(),
        hrMaxByAge = HrMax.byAge(userPreferences.userBirthDate, LocalDate.now(), ZoneId.systemDefault())
    )
    
    fun updateUserGender(gender: String) {
        userPreferences.userGender = gender
        _settingsState.value = _settingsState.value.copy(userGender = gender)
    }

    fun updateUnitSystem(unitSystem: String) {
        userPreferences.unitSystem = unitSystem
        _settingsState.value = _settingsState.value.copy(unitSystem = unitSystem)
    }

    fun updateGpsDiagnostics(enabled: Boolean) {
        userPreferences.gpsDiagnostics = enabled
        _settingsState.value = _settingsState.value.copy(gpsDiagnostics = enabled)
    }

    /**
     * Turns steps for distance on or off. Turning on without the permission is finished by
     * the fragment's permission request, which reports back via [onStepPermissionResult].
     */
    fun updateStepsForDistance(enabled: Boolean) {
        userPreferences.stepsForDistanceEnabled = enabled
        refreshStepsForDistance()
    }

    fun onStepPermissionResult(granted: Boolean) {
        userPreferences.stepPermissionAsked = true
        if (!granted) userPreferences.stepsForDistanceEnabled = false
        refreshStepsForDistance()
    }

    /** Re-reads the effective state, e.g. after the user changed permissions in system settings. */
    fun refreshStepsForDistance() {
        _settingsState.value = _settingsState.value.copy(
            stepsForDistance = StepTrackingAccess.isStepTrackingAllowed(context)
        )
    }

    fun updateVoiceFeedback(voiceFeedback: Boolean) {
        userPreferences.voiceFeedback = voiceFeedback
        _settingsState.value = _settingsState.value.copy(voiceFeedback = voiceFeedback)
    }

    /** No step permission is asked for: without steps auto-pause works by GPS alone. */
    fun updateAutoPause(enabled: Boolean) {
        userPreferences.autoPause = enabled
        _settingsState.value = _settingsState.value.copy(autoPause = enabled)
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
    private val userPreferences: UserPreferences,
    private val pairedSensors: StateFlow<Map<SensorType, PairedSensor>>,
    private val sensorStatus: StateFlow<SensorStatus>
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(context.applicationContext, userPreferences, pairedSensors, sensorStatus) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
