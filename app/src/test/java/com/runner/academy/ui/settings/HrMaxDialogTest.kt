package com.runner.academy.ui.settings

import com.runner.academy.util.HrMax
import com.runner.academy.util.HrMaxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrMaxDialogTest {

    private val auto180 = HrMax(180, HrMaxSource.AUTO)
    private val manual190 = HrMax(190, HrMaxSource.MANUAL)

    @Test
    fun `the picker starts at the current HRmax, else at 190`() {
        assertEquals(180, HrMaxDialog.initialBpm(auto180))
        assertEquals(190, HrMaxDialog.initialBpm(manual190))
        assertEquals(190, HrMaxDialog.initialBpm(null))
    }

    @Test
    fun `saving an untouched automatic value writes nothing`() {
        assertNull(HrMaxDialog.manualToSave(auto180, 180))
    }

    @Test
    fun `any other value is saved as manual`() {
        assertEquals(185, HrMaxDialog.manualToSave(auto180, 185))
        assertEquals(190, HrMaxDialog.manualToSave(manual190, 190))
        assertEquals(190, HrMaxDialog.manualToSave(null, 190))
    }

    @Test
    fun `over a manual value the neutral button goes back by age, or turns the zones off without an age`() {
        assertEquals(HrMaxDialog.Neutral.BY_AGE, HrMaxDialog.neutral(manual190, byAge = auto180))
        assertEquals(HrMaxDialog.Neutral.ZONES_OFF, HrMaxDialog.neutral(manual190, byAge = null))
    }

    @Test
    fun `without a manual value there is nothing to clear`() {
        assertNull(HrMaxDialog.neutral(auto180, byAge = auto180))
        assertNull(HrMaxDialog.neutral(null, byAge = null))
    }

    @Test
    fun `the explanation tells no birth date from an age out of 10 to 100`() {
        assertEquals(HrMaxDialog.Explanation.FORMULA, HrMaxDialog.explanation(birthDateMs = 500L, byAge = auto180))
        assertEquals(HrMaxDialog.Explanation.NO_BIRTH_DATE, HrMaxDialog.explanation(birthDateMs = 0L, byAge = null))
        assertEquals(HrMaxDialog.Explanation.AGE_OUT_OF_RANGE, HrMaxDialog.explanation(birthDateMs = 500L, byAge = null))
        // Before 1970 is a real date
        assertEquals(HrMaxDialog.Explanation.AGE_OUT_OF_RANGE, HrMaxDialog.explanation(birthDateMs = -500L, byAge = null))
    }

    @Test
    fun `the preview lists Z5 down to Z1 with their beats`() {
        assertEquals(
            listOf(5 to 171..190, 4 to 152..170, 3 to 133..151, 2 to 114..132, 1 to 95..113),
            HrMaxDialog.zonesPreview(190)
        )
    }
}
