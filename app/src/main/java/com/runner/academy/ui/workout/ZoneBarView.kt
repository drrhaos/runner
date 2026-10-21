package com.runner.academy.ui.workout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.runner.academy.R
import kotlin.math.max

/**
 * A row's share of the time in zones: a dim track across the row and the [fraction] filled in
 * [barColor]. A share above zero is never thinner than 2dp, so seconds in Z5 still show.
 */
class ZoneBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val minFillPx = 2f * density
    private val radiusPx = 3f * density
    private val rect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.segment_track_dim)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hr_zone_0)
    }

    /** 0..1 of the width. */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var barColor: Int
        get() = fillPaint.color
        set(value) {
            fillPaint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, trackPaint)
        if (fraction <= 0f) return
        rect.set(0f, 0f, max(minFillPx, w * fraction), h)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
    }
}
