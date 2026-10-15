package com.runner.academy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings opened from the segment editor get Up, not the hamburger (review of r4-zone-target). */
class NavDestinationsTest {

    @Test
    fun `settings from the editor is not top-level, the drawer settings is`() {
        assertFalse(R.id.nav_settings_from_editor in NavDestinations.topLevel)
        assertTrue(R.id.nav_settings in NavDestinations.topLevel)
    }

    @Test
    fun `both settings destinations count as the settings screen`() {
        assertTrue(R.id.nav_settings in NavDestinations.settings)
        assertTrue(R.id.nav_settings_from_editor in NavDestinations.settings)
    }
}
