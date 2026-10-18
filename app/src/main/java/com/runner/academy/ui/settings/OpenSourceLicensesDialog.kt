package com.runner.academy.ui.settings

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext
import com.runner.academy.R

/**
 * Settings → "Open-source licenses": what the APK ships under someone else's license (Apache 2.0
 * requires its text with every copy). The list is res/raw/aboutlibraries.json, written at build
 * time by the aboutlibraries plugin from the dependencies and config/aboutlibraries.
 */
object OpenSourceLicensesDialog {

    /** The libraries, A to Z; read from the generated resource on each call. */
    fun libraries(context: Context): List<Library> =
        Libs.Builder().withContext(context).build().libraries
            .sortedBy { it.name.lowercase() }

    /** "MPAndroidChart v3.1.0"; the name alone when there is no version (icons, map data). */
    fun title(library: Library): String =
        listOfNotNull(library.name, library.artifactVersion?.takeIf { it.isNotBlank() })
            .joinToString(" ")

    /** The license names, then each license text (its link when the text is missing). */
    fun licenseText(library: Library): String =
        library.licenses.joinToString("\n\n") { license ->
            listOfNotNull(license.name, license.licenseContent?.takeIf { it.isNotBlank() } ?: license.url)
                .joinToString("\n\n")
        }

    fun show(context: Context) {
        val libraries = libraries(context)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_oss_licenses_title)
            .setItems(libraries.map(::title).toTypedArray()) { _, which ->
                showLicense(context, libraries[which])
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLicense(context: Context, library: Library) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title(library))
            .setMessage(licenseText(library))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
