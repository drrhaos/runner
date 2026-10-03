package com.runner.academy.service

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartTest {

    @Test
    fun startThatSucceeds_reportsNoFailure() {
        var started = false
        assertNull(tryStartForeground { started = true })
        assertTrue(started)
    }

    @Test
    fun backgroundStartNotAllowed_isReturnedNotThrown() {
        // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException
        val refusal = IllegalStateException("startForeground not allowed")
        assertSame(refusal, tryStartForeground { throw refusal })
    }

    @Test
    fun locationTypeWithoutPermission_isReturnedNotThrown() {
        val refusal = SecurityException("location FGS requires permissions")
        assertSame(refusal, tryStartForeground { throw refusal })
    }

    @Test(expected = IllegalArgumentException::class)
    fun unrelatedBug_stillCrashes() {
        tryStartForeground { throw IllegalArgumentException("bad notification") }
    }
}
