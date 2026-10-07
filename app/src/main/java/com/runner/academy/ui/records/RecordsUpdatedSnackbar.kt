package com.runner.academy.ui.records

import android.view.View
import androidx.navigation.NavController
import com.google.android.material.snackbar.Snackbar
import com.runner.academy.R

/** "Records updated: N" after an import, with "Open" to the records screen unless it is shown. */
object RecordsUpdatedSnackbar {

    private const val MAX_LINES = 4

    /** [message]: what to say before the count (the import result), or null for the count alone. */
    fun show(view: View, navController: NavController, count: Int, message: String? = null, anchor: View? = null) {
        val updated = view.resources.getQuantityString(R.plurals.records_updated, count, count)
        val text = if (message == null) updated else "$message\n$updated"
        val snackbar = Snackbar.make(view, text, Snackbar.LENGTH_LONG).setTextMaxLines(MAX_LINES)
        if (navController.currentDestination?.id != R.id.nav_records) {
            snackbar.setAction(R.string.records_updated_open) {
                if (navController.currentDestination?.id != R.id.nav_records) navController.navigate(R.id.nav_records)
            }
        }
        if (anchor != null) snackbar.anchorView = anchor
        snackbar.show()
    }
}
