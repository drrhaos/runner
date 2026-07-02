package com.example.runner.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Управление настройками пользователя
 */
class UserPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "runner_preferences"
        
        // Ключи настроек
        private const val KEY_USER_WEIGHT = "user_weight"
        private const val KEY_USER_HEIGHT = "user_height"
        private const val KEY_USER_BIRTH_DATE = "user_birth_date"
        private const val KEY_USER_GENDER = "user_gender"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_AUTO_PAUSE = "auto_pause"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_GPS_ACCURACY = "gps_accuracy"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_START_COUNTDOWN = "start_countdown_seconds"
        
        // Значения по умолчанию
        private const val DEFAULT_WEIGHT = 70f
        private const val DEFAULT_HEIGHT = 175f
        private const val DEFAULT_BIRTH_DATE = 0L
        private const val DEFAULT_GENDER = "male"
        private const val DEFAULT_UNIT_SYSTEM = "metric"
        private const val DEFAULT_AUTO_PAUSE = true
        private const val DEFAULT_VOICE_FEEDBACK = false
        private const val DEFAULT_GPS_ACCURACY = "high"
        private const val DEFAULT_FIRST_LAUNCH = true
        private val DEFAULT_THEME_MODE = ThemeUtils.THEME_SYSTEM
        private const val DEFAULT_APP_LANGUAGE = "en"
        private const val DEFAULT_START_COUNTDOWN = 5
    }
    
    /**
     * Вес пользователя в кг
     */
    var userWeight: Float
        get() = prefs.getFloat(KEY_USER_WEIGHT, DEFAULT_WEIGHT)
        set(value) = prefs.edit().putFloat(KEY_USER_WEIGHT, value).apply()
    
    /**
     * Рост пользователя в см
     */
    var userHeight: Float
        get() = prefs.getFloat(KEY_USER_HEIGHT, DEFAULT_HEIGHT)
        set(value) = prefs.edit().putFloat(KEY_USER_HEIGHT, value).apply()
    
    /**
     * Дата рождения пользователя (timestamp)
     */
    var userBirthDate: Long
        get() = prefs.getLong(KEY_USER_BIRTH_DATE, DEFAULT_BIRTH_DATE)
        set(value) = prefs.edit().putLong(KEY_USER_BIRTH_DATE, value).apply()
    
    /**
     * Пол пользователя ("male" или "female")
     */
    var userGender: String
        get() = prefs.getString(KEY_USER_GENDER, DEFAULT_GENDER) ?: DEFAULT_GENDER
        set(value) = prefs.edit().putString(KEY_USER_GENDER, value).apply()
    
    /**
     * Возраст пользователя (вычисляется из даты рождения)
     */
    val userAge: Int
        get() {
            if (userBirthDate == 0L) return 0
            val birthDate = java.util.Date(userBirthDate)
            val currentDate = java.util.Date()
            val diffInMillis = currentDate.time - birthDate.time
            return (diffInMillis / (365.25 * 24 * 60 * 60 * 1000)).toInt()
        }
    
    /**
     * Система единиц измерения
     */
    var unitSystem: String
        get() = prefs.getString(KEY_UNIT_SYSTEM, DEFAULT_UNIT_SYSTEM) ?: DEFAULT_UNIT_SYSTEM
        set(value) = prefs.edit().putString(KEY_UNIT_SYSTEM, value).apply()
    
    /**
     * Автоматическая пауза при остановке
     */
    var autoPause: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE, value).apply()
    
    /**
     * Голосовые уведомления
     */
    var voiceFeedback: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FEEDBACK, DEFAULT_VOICE_FEEDBACK)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, value).apply()
    
    /**
     * Точность GPS
     */
    var gpsAccuracy: String
        get() = prefs.getString(KEY_GPS_ACCURACY, DEFAULT_GPS_ACCURACY) ?: DEFAULT_GPS_ACCURACY
        set(value) = prefs.edit().putString(KEY_GPS_ACCURACY, value).apply()

    /**
     * Режим темы приложения (system/light/dark)
     */
    var themeMode: String
        get() = ThemeUtils.ensureValidMode(prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE)
        set(value) {
            val sanitized = ThemeUtils.ensureValidMode(value)
            prefs.edit().putString(KEY_THEME_MODE, sanitized).apply()
        }
    
    /**
     * Первый запуск приложения
     */
    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, DEFAULT_FIRST_LAUNCH)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    /**
     * Язык приложения (en/ru)
     */
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    /**
     * Количество секунд обратного отсчета перед стартом тренировки
     */
    var startCountdownSeconds: Int
        get() = prefs.getInt(KEY_START_COUNTDOWN, DEFAULT_START_COUNTDOWN)
        set(value) = prefs.edit().putInt(KEY_START_COUNTDOWN, value).apply()

    /**
     * Сбрасывает все настройки к значениям по умолчанию
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Проверяет, является ли система единиц метрической
     */
    fun isMetricSystem(): Boolean = unitSystem == "metric"
    
    /**
     * Получает настройки GPS точности
     */
    fun getGpsAccuracyLevel(): String = gpsAccuracy
}
