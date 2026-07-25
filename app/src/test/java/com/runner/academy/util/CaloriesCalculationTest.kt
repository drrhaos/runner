package com.runner.academy.util

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для расчета калорий
 */
class CaloriesCalculationTest {

    @Test
    fun calculatecalories_should_use_weight_in_calculation() {
        // Given
        val distance = 5.0f // 5 км
        val weight1 = 60f // 60 кг
        val weight2 = 80f // 80 кг

        // When
        val calories1 = FormatUtils.calculateCalories(distance, weight1)
        val calories2 = FormatUtils.calculateCalories(distance, weight2)

        // Then
        // Калории должны быть разными для разного веса
        assertNotEquals(calories1, calories2)
        
        // Больший вес должен давать больше калорий
        assertTrue("Больший вес должен давать больше калорий", calories2 > calories1)
        
        // Проверяем формулу: distance * weight
        assertEquals((distance * weight1).toInt(), calories1)
        assertEquals((distance * weight2).toInt(), calories2)
    }

    @Test
    fun calculatecalories_should_use_default_weight_when_not_provided() {
        // Given
        val distance = 5.0f // 5 км
        val defaultWeight = 70f // вес по умолчанию

        // When
        val calories = FormatUtils.calculateCalories(distance)

        // Then
        assertEquals((distance * defaultWeight).toInt(), calories)
    }

    @Test
    fun calculatecalories_should_handle_zero_distance() {
        // Given
        val distance = 0f
        val weight = 70f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertEquals(0, calories)
    }

    @Test
    fun calculatecalories_should_handle_zero_weight() {
        // Given
        val distance = 5.0f
        val weight = 0f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertEquals(0, calories)
    }

    @Test
    fun calculatecalories_should_handle_small_values() {
        // Given
        val distance = 0.1f // 100 метров
        val weight = 50f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertEquals(5, calories) // 0.1 * 50 = 5
    }

    @Test
    fun calculatecalories_should_handle_large_values() {
        // Given
        val distance = 42.195f // марафон
        val weight = 70f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertEquals(2953, calories) // 42.195 * 70 = 2953.65 -> 2953
    }

    @Test
    fun calculatecalories_should_be_proportional_to_distance() {
        // Given
        val weight = 70f
        val distance1 = 5.0f
        val distance2 = 10.0f

        // When
        val calories1 = FormatUtils.calculateCalories(distance1, weight)
        val calories2 = FormatUtils.calculateCalories(distance2, weight)

        // Then
        // В 2 раза больше расстояние = в 2 раза больше калорий
        assertEquals(calories1 * 2, calories2)
    }

    @Test
    fun calculatecalories_should_be_proportional_to_weight() {
        // Given
        val distance = 5.0f
        val weight1 = 50f
        val weight2 = 100f

        // When
        val calories1 = FormatUtils.calculateCalories(distance, weight1)
        val calories2 = FormatUtils.calculateCalories(distance, weight2)

        // Then
        // В 2 раза больше вес = в 2 раза больше калорий
        assertEquals(calories1 * 2, calories2)
    }

    @Test
    fun calculatecalories_should_handle_decimal_values() {
        // Given
        val distance = 3.5f
        val weight = 65.5f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertEquals(229, calories) // 3.5 * 65.5 = 229.25 -> 229
    }

    @Test
    fun calculatecalories_should_return_integer() {
        // Given
        val distance = 1.5f
        val weight = 60.7f

        // When
        val calories = FormatUtils.calculateCalories(distance, weight)

        // Then
        assertTrue("Результат должен быть целым числом", calories is Int)
        assertEquals(91, calories) // 1.5 * 60.7 = 91.05 -> 91
    }

    @Test
    fun calculatecalories_formula_should_be_distance_times_weight() {
        // Given
        val testCases = listOf(
            Triple(1.0f, 50f, 50),
            Triple(2.0f, 60f, 120),
            Triple(0.5f, 80f, 40),
            Triple(10.0f, 70f, 700),
            Triple(21.1f, 65f, 1371) // полумарафон
        )

        // When & Then
        testCases.forEach { (distance, weight, expectedCalories) ->
            val actualCalories = FormatUtils.calculateCalories(distance, weight)
            assertEquals(
                "Для расстояния $distance км и веса $weight кг ожидается $expectedCalories калорий",
                expectedCalories,
                actualCalories
            )
        }
    }
}
