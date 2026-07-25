package com.runner.academy.util

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Тесты для UserPreferences - управление настройками пользователя
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserPreferencesTest {

    private lateinit var context: Context
    private lateinit var userPreferences: UserPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        userPreferences = UserPreferences(context)
        // Очищаем настройки перед каждым тестом
        userPreferences.resetToDefaults()
    }

    @Test
    fun userweight_should_have_default_value() {
        // When
        val weight = userPreferences.userWeight

        // Then
        assertEquals("Should have default weight of 70kg", 70f, weight, 0.01f)
    }

    @Test
    fun userweight_should_save_and_load_correctly() {
        // Given
        val testWeight = 75.5f

        // When
        userPreferences.userWeight = testWeight
        val loadedWeight = userPreferences.userWeight

        // Then
        assertEquals("Should save and load weight correctly", testWeight, loadedWeight, 0.01f)
    }

    @Test
    fun userheight_should_have_default_value() {
        // When
        val height = userPreferences.userHeight

        // Then
        assertEquals("Should have default height of 175cm", 175f, height, 0.01f)
    }

    @Test
    fun userheight_should_save_and_load_correctly() {
        // Given
        val testHeight = 180.5f

        // When
        userPreferences.userHeight = testHeight
        val loadedHeight = userPreferences.userHeight

        // Then
        assertEquals("Should save and load height correctly", testHeight, loadedHeight, 0.01f)
    }

    @Test
    fun usergender_should_have_default_value() {
        // When
        val gender = userPreferences.userGender

        // Then
        assertEquals("Should have default gender 'male'", "male", gender)
    }

    @Test
    fun usergender_should_save_and_load_correctly() {
        // Given
        val testGender = "female"

        // When
        userPreferences.userGender = testGender
        val loadedGender = userPreferences.userGender

        // Then
        assertEquals("Should save and load gender correctly", testGender, loadedGender)
    }

    @Test
    fun userbirthdate_should_have_default_value() {
        // When
        val birthDate = userPreferences.userBirthDate

        // Then
        assertEquals("Should have default birth date of 0", 0L, birthDate)
    }

    @Test
    fun userbirthdate_should_save_and_load_correctly() {
        // Given
        val testBirthDate = System.currentTimeMillis() - (25 * 365 * 24 * 60 * 60 * 1000L) // 25 лет назад

        // When
        userPreferences.userBirthDate = testBirthDate
        val loadedBirthDate = userPreferences.userBirthDate

        // Then
        assertEquals("Should save and load birth date correctly", testBirthDate, loadedBirthDate)
    }

    @Test
    fun userage_should_return_0_when_birth_date_is_not_set() {
        // Given
        userPreferences.userBirthDate = 0L

        // When
        val age = userPreferences.userAge

        // Then
        assertEquals("Should return 0 when birth date is not set", 0, age)
    }

    @Test
    fun userage_should_calculate_age_correctly() {
        // Given - 25 лет назад
        val birthDate = System.currentTimeMillis() - (25L * 365 * 24 * 60 * 60 * 1000)
        userPreferences.userBirthDate = birthDate

        // When
        val age = userPreferences.userAge

        // Then - возраст должен быть около 25 (с небольшой погрешностью)
        assertTrue("Age should be approximately 25", age >= 24 && age <= 26)
    }

    @Test
    fun unitsystem_should_have_default_value() {
        // When
        val unitSystem = userPreferences.unitSystem

        // Then
        assertEquals("Should have default unit system 'metric'", "metric", unitSystem)
    }

    @Test
    fun unitsystem_should_save_and_load_correctly() {
        // Given
        val testUnitSystem = "imperial"

        // When
        userPreferences.unitSystem = testUnitSystem
        val loadedUnitSystem = userPreferences.unitSystem

        // Then
        assertEquals("Should save and load unit system correctly", testUnitSystem, loadedUnitSystem)
    }

    @Test
    fun ismetricsystem_should_return_true_for_metric() {
        // Given
        userPreferences.unitSystem = "metric"

        // When
        val isMetric = userPreferences.isMetricSystem()

        // Then
        assertTrue("Should return true for metric system", isMetric)
    }

    @Test
    fun ismetricsystem_should_return_false_for_imperial() {
        // Given
        userPreferences.unitSystem = "imperial"

        // When
        val isMetric = userPreferences.isMetricSystem()

        // Then
        assertFalse("Should return false for imperial system", isMetric)
    }

    @Test
    fun autopause_should_have_default_value() {
        // When
        val autoPause = userPreferences.autoPause

        // Then
        assertTrue("Should have default autoPause of true", autoPause)
    }

    @Test
    fun autopause_should_save_and_load_correctly() {
        // Given
        val testAutoPause = false

        // When
        userPreferences.autoPause = testAutoPause
        val loadedAutoPause = userPreferences.autoPause

        // Then
        assertEquals("Should save and load autoPause correctly", testAutoPause, loadedAutoPause)
    }

    @Test
    fun voicefeedback_should_have_default_value() {
        // When
        val voiceFeedback = userPreferences.voiceFeedback

        // Then
        assertFalse("Should have default voiceFeedback of false", voiceFeedback)
    }

    @Test
    fun voicefeedback_should_save_and_load_correctly() {
        // Given
        val testVoiceFeedback = true

        // When
        userPreferences.voiceFeedback = testVoiceFeedback
        val loadedVoiceFeedback = userPreferences.voiceFeedback

        // Then
        assertEquals("Should save and load voiceFeedback correctly", testVoiceFeedback, loadedVoiceFeedback)
    }

    @Test
    fun gpsaccuracy_should_have_default_value() {
        // When
        val gpsAccuracy = userPreferences.gpsAccuracy

        // Then
        assertEquals("Should have default gpsAccuracy of 'high'", "high", gpsAccuracy)
    }

    @Test
    fun gpsaccuracy_should_save_and_load_correctly() {
        // Given
        val testGpsAccuracy = "medium"

        // When
        userPreferences.gpsAccuracy = testGpsAccuracy
        val loadedGpsAccuracy = userPreferences.gpsAccuracy

        // Then
        assertEquals("Should save and load gpsAccuracy correctly", testGpsAccuracy, loadedGpsAccuracy)
    }

    @Test
    fun getgpsaccuracylevel_should_return_current_gps_accuracy() {
        // Given
        val testGpsAccuracy = "low"
        userPreferences.gpsAccuracy = testGpsAccuracy

        // When
        val accuracyLevel = userPreferences.getGpsAccuracyLevel()

        // Then
        assertEquals("Should return current gps accuracy level", testGpsAccuracy, accuracyLevel)
    }

    @Test
    fun isfirstlaunch_should_have_default_value() {
        // When
        val isFirstLaunch = userPreferences.isFirstLaunch

        // Then
        assertTrue("Should have default isFirstLaunch of true", isFirstLaunch)
    }

    @Test
    fun isfirstlaunch_should_save_and_load_correctly() {
        // Given
        val testIsFirstLaunch = false

        // When
        userPreferences.isFirstLaunch = testIsFirstLaunch
        val loadedIsFirstLaunch = userPreferences.isFirstLaunch

        // Then
        assertEquals("Should save and load isFirstLaunch correctly", testIsFirstLaunch, loadedIsFirstLaunch)
    }

    @Test
    fun resettodefaults_should_clear_all_preferences() {
        // Given - устанавливаем все значения
        userPreferences.userWeight = 80f
        userPreferences.userHeight = 190f
        userPreferences.userGender = "female"
        userPreferences.unitSystem = "imperial"
        userPreferences.autoPause = false
        userPreferences.voiceFeedback = true
        userPreferences.gpsAccuracy = "low"
        userPreferences.isFirstLaunch = false

        // When
        userPreferences.resetToDefaults()

        // Then - все значения должны вернуться к дефолтным
        assertEquals("Weight should reset to default", 70f, userPreferences.userWeight, 0.01f)
        assertEquals("Height should reset to default", 175f, userPreferences.userHeight, 0.01f)
        assertEquals("Gender should reset to default", "male", userPreferences.userGender)
        assertEquals("Unit system should reset to default", "metric", userPreferences.unitSystem)
        assertTrue("Auto pause should reset to default", userPreferences.autoPause)
        assertFalse("Voice feedback should reset to default", userPreferences.voiceFeedback)
        assertEquals("GPS accuracy should reset to default", "high", userPreferences.gpsAccuracy)
        assertTrue("Is first launch should reset to default", userPreferences.isFirstLaunch)
    }

    @Test
    fun multiple_preferences_should_persist_independently() {
        // Given
        val weight = 65f
        val height = 170f
        val gender = "female"
        val unitSystem = "imperial"

        // When
        userPreferences.userWeight = weight
        userPreferences.userHeight = height
        userPreferences.userGender = gender
        userPreferences.unitSystem = unitSystem

        // Then - все значения должны сохраниться независимо
        assertEquals("Weight should persist", weight, userPreferences.userWeight, 0.01f)
        assertEquals("Height should persist", height, userPreferences.userHeight, 0.01f)
        assertEquals("Gender should persist", gender, userPreferences.userGender)
        assertEquals("Unit system should persist", unitSystem, userPreferences.unitSystem)
    }
}

