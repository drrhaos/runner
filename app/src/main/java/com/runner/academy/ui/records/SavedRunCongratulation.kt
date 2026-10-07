package com.runner.academy.ui.records

import android.os.Bundle

/**
 * The justSaved argument of the details: the record card congratulates once per save. Once a
 * card was shown the argument is used up, so the details opened again from the back stack
 * (after "All records"), recreated or restored after the process died, show the calm card.
 */
class SavedRunCongratulation(private val arguments: Bundle?) {

    /** The card of this view is built as just saved. */
    val justSaved: Boolean = arguments?.getBoolean(KEY, false) ?: false

    private var pending = justSaved

    /** A card is shown: true only the first time after saving (TalkBack announces it). */
    fun onCardShown(): Boolean {
        if (!pending) return false
        pending = false
        arguments?.putBoolean(KEY, false)
        return true
    }

    companion object {
        /** The argument name in the navigation graph. */
        const val KEY = "justSaved"
    }
}
