package com.runner.academy.service

/**
 * Runs a `startForeground` call the system may refuse when the service is started from the
 * background (sticky restart after process death): on Android 12+ with
 * `ForegroundServiceStartNotAllowedException` (an [IllegalStateException]), on 14+ for a
 * location-type service without background location with a [SecurityException].
 *
 * @return the refusal, or null once the service is in the foreground.
 */
internal inline fun tryStartForeground(start: () -> Unit): RuntimeException? =
    try {
        start()
        null
    } catch (e: IllegalStateException) {
        e
    } catch (e: SecurityException) {
        e
    }
