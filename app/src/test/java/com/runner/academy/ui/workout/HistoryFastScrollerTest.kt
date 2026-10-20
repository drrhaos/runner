package com.runner.academy.ui.workout

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.R
import com.runner.academy.data.WorkoutMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru")
class HistoryFastScrollerTest {

    private lateinit var list: RecyclerView
    private lateinit var scroller: HistoryFastScroller
    private lateinit var parent: FrameLayout

    /** 100 rows of 100 px: four months of 25. */
    private val index = HistoryIndex(
        listOf(WorkoutMonth(2026, 10, 25), WorkoutMonth(2026, 9, 25), WorkoutMonth(2026, 8, 25), WorkoutMonth(2025, 12, 25))
    )

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Runner)
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Rows(100)
        }
        scroller = HistoryFastScroller(context)
        parent = FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        scroller.attach(list)
        scroller.index = index
        layout()
    }

    private fun layout() {
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 2000)
    }

    private fun touch(action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return scroller.onTouchEvent(event).also { event.recycle() }
    }

    /** Scrolling shows the thumb (after its fade-in). */
    private fun showThumb() {
        list.scrollBy(0, 500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    }

    @Test
    fun `a touch off the thumb goes to the list below`() {
        showThumb()
        assertFalse(touch(MotionEvent.ACTION_DOWN, 10f, scroller.thumbCenterY()))
    }

    @Test
    fun `a hidden thumb takes no touch`() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 995f, scroller.thumbCenterY()))
    }

    @Test
    fun `dragging the thumb to the bottom reaches the oldest month and names it`() {
        showThumb()
        assertTrue(touch(MotionEvent.ACTION_DOWN, 995f, scroller.thumbCenterY()))
        touch(MotionEvent.ACTION_MOVE, 995f, 2000f)
        layout()

        assertEquals("Декабрь 2025", scroller.bubbleLabel)
        assertEquals(99, (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition())

        touch(MotionEvent.ACTION_UP, 995f, 2000f)
        assertNull(scroller.bubbleLabel)
    }

    @Test
    fun `dragging to the middle names the month there`() {
        showThumb()
        touch(MotionEvent.ACTION_DOWN, 995f, scroller.thumbCenterY())
        // Three fifths down the track: about position 60, inside August (50..74)
        touch(MotionEvent.ACTION_MOVE, 995f, 1200f)
        assertEquals("Август 2026", scroller.bubbleLabel)
    }

    @Test
    fun `a short history gets no thumb`() {
        scroller.index = HistoryIndex(listOf(WorkoutMonth(2026, 10, 100)))
        showThumb()
        assertFalse(touch(MotionEvent.ACTION_DOWN, 995f, scroller.thumbCenterY()))
    }

    private class Rows(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                TextView(parent.context).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100) }
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = position.toString()
        }

        override fun getItemCount() = count
    }
}
