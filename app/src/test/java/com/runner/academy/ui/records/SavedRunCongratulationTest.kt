package com.runner.academy.ui.records

import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavedRunCongratulationTest {

    private fun arguments(justSaved: Boolean) = Bundle().apply { putBoolean(SavedRunCongratulation.KEY, justSaved) }

    @Test
    fun `right after saving the first card shown is announced, the next ones are not`() {
        val congratulation = SavedRunCongratulation(arguments(justSaved = true))

        assertTrue(congratulation.justSaved)
        assertTrue(congratulation.onCardShown())
        assertFalse(congratulation.onCardShown())
    }

    @Test
    fun `once shown, the details opened again from the back stack are no congratulation`() {
        val arguments = arguments(justSaved = true)
        SavedRunCongratulation(arguments).onCardShown()

        val again = SavedRunCongratulation(arguments)

        assertFalse(again.justSaved)
        assertFalse(again.onCardShown())
    }

    @Test
    fun `not shown yet, a recreated view still congratulates`() {
        val arguments = arguments(justSaved = true)
        SavedRunCongratulation(arguments)

        assertTrue(SavedRunCongratulation(arguments).justSaved)
    }

    @Test
    fun `opened from the list it is never a congratulation`() {
        val fromList = SavedRunCongratulation(arguments(justSaved = false))
        val noArguments = SavedRunCongratulation(null)

        assertFalse(fromList.justSaved)
        assertFalse(fromList.onCardShown())
        assertFalse(noArguments.justSaved)
        assertFalse(noArguments.onCardShown())
    }
}
