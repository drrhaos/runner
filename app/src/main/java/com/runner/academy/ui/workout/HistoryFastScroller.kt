package com.runner.academy.ui.workout

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.runner.academy.data.WorkoutMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A fast scroller over the workout list: a thumb on the right edge shows while the list
 * scrolls; dragging it moves through the whole history, and a bubble beside the finger names
 * the month and year of the place reached ([HistoryIndex], so unloaded pages are named too).
 *
 * It lies over the whole list but takes a touch only when it lands on the visible thumb, so the
 * cards and their buttons under the edge stay tappable. TalkBack users scroll the list itself.
 */
class HistoryFastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The months of the list; set whenever the list's filter or contents change. */
    var index: HistoryIndex = HistoryIndex.EMPTY
        set(value) {
            field = value
            if (!isUsable()) hideNow()
        }

    private var recyclerView: RecyclerView? = null

    private val thumbWidth = dp(6f)
    private val thumbWidthDragging = dp(8f)
    private val thumbHeight = dp(48f)
    private val thumbMarginEnd = dp(4f)
    private val touchWidth = dp(48f)
    private val bubbleGap = dp(16f)
    private val bubblePaddingH = dp(16f)
    private val bubbleHeight = dp(40f)

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@HistoryFastScroller, com.google.android.material.R.attr.colorPrimary)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@HistoryFastScroller, com.google.android.material.R.attr.colorPrimaryContainer)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@HistoryFastScroller, com.google.android.material.R.attr.colorOnPrimaryContainer)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val rect = RectF()

    /** Where the thumb is along the track, 0 (top) to 1 (bottom). */
    private var fraction = 0f

    /** True while a finger drags the thumb; a pull-to-refresh around the list must not start. */
    var dragging = false
        private set
    private var label: String? = null
    private var labelMonth: WorkoutMonth? = null

    /** 0 hidden … 1 shown; animated. */
    private var shown = 0f
    private var fade: ValueAnimator? = null
    private val hideLater = Runnable { animateShown(0f) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Follows [list]'s scrolling: the thumb shows on scroll and fades out after a pause. */
    fun attach(list: RecyclerView) {
        recyclerView = list
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dragging || dy == 0 || !isUsable()) return
                fraction = listFraction()
                animateShown(1f)
                scheduleHide()
                invalidate()
            }
        })
    }

    // A drag, never a click; hidden from TalkBack, which scrolls the list itself
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shown < 0.5f || !isUsable() || !onThumb(event.x, event.y)) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideLater)
                animateShown(1f)
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                label = null
                labelMonth = null
                scheduleHide()
                invalidate()
                return true
            }
        }
        return dragging
    }

    /** Moves the list to the place under [y] and names its month in the bubble. */
    private fun dragTo(y: Float) {
        val list = recyclerView ?: return
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        fraction = ((y - trackTop()) / trackLength()).coerceIn(0f, 1f)
        val position = (fraction * (count - 1)).roundToInt()
        list.stopScroll()
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        val month = index.monthAt(position)
        if (month != labelMonth) {
            if (labelMonth != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            labelMonth = month
            label = month?.let { MonthLabel.of(it) }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0f) return
        val alpha = (shown * 255).roundToInt()
        val centerY = thumbCenterY()

        val width = if (dragging) thumbWidthDragging else thumbWidth
        val right = this.width - thumbMarginEnd
        rect.set(right - width, centerY - thumbHeight / 2, right, centerY + thumbHeight / 2)
        thumbPaint.alpha = alpha
        canvas.drawRoundRect(rect, width / 2, width / 2, thumbPaint)

        val text = label ?: return
        val textWidth = textPaint.measureText(text)
        val bubbleRight = right - width - bubbleGap
        val top = (centerY - bubbleHeight / 2).coerceIn(0f, (height - bubbleHeight).coerceAtLeast(0f))
        rect.set(bubbleRight - textWidth - 2 * bubblePaddingH, top, bubbleRight, top + bubbleHeight)
        bubblePaint.alpha = alpha
        canvas.drawRoundRect(rect, bubbleHeight / 2, bubbleHeight / 2, bubblePaint)
        textPaint.alpha = alpha
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, rect.left + bubblePaddingH, baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideLater)
        fade?.cancel()
        super.onDetachedFromWindow()
    }

    /** Worth a fast scroller: at least two months and a list longer than a few screens. */
    private fun isUsable(): Boolean {
        val list = recyclerView ?: return false
        val count = list.adapter?.itemCount ?: 0
        return index.months.size >= 2 && count >= MIN_ITEMS
    }

    /** The first visible position over the positions the top can reach. */
    private fun listFraction(): Float {
        val list = recyclerView ?: return 0f
        val manager = list.layoutManager as? LinearLayoutManager ?: return 0f
        val count = list.adapter?.itemCount ?: 0
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val visible = (manager.findLastVisibleItemPosition() - first + 1).coerceAtLeast(1)
        val reachable = (count - visible).coerceAtLeast(1)
        return if (!list.canScrollVertically(1)) 1f else (first.toFloat() / reachable).coerceIn(0f, 1f)
    }

    private fun onThumb(x: Float, y: Float): Boolean =
        x >= width - touchWidth && abs(y - thumbCenterY()) <= thumbHeight

    /** The thumb's centre, where a drag grabs it. */
    internal fun thumbCenterY(): Float = trackTop() + fraction * trackLength()

    /** The month the bubble names while dragging; null otherwise. */
    internal val bubbleLabel: String? get() = label

    private fun trackTop() = thumbHeight / 2

    private fun trackLength() = (height - thumbHeight).coerceAtLeast(1f)

    private fun scheduleHide() {
        removeCallbacks(hideLater)
        postDelayed(hideLater, HIDE_DELAY_MS)
    }

    private fun hideNow() {
        removeCallbacks(hideLater)
        fade?.cancel()
        dragging = false
        shown = 0f
        invalidate()
    }

    private fun animateShown(target: Float) {
        if (shown == target && fade?.isRunning != true) return
        fade?.cancel()
        fade = ValueAnimator.ofFloat(shown, target).apply {
            duration = FADE_MS
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** Below this many workouts the list is scrolled by hand. */
        const val MIN_ITEMS = 30
        const val HIDE_DELAY_MS = 1_500L
        const val FADE_MS = 200L
    }
}
