package com.example.runner

import org.junit.Test
import org.junit.Assert.*

/**
 * Простой тест для проверки TDD
 */
class SimpleTest {

    @Test
    fun `addition should work correctly`() {
        // Given
        val a = 2
        val b = 3

        // When
        val result = a + b

        // Then
        assertEquals(5, result)
    }

    @Test
    fun `string concatenation should work`() {
        // Given
        val str1 = "Hello"
        val str2 = "World"

        // When
        val result = str1 + " " + str2

        // Then
        assertEquals("Hello World", result)
    }

    @Test
    fun `boolean logic should work`() {
        // Given
        val condition = true

        // When & Then
        assertTrue(condition)
        assertFalse(!condition)
    }
}
