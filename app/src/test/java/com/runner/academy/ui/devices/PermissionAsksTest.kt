package com.runner.academy.ui.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAsksTest {

    @Test
    fun `a first dialog dismissed without an answer is not a refusal for good`() {
        val asks = PermissionAsks()

        asks.beforeRequest(rationale = false)

        assertFalse(asks.afterDenial(rationale = false))
    }

    @Test
    fun `a refusal that leaves a rationale can be asked again`() {
        val asks = PermissionAsks()

        asks.beforeRequest(rationale = false)

        assertFalse(asks.afterDenial(rationale = true))
    }

    @Test
    fun `refused after a rationale, with no rationale left, is for good`() {
        val asks = PermissionAsks()
        asks.beforeRequest(rationale = false)
        asks.afterDenial(rationale = true)

        asks.beforeRequest(rationale = true)

        assertTrue(asks.afterDenial(rationale = false))
    }

    @Test
    fun `a dismissal after a refusal keeps the rationale and is not for good`() {
        val asks = PermissionAsks()
        asks.beforeRequest(rationale = true)

        assertFalse(asks.afterDenial(rationale = true))
    }

    @Test
    fun `two quiet refusals in a row are for good (the system no longer shows the dialog)`() {
        val asks = PermissionAsks()
        asks.beforeRequest(rationale = false)
        assertFalse(asks.afterDenial(rationale = false))

        asks.beforeRequest(rationale = false)

        assertTrue(asks.afterDenial(rationale = false))
    }
}
