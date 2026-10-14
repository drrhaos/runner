package com.runner.academy.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.runner.academy.voice.VoiceSettings
import com.runner.academy.voice.VoiceSettingsCodec
import java.time.LocalDate
import java.time.ZoneId

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
        private const val KEY_VOICE_FEEDBACK = VoiceSettingsCodec.KEY_ENABLED
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_START_COUNTDOWN = "start_countdown_seconds"
        private const val KEY_BATTERY_HINT_SHOWN = "battery_optimization_hint_shown"
        private const val KEY_GPS_DIAGNOSTICS = "gps_diagnostics"
        private const val KEY_STEPS_FOR_DISTANCE = "steps_for_distance"
        private const val KEY_STEP_PERMISSION_ASKED = "step_permission_asked"
        private const val KEY_STRIDE_MODEL = "stride_model"
        private const val KEY_AUTO_PAUSE = "auto_pause"
        private const val KEY_HR_MAX_MANUAL = "hr_max_manual"

        // Значения по умолчанию
        private const val DEFAULT_WEIGHT = 70f
        private const val DEFAULT_HEIGHT = 175f
        private const val DEFAULT_BIRTH_DATE = 0L
        private const val DEFAULT_GENDER = "male"
        private const val DEFAULT_UNIT_SYSTEM = "metric"
        private const val DEFAULT_VOICE_FEEDBACK = false
        private const val DEFAULT_GPS_DIAGNOSTICS = false
        private const val DEFAULT_STEPS_FOR_DISTANCE = true
        private const val DEFAULT_AUTO_PAUSE = false
        private const val DEFAULT_FIRST_LAUNCH = true
        private val DEFAULT_THEME_MODE = ThemeUtils.THEME_SYSTEM
        private const val DEFAULT_APP_LANGUAGE = "en"
        private const val DEFAULT_START_COUNTDOWN = 5
    }
    
    /** Подсказка про оптимизацию батареи показывается один раз. */
    var batteryOptimizationHintShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_HINT_SHOWN, value).apply()

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
        get() = ageOn(LocalDate.now(), ZoneId.systemDefault())

    /** Полных лет на [today] (0 — дата рождения не задана); то же правило, что у [hrMax] по возрасту. */
    fun ageOn(today: LocalDate, zone: ZoneId): Int = Ages.fullYears(userBirthDate, today, zone) ?: 0
    
    /**
     * Система единиц измерения
     */
    var unitSystem: String
        get() = prefs.getString(KEY_UNIT_SYSTEM, DEFAULT_UNIT_SYSTEM) ?: DEFAULT_UNIT_SYSTEM
        set(value) = prefs.edit().putString(KEY_UNIT_SYSTEM, value).apply()
    
    /**
     * Голосовые уведомления
     */
    var voiceFeedback: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FEEDBACK, DEFAULT_VOICE_FEEDBACK)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, value).apply()

    /**
     * Все настройки голоса; [voiceFeedback] — их главный переключатель ([VoiceSettings.enabled]).
     * Сервис читает их на каждом тике: смена посреди пробежки действует сразу.
     */
    fun voiceSettings(): VoiceSettings {
        // Only the 8 keys, not a copy of every preference; a value of another type reads as absent
        val values = HashMap<String, Any?>()
        fun read(key: String, get: () -> Any?) {
            if (prefs.contains(key)) values[key] = runCatching(get).getOrNull()
        }
        VoiceSettingsCodec.BOOLEAN_KEYS.forEach { key -> read(key) { prefs.getBoolean(key, false) } }
        VoiceSettingsCodec.STRING_KEYS.forEach { key -> read(key) { prefs.getString(key, null) } }
        VoiceSettingsCodec.INT_KEYS.forEach { key -> read(key) { prefs.getInt(key, 0) } }
        return VoiceSettingsCodec.decode(values)
    }

    fun setVoiceSettings(settings: VoiceSettings) {
        prefs.edit {
            VoiceSettingsCodec.encode(settings).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }

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
     * Записывать диагностику GPS (сырые точки, спутники) в файл тренировки. По умолчанию выключено.
     */
    var gpsDiagnostics: Boolean
        get() = prefs.getBoolean(KEY_GPS_DIAGNOSTICS, DEFAULT_GPS_DIAGNOSTICS)
        set(value) = prefs.edit().putBoolean(KEY_GPS_DIAGNOSTICS, value).apply()

    /**
     * Количество секунд обратного отсчета перед стартом тренировки
     */
    var startCountdownSeconds: Int
        get() = prefs.getInt(KEY_START_COUNTDOWN, DEFAULT_START_COUNTDOWN)
        set(value) = prefs.edit().putInt(KEY_START_COUNTDOWN, value).apply()

    /**
     * Шаги для дистанции при потере GPS. По умолчанию включено; без разрешения
     * ACTIVITY_RECOGNITION (API 29+) шаги всё равно не используются —
     * см. [StepTrackingAccess.isStepTrackingAllowed].
     */
    var stepsForDistanceEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEPS_FOR_DISTANCE, DEFAULT_STEPS_FOR_DISTANCE)
        set(value) = prefs.edit().putBoolean(KEY_STEPS_FOR_DISTANCE, value).apply()

    /**
     * Автопауза: часы «в движении» встают, когда бегун стоит. По умолчанию выключено;
     * в тренировках с отрезками не действует.
     */
    var autoPause: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }

    /** Максимальный пульс, заданный вручную; 0 — по возрасту (220 − возраст). */
    var hrMaxManual: Int
        get() = prefs.getInt(KEY_HR_MAX_MANUAL, 0)
        set(value) = prefs.edit { putInt(KEY_HR_MAX_MANUAL, value) }

    /** Максимальный пульс для зон; null — зоны выключены (нет ни даты рождения, ни ручного значения). */
    fun hrMax(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): HrMax? =
        HrMax.resolve(userBirthDate, hrMaxManual, today, zone)

    /** Пульсовые зоны по текущему [hrMax]; null — зоны выключены. */
    fun hrZones(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): HrZones? =
        hrMax(today, zone)?.zones

    /** Разрешение ACTIVITY_RECOGNITION уже запрашивалось — повторно автоматически не спрашиваем. */
    var stepPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_STEP_PERMISSION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_STEP_PERMISSION_ASKED, value).apply()

    /** Модель длины шага, обученная на прошлых пробежках (или из роста, если данных нет). */
    fun loadStrideModel(): StrideModel =
        StrideModel.deserialize(prefs.getString(KEY_STRIDE_MODEL, null), userHeight)

    fun saveStrideModel(model: StrideModel) {
        prefs.edit().putString(KEY_STRIDE_MODEL, model.serialize()).apply()
    }

    /**
     * Сбрасывает все настройки к значениям по умолчанию. Обученная модель шага и отметка
     * «разрешение на шаги уже спрашивали» — не настройки, а накопленное состояние: сохраняются.
     */
    fun resetToDefaults() {
        val stepPermissionAsked = stepPermissionAsked
        val strideModel = prefs.getString(KEY_STRIDE_MODEL, null)
        // Editor.clear() is applied before the puts of the same edit.
        val editor = prefs.edit().clear()
        if (stepPermissionAsked) editor.putBoolean(KEY_STEP_PERMISSION_ASKED, true)
        if (strideModel != null) editor.putString(KEY_STRIDE_MODEL, strideModel)
        editor.apply()
    }
    
    /**
     * Проверяет, является ли система единиц метрической
     */
    fun isMetricSystem(): Boolean = unitSystem == "metric"
}
