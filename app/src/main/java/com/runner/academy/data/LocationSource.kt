package com.runner.academy.data

/** Source of an individual track point (persisted in JSON). */
enum class LocationSource {
    GPS,
    NETWORK,
    PEDOMETER,
    NONE,
    MANUAL
}
