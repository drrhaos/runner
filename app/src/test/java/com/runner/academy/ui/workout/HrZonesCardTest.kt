package com.runner.academy.ui.workout

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.R
import com.runner.academy.util.HrMax
import com.runner.academy.util.HrMaxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HrZonesCardTest {

    private lateinit var context: Context
    private lateinit var card: LinearLayout
    private lateinit var title: TextView
    private lateinit var basis: TextView
    private lateinit var rows: LinearLayout
    private lateinit var zonesCard: HrZonesCard

    @Before
    fun setUp() {
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Runner)
        card = LinearLayout(context).apply { visibility = View.GONE }
        title = TextView(context)
        basis = TextView(context)
        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(title)
        card.addView(basis)
        card.addView(rows)
        zonesCard = HrZonesCard(card, title, basis, rows)
    }

    /** HRmax 190 by age: 45 s below Z1, 2:15 in Z4. */
    private fun show(): List<View> {
        zonesCard.show(HrZonesDisplay.of(HrMax(190, HrMaxSource.AUTO), longArrayOf(45_000, 0, 0, 0, 135_000, 0)))
        return (0 until rows.childCount).map { rows.getChildAt(it) }
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `no zones hides the card`() {
        card.visibility = View.VISIBLE

        zonesCard.show(HrZonesDisplay.None)

        assertEquals(View.GONE, card.visibility)
    }

    @Test
    fun `the card says what the zones are built on`() {
        show()

        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(ViewCompat.isAccessibilityHeading(title))
        assertEquals(
            context.getString(R.string.workout_details_hr_zones_basis, 190, context.getString(R.string.hr_max_source_auto_short)),
            basis.text.toString()
        )
    }

    @Test
    fun `six rows from Z5 down, empty ones at zero`() {
        val rowViews = show()

        assertEquals(6, rowViews.size)
        assertEquals("Z5", rowViews[0].text(R.id.textView_zone_badge))
        assertEquals("171–190", rowViews[0].text(R.id.textView_zone_range))
        assertEquals("0:00", rowViews[0].text(R.id.textView_zone_time))
        assertEquals(context.getString(R.string.workout_details_hr_zone_percent, 0), rowViews[0].text(R.id.textView_zone_percent))
        assertEquals("Z4", rowViews[1].text(R.id.textView_zone_badge))
        assertEquals("2:15", rowViews[1].text(R.id.textView_zone_time))
        assertEquals(context.getString(R.string.workout_details_hr_zone_percent, 75), rowViews[1].text(R.id.textView_zone_percent))
        assertEquals(0.75f, rowViews[1].findViewById<ZoneBarView>(R.id.view_zone_bar).fraction, 0.0001f)
    }

    @Test
    fun `below Z1 is a grey label instead of a badge`() {
        val below = show().last()

        assertEquals(View.GONE, below.findViewById<View>(R.id.textView_zone_badge).visibility)
        assertEquals(View.VISIBLE, below.findViewById<View>(R.id.textView_zone_below).visibility)
        assertEquals("< 95", below.text(R.id.textView_zone_range))
        assertEquals("0:45", below.text(R.id.textView_zone_time))
    }

    @Test
    fun `each row is one group for TalkBack`() {
        val rowViews = show()
        val z4 = rowViews[1]
        val below = rowViews.last()

        assertTrue(z4.isFocusable)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            (z4 as ViewGroup).getChildAt(0).importantForAccessibility
        )
        assertEquals(
            context.getString(
                R.string.workout_details_hr_zone_row_a11y, 4, 152,
                context.resources.getQuantityString(R.plurals.hr_zone_bound_a11y, 170, 170),
                com.runner.academy.util.FormatUtils.formatTimeForTTS(135_000, context),
                context.resources.getQuantityString(R.plurals.percent_a11y, 75, 75)
            ),
            z4.contentDescription.toString()
        )
        assertEquals(
            context.getString(
                R.string.workout_details_hr_zone_below_a11y,
                context.resources.getQuantityString(R.plurals.hr_zone_bound_a11y, 95, 95),
                com.runner.academy.util.FormatUtils.formatTimeForTTS(45_000, context),
                context.resources.getQuantityString(R.plurals.percent_a11y, 25, 25)
            ),
            below.contentDescription.toString()
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `in Russian the bounds are read without a dash and need no case`() {
        val rowViews = show()

        assertTrue(rowViews[1].contentDescription.toString().startsWith("Зона 4, от 152 до 170 уд/мин: "))
        assertTrue(rowViews.last().contentDescription.toString().startsWith("Ниже зоны 1, меньше 95 уд/мин: "))
    }

    @Test
    fun `showing again reuses the rows`() {
        show()
        val first = rows.getChildAt(0)

        show()

        assertEquals(6, rows.childCount)
        assertTrue(first === rows.getChildAt(0))
    }
}
