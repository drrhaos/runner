package com.runner.academy.ui.trainingplan

import androidx.lifecycle.ViewModel
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.WorkoutType

/**
 * The unsaved state of the template editor. It lives in the editor fragment's ViewModel store,
 * so it survives a rotation and the trip to the settings («Open settings»: the editor waits on
 * the back stack while its view is destroyed).
 *
 * Known limitation: process death loses it (no SavedStateHandle) — the editor then starts over
 * from the saved template or the wizard, as before release 4.
 */
class TemplateEditorViewModel : ViewModel() {
    val drafts: MutableList<SegmentDraft> = mutableListOf()
    var selectedType: WorkoutType = WorkoutType.INTERVAL_TRAINING
    var selectedIcon: TrainingIcon = TrainingIcon.INTERVAL
    var iconChosenManually: Boolean = false

    /** The drafts are filled (from the database or the wizard): a rebuilt view must not refill them. */
    var initialized: Boolean = false
}
