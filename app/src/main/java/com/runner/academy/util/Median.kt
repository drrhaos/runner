package com.runner.academy.util

/** The median (the mean of the middle two for an even count); null without values. Leaves this as it is. */
fun DoubleArray.median(): Double? {
    if (isEmpty()) return null
    val sorted = sortedArray()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** The median of these values, see [DoubleArray.median]. */
fun Collection<Double>.median(): Double? = toDoubleArray().median()
