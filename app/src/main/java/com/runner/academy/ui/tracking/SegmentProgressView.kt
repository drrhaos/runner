package com.runner.academy.ui.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.ui.trainingplan.formatTemplateScheme
import com.runner.academy.ui.trainingplan.formatTemplateSchemeDetailed
import kotlin.math.max
import kotlin.math.min

/**
 * Visual scheme of a base workout: colored blocks by [SegmentKind] with short labels.
 * Current segment fills left-to-right; completed are solid; upcoming are muted.
 */
class SegmentProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            9f,
            resources.displayMetrics
        )
        color = ContextCompat.getColor(context, R.color.segment_scheme_label)
    }
    private val rect = RectF()

    private var segments: List<WorkoutTemplateSegment> = emptyList()
    private var weights: FloatArray = FloatArray(0)
    private var labels: Array<String> = emptyArray()
    private var currentIndex: Int = 0
    private var currentProgress: Float = 0f

    private val density = resources.displayMetrics.density
    private val gapPx = 3f * density
    private val cornerPx = 4f * density
    private val barHeightPx = 11f * density
    private val labelGapPx = 2f * density
    private val labelAreaPx = 12f * density
    private val minLabelWidthPx = 14f * density
    private val dimColor = ContextCompat.getColor(context, R.color.segment_track_dim)
    private val currentStrokeColor = ContextCompat.getColor(context, R.color.segment_scheme_current_stroke)

    /** The target's indicator under the current segment; null: none (no target, a preview). */
    var target: TargetIndicatorLook? = null
        private set
    private var targetColor = 0
    private var targetGlyph: Drawable? = null
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targetRadiusPx = 6f * density
    private val targetStrokePx = 1.5f * density
    private val targetGlyphPx = 9f * density

    fun setSegments(segments: List<WorkoutTemplateSegment>) {
        this.segments = segments
        weights = FloatArray(segments.size) { i -> relativeWeight(segments[i]) }
        labels = Array(segments.size) { i -> shortLabel(segments, i) }
        currentIndex = 0
        currentProgress = 0f
        setTarget(null)
        contentDescription = if (segments.isEmpty()) {
            null
        } else {
            formatTemplateScheme(context, segments, forA11y = true)
        }
        updateDetailsClickListener()
        requestLayout()
        invalidate()
    }

    private fun updateDetailsClickListener() {
        if (segments.isEmpty()) {
            setOnClickListener(null)
            isClickable = false
            isFocusable = false
            return
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.template_scheme_details_title)
                .setMessage(formatTemplateSchemeDetailed(context, segments))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    fun setProgress(index: Int, progress: Float) {
        currentIndex = index.coerceAtLeast(0)
        currentProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * The target's verdict as a 12dp circle with its glyph in place of the current segment's
     * label: filled, or a ring for a pace by steps. The bar itself keeps the segment's colours.
     * TalkBack hears the verdict as the bar's state ([TargetIndicatorLook.stateDescription],
     * without the value), set only when the verdict changes, not with every second's pace; the
     * scheme's description stays.
     */
    fun setTarget(look: TargetIndicatorLook?) {
        if (look == target) return
        val state = look?.stateDescription
        if (state != target?.stateDescription) ViewCompat.setStateDescription(this, state)
        target = look
        if (look != null) {
            targetColor = when (look.tone) {
                TargetIndicatorLook.Tone.IN -> ContextCompat.getColor(context, R.color.target_in)
                TargetIndicatorLook.Tone.OFF -> ContextCompat.getColor(context, R.color.target_off)
                TargetIndicatorLook.Tone.NEUTRAL ->
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            val glyphColor = if (look.outlined) targetColor else ContextCompat.getColor(context, R.color.target_glyph_on_fill)
            targetGlyph = AppCompatResources.getDrawable(context, look.icon)?.mutate()?.apply { setTint(glyphColor) }
        } else {
            targetGlyph = null
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (paddingTop + barHeightPx + labelGapPx + labelAreaPx + paddingBottom).toInt()
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
        val bottom = top + barHeightPx
        val labelY = bottom + labelGapPx - labelPaint.ascent()

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
                    if (currentProgress < 1f) {
                        dimPaint.color = dimColor
                        rect.set(x + filled, top, x + segW, bottom)
                        canvas.drawRoundRect(rect, cornerPx, cornerPx, dimPaint)
                    }
                    strokePaint.color = currentStrokeColor
                    rect.set(x, top, x + segW, bottom)
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, strokePaint)
                }
                else -> {
                    fillPaint.color = withAlpha(color, 0.38f)
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
                }
            }

            val look = target
            if (index == currentIndex && look != null) {
                // Drawn even on a segment narrower than a label, centred, past its edges if need be
                drawTarget(canvas, look, x + segW / 2f, bottom + labelGapPx + labelAreaPx / 2f)
            } else if (segW >= minLabelWidthPx) {
                val label = labels[index]
                if (label.isNotEmpty()) {
                    canvas.drawText(label, x + segW / 2f, labelY, labelPaint)
                }
            }
            x += segW + gapPx
        }
    }

    private fun drawTarget(canvas: Canvas, look: TargetIndicatorLook, segmentCx: Float, cy: Float) {
        // A narrow first or last segment: the circle stays inside the view
        val cx = segmentCx.coerceIn(targetRadiusPx, (width - targetRadiusPx).coerceAtLeast(targetRadiusPx))
        targetPaint.color = targetColor
        if (look.outlined) {
            targetPaint.style = Paint.Style.STROKE
            targetPaint.strokeWidth = targetStrokePx
            canvas.drawCircle(cx, cy, targetRadiusPx - targetStrokePx / 2f, targetPaint)
        } else {
            targetPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, targetRadiusPx, targetPaint)
        }
        targetGlyph?.let { glyph ->
            val half = targetGlyphPx / 2f
            glyph.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            glyph.draw(canvas)
        }
    }

    private fun shortLabel(all: List<WorkoutTemplateSegment>, index: Int): String {
        val segment = all[index]
        return when (segment.kind) {
            SegmentKind.WARMUP -> context.getString(R.string.segment_scheme_warmup)
            SegmentKind.RECOVERY -> context.getString(R.string.segment_scheme_recovery)
            SegmentKind.COOLDOWN -> context.getString(R.string.segment_scheme_cooldown)
            SegmentKind.CUSTOM -> context.getString(R.string.segment_scheme_custom)
            SegmentKind.WORK -> {
                val workNumber = all.take(index + 1).count { it.kind == SegmentKind.WORK }
                workNumber.toString()
            }
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
