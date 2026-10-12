package com.runner.academy.debug

import android.content.Context
import android.view.View
import com.runner.academy.sensor.HeartRateOverride
import kotlinx.coroutines.CoroutineScope

/** Release: no debug sensors. The debug build's twin has the same signatures. */
object DebugSensors {

    /** The settings row is an empty `Space` here. */
    @Suppress("UNUSED_PARAMETER")
    fun bind(row: View) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun heartRateOverride(context: Context, scope: CoroutineScope): HeartRateOverride? = null
}
