package com.runner.academy.ui.records

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.runner.academy.R
import com.runner.academy.RunnerApplication
import com.runner.academy.data.BestEffort
import com.runner.academy.data.EffortRow
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RunnerApplication::class, qualifiers = "en")
class RecordsAdapterTest {

    private val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        .apply { setTheme(R.style.Theme_Runner) }
    private val adapter = RecordsAdapter(onWorkoutClick = {}, onHistoryToggle = {})

    private fun bind(row: RecordsRow): View {
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        adapter.submitList(listOf(RecordsAdapter.Item(row, expanded = false)))
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.badgeColor() = findViewById<View>(R.id.view_record_badge).backgroundTintList!!.defaultColor
    private fun color(res: Int) = ContextCompat.getColor(activity, res)

    private val date = Date(1_790_000_000_000L)
    private fun record(distance: RecordDistance, elapsedMs: Long) =
        EffortRow(BestEffort(1L, distance.meters, elapsedMs, 0L, elapsedMs, 0f), date, WorkoutType.EASY_RUN)

    @Test
    fun `each distance has its own icon and color`() {
        assertEquals(
            listOf(R.drawable.ic_record_bolt, R.drawable.ic_record_medal, R.drawable.ic_record_medal, R.drawable.ic_record_route, R.drawable.ic_record_finish),
            RecordDistance.entries.map { RecordsLook.icon(it) }
        )
        assertEquals(
            listOf(R.color.ios_green, R.color.ios_blue, R.color.ios_indigo, R.color.ios_orange, R.color.ios_pink),
            RecordDistance.entries.map { RecordsLook.color(it) }
        )
    }

    @Test
    fun `a record shows its time big, the pace and the date beside it, in the distance's color`() {
        val view = bind(RecordsRow(RecordDistance.KM_5, record(RecordDistance.KM_5, 1_471_000L), emptyList()))

        assertEquals("5 km", view.text(R.id.textView_record_distance))
        assertEquals("24:31", view.text(R.id.textView_record_time))
        assertEquals(FormatUtils.formatDate(date), view.text(R.id.textView_record_date))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_record_result).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.textView_record_hint).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.imageView_record_chevron).visibility)
        assertEquals(color(R.color.ios_blue), view.badgeColor())
    }

    @Test
    fun `a record not reached is grey, asks to run the distance and opens nothing`() {
        val view = bind(RecordsRow(RecordDistance.MARATHON, null, emptyList()))

        assertEquals("Run 42.2 km in one workout", view.text(R.id.textView_record_hint))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.textView_record_hint).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.layout_record_result).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.imageView_record_chevron).visibility)
        assertEquals(color(R.color.ios_fill), view.badgeColor())
        assertEquals(
            R.drawable.ic_record_finish,
            org.robolectric.Shadows.shadowOf(view.findViewById<ImageView>(R.id.imageView_record_icon).drawable).createdFromResId
        )
        assertFalse(view.findViewById<View>(R.id.row_record_main).hasOnClickListeners())
    }
}
