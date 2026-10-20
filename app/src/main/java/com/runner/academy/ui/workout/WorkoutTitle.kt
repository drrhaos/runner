package com.runner.academy.ui.workout

/** The name a workout goes by: from its file or the form, shown in the list and on its card. */
object WorkoutTitle {

    /** [raw] trimmed; null when blank (no name). */
    fun clean(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** The workout's [title], else the name of its type. */
    fun label(title: String?, typeName: String): String = clean(title) ?: typeName
}
