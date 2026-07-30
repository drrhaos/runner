package com.runner.academy.ui.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal multi-segment progress bar colored by [SegmentKind].
 * Current segment fills left-to-right; completed are solid; upcoming are muted.
 */
class SegmentProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    private var segments: List<WorkoutTemplateSegment> = emptyList()
    private var weights: FloatArray = FloatArray(0)
    private var currentIndex: Int = 0
    private var currentProgress: Float = 0f

    private val gapPx = 3f * resources.displayMetrics.density
    private val cornerPx = 4f * resources.displayMetrics.density
    private val dimColor = ContextCompat.getColor(context, R.color.segment_track_dim)

    fun setSegments(segments: List<WorkoutTemplateSegment>) {
        this.segments = segments
        weights = FloatArray(segments.size) { i -> relativeWeight(segments[i]) }
        currentIndex = 0
        currentProgress = 0f
        invalidate()
    }

    fun setProgress(index: Int, progress: Float) {
        currentIndex = index.coerceAtLeast(0)
        currentProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (10f * resources.displayMetrics.density).toInt()
        val w = View.resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || width == 0 || height == 0) return

        val totalWeight = weights.sum().coerceAtLeast(0.0001f)
        val gaps = gapPx * max(0, segments.size - 1)
        val usable = (width - paddingLeft - paddingRight - gaps).coerceAtLeast(0f)
        var x = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()

        segments.forEachIndexed { index, segment ->
            val segW = usable * (weights[index] / totalWeight)
            val color = colorFor(segment.kind)
            rect.set(x, top, x + segW, bottom)

            when {
                index < currentIndex -> {
                    fillPaint.color = color
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
                }
                index == currentIndex -> {
                    dimPaint.color = withAlpha(color, 0.28f)
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, dimPaint)
                    val filled = segW * currentProgress
                    if (filled > 0.5f) {
                        fillPaint.color = color
                        rect.right = x + filled
                        canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
                    }
                    // subtle track overlay for upcoming part of current segment
                    if (currentProgress < 1f) {
                        dimPaint.color = dimColor
                        rect.set(x + filled, top, x + segW, bottom)
                        canvas.drawRoundRect(rect, cornerPx, cornerPx, dimPaint)
                    }
                }
                else -> {
                    fillPaint.color = withAlpha(color, 0.32f)
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
                }
            }
            x += segW + gapPx
        }
    }

    private fun colorFor(kind: SegmentKind): Int {
        val res = when (kind) {
            SegmentKind.WARMUP -> R.color.segment_warmup
            SegmentKind.WORK -> R.color.segment_work
            SegmentKind.RECOVERY -> R.color.segment_recovery
            SegmentKind.COOLDOWN -> R.color.segment_cooldown
            SegmentKind.CUSTOM -> R.color.segment_custom
        }
        return ContextCompat.getColor(context, res)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    companion object {
        /** Relative visual weight — duration in ms, or distance converted via pace. */
        fun relativeWeight(segment: WorkoutTemplateSegment): Float {
            return when (segment.goalType) {
                SegmentGoalType.DURATION ->
                    (segment.durationMs ?: 0L).toFloat().coerceAtLeast(1f)
                SegmentGoalType.DISTANCE -> {
                    val meters = segment.distanceMeters ?: 0f
                    val paceMinPerKm = segment.targetPaceMinPerKm?.takeIf { it > 0f } ?: 5.5f
                    (meters / 1000f * paceMinPerKm * 60_000f).coerceAtLeast(1f)
                }
            }
        }
    }
}
