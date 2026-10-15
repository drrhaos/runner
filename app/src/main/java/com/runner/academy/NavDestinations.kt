package com.runner.academy

/** Navigation destinations that the activity and screens treat as a group. */
object NavDestinations {

    /** Drawer screens: a hamburger, no Up arrow. */
    val topLevel: Set<Int> = setOf(
        R.id.nav_tracking,
        R.id.nav_workouts,
        R.id.nav_my_plan,
        R.id.nav_plans,
        R.id.nav_statistics,
        R.id.nav_records,
        R.id.nav_settings
    )

    /**
     * The settings screen: from the drawer, and from the segment editor with Up back to the
     * editor and its unsaved drafts ([R.id.nav_settings_from_editor] is not top-level).
     */
    val settings: Set<Int> = setOf(R.id.nav_settings, R.id.nav_settings_from_editor)
}
