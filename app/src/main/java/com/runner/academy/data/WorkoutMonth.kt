package com.runner.academy.data

/** How many workouts of a list fall in one calendar month (local time); [month] is 1..12. */
data class WorkoutMonth(val year: Int, val month: Int, val count: Int)
