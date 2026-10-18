package com.runner.academy.ui.records

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.runner.academy.R
import com.runner.academy.data.RecordDistance

/**
 * The badge of a record card: a white icon in a square of the distance's own color (the palette's
 * light and dark shades). A record not reached keeps its icon in a grey square.
 */
object RecordsLook {

    @DrawableRes
    fun icon(distance: RecordDistance): Int = when (distance) {
        RecordDistance.KM_1 -> R.drawable.ic_record_bolt
        RecordDistance.KM_5, RecordDistance.KM_10 -> R.drawable.ic_record_medal
        RecordDistance.HALF_MARATHON -> R.drawable.ic_record_route
        RecordDistance.MARATHON -> R.drawable.ic_record_finish
    }

    @ColorRes
    fun color(distance: RecordDistance): Int = when (distance) {
        RecordDistance.KM_1 -> R.color.ios_green
        RecordDistance.KM_5 -> R.color.ios_blue
        RecordDistance.KM_10 -> R.color.ios_indigo
        RecordDistance.HALF_MARATHON -> R.color.ios_orange
        RecordDistance.MARATHON -> R.color.ios_pink
    }

    @get:ColorRes
    val notReachedColor: Int get() = R.color.ios_fill
}
