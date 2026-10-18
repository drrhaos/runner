package com.runner.academy.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mikepenz.aboutlibraries.entity.Library
import com.runner.academy.RunnerApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RunnerApplication::class)
class OpenSourceLicensesDialogTest {

    private val libraries: List<Library> by lazy {
        OpenSourceLicensesDialog.libraries(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun library(uniqueId: String): Library = libraries.single { it.uniqueId == uniqueId }

    @Test
    fun `every library carries a license text`() {
        assertTrue(libraries.isNotEmpty())
        libraries.forEach { library ->
            assertTrue(library.uniqueId, library.licenses.isNotEmpty())
            library.licenses.forEach { license ->
                assertTrue("${library.uniqueId}: ${license.name}", !license.licenseContent.isNullOrBlank())
            }
        }
    }

    @Test
    fun `the Apache 2_0 text is the full license, not a link`() {
        val text = OpenSourceLicensesDialog.licenseText(library("org.osmdroid:osmdroid-android"))

        assertTrue(text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun `MPAndroidChart from JitPack gets its license from the config`() {
        assertEquals(listOf("Apache-2.0"), library("com.github.PhilJay:MPAndroidChart").licenses.map { it.spdxId })
    }

    @Test
    fun `the copied icons and the map data are listed`() {
        assertEquals(listOf("Apache-2.0"), library("com.google:material-design-icons").licenses.map { it.spdxId })
        assertTrue(
            OpenSourceLicensesDialog.licenseText(library("org.openstreetmap:map-data"))
                .contains("© OpenStreetMap contributors")
        )
    }

    @Test
    fun `the list runs A to Z and a title carries the version when there is one`() {
        val names = libraries.map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
        assertEquals("MPAndroidChart v3.1.0", OpenSourceLicensesDialog.title(library("com.github.PhilJay:MPAndroidChart")))
        assertEquals("Material Icons", OpenSourceLicensesDialog.title(library("com.google:material-design-icons")))
    }
}
