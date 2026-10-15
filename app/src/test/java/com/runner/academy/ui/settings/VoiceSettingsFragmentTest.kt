package com.runner.academy.ui.settings

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.R
import com.runner.academy.RunnerApplication
import com.runner.academy.appContainer
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.PairedSensorStore
import com.runner.academy.sensor.SensorType
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RunnerApplication::class)
class VoiceSettingsFragmentTest {

    private lateinit var context: Context
    private val prefs get() = context.appContainer().userPreferences
    private val on = VoiceSettings.DEFAULT.copy(enabled = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    private fun launch(): View {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().start().resume().get()
        activity.setTheme(R.style.Theme_Runner)
        val fragment = VoiceSettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        idle()
        return fragment.requireView()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun View.row(id: Int): View = findViewById(id)
    private fun View.value(rowId: Int) = row(rowId).let { it.findViewById<TextView>(R.id.textView_choice_value) ?: it.findViewById(R.id.textView_metrics_value) }.text.toString()
    private fun View.click(id: Int) {
        row(id).performClick()
        idle()
    }

    private fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as AlertDialog?

    private fun AlertDialog.clickItem(position: Int) {
        listView.performItemClick(listView.adapter.getView(position, null, listView), position, position.toLong())
        idle()
    }

    private fun stored() = prefs.voiceSettings()

    private fun checked(view: View): Boolean {
        val info = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.isCheckable)
        return info.isChecked
    }

    @Test
    fun `by default the voice is off and the profiles are greyed out, the tolerance is not`() {
        val view = launch()

        assertFalse(checked(view.row(R.id.row_voice_enabled)))
        assertEquals(context.getString(R.string.voice_every_km_1), view.value(R.id.row_free_summary))
        assertEquals(context.getString(R.string.voice_every_off), view.value(R.id.row_intervals_summary))
        for (id in listOf(
            R.id.row_free_summary, R.id.row_free_metrics, R.id.row_intervals_summary,
            R.id.row_intervals_metrics, R.id.row_intervals_segment_change, R.id.row_intervals_target_check
        )) {
            assertFalse("row $id", view.row(id).isEnabled)
            assertEquals("row $id", 0.38f, view.row(id).alpha, 0.001f)
        }
        assertTrue(view.row(R.id.row_pace_tolerance).isEnabled)
        assertEquals(1f, view.row(R.id.row_pace_tolerance).alpha, 0.001f)
        assertEquals(context.getString(R.string.voice_pace_tolerance_value, 10), view.value(R.id.row_pace_tolerance))
        assertEquals(View.GONE, view.findViewById<View>(R.id.textView_units_note).visibility)
    }

    @Test
    fun `the master switch row turns the voice on at once`() {
        val view = launch()

        view.click(R.id.row_voice_enabled)

        assertTrue(stored().enabled)
        assertTrue(checked(view.row(R.id.row_voice_enabled)))
        assertTrue(view.row(R.id.row_free_summary).isEnabled)
        assertTrue(view.row(R.id.row_free_metrics).isEnabled)
        assertTrue(view.row(R.id.row_intervals_segment_change).isEnabled)
        // The interval summary is off: what it would say stays greyed out
        assertFalse(view.row(R.id.row_intervals_metrics).isEnabled)
    }

    @Test
    fun `a greyed out row opens nothing`() {
        val view = launch()

        view.click(R.id.row_free_summary)

        assertNull(latestDialog())
    }

    @Test
    fun `the summary dialog lists every interval and saves the choice`() {
        prefs.setVoiceSettings(on)
        val view = launch()

        view.click(R.id.row_intervals_summary)
        val dialog = latestDialog()!!
        assertEquals(SummaryEvery.entries.size, dialog.listView.adapter.count)
        dialog.clickItem(SummaryEvery.entries.indexOf(SummaryEvery.MIN_5))

        assertFalse(dialog.isShowing)
        assertEquals(SummaryEvery.MIN_5, stored().intervals.summary)
        assertEquals(context.getString(R.string.voice_every_min_5), view.value(R.id.row_intervals_summary))
        assertTrue(view.row(R.id.row_intervals_metrics).isEnabled)
        assertEquals(
            context.getString(R.string.voice_summary_label) + ", " + context.getString(R.string.voice_every_min_5),
            view.row(R.id.row_intervals_summary).contentDescription.toString()
        )
    }

    @Test
    fun `the metrics row lists the chosen metrics in phrase order`() {
        prefs.setVoiceSettings(on)
        val view = launch()

        assertEquals("distance, time, average pace", view.value(R.id.row_free_metrics))
    }

    @Test
    fun `Done is off with nothing ticked and saves the ticked metrics`() {
        prefs.setVoiceSettings(on)
        val view = launch()

        view.click(R.id.row_free_metrics)
        val dialog = latestDialog()!!
        val done = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(context.getString(R.string.voice_metrics_done), done.text.toString())
        assertTrue(done.isEnabled)
        // Untick distance, time and average pace: nothing is left
        (0..2).forEach { dialog.clickItem(it) }
        assertFalse(done.isEnabled)
        dialog.clickItem(SummaryMetric.entries.indexOf(SummaryMetric.SPLIT_PACE))
        assertTrue(done.isEnabled)
        done.performClick()
        idle()

        assertEquals(setOf(SummaryMetric.SPLIT_PACE), stored().free.metrics)
        assertEquals("pace since last summary", view.value(R.id.row_free_metrics))
    }

    @Test
    fun `heart rate without a monitor is said to be skipped`() {
        prefs.setVoiceSettings(on.copy(free = on.free.copy(metrics = setOf(SummaryMetric.CURRENT_HR))))
        val view = launch()

        val note = view.row(R.id.row_free_metrics).findViewById<View>(R.id.textView_hr_note)
        assertEquals(View.VISIBLE, note.visibility)
        assertEquals(
            View.GONE,
            view.row(R.id.row_intervals_metrics).findViewById<View>(R.id.textView_hr_note).visibility
        )
    }

    @Test
    fun `with a paired monitor heart rate is not said to be skipped`() {
        context.appContainer().pairedSensorStore.put(
            PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)
        )
        prefs.setVoiceSettings(on.copy(free = on.free.copy(metrics = setOf(SummaryMetric.CURRENT_HR))))
        val view = launch()

        assertEquals(
            View.GONE,
            view.row(R.id.row_free_metrics).findViewById<View>(R.id.textView_hr_note).visibility
        )
    }

    @Test
    fun `the interval switches are written through and read as checkable rows`() {
        prefs.setVoiceSettings(on)
        val view = launch()
        assertTrue(checked(view.row(R.id.row_intervals_segment_change)))

        view.click(R.id.row_intervals_segment_change)
        view.click(R.id.row_intervals_target_check)

        assertFalse(stored().intervals.segmentChange)
        assertFalse(stored().intervals.targetCheck)
        assertFalse(checked(view.row(R.id.row_intervals_segment_change)))
        assertEquals(
            context.getString(R.string.voice_segment_change_label),
            view.row(R.id.row_intervals_segment_change).findViewById<TextView>(R.id.textView_switch_label).text.toString()
        )
    }

    @Test
    fun `the tolerance can be changed with the voice off`() {
        val view = launch()

        view.click(R.id.row_pace_tolerance)
        val dialog = latestDialog()!!
        assertEquals(VoiceSettings.PACE_TOLERANCES_SEC.size, dialog.listView.adapter.count)
        dialog.clickItem(VoiceSettings.PACE_TOLERANCES_SEC.indexOf(15))

        assertEquals(15, stored().paceToleranceSec)
        assertFalse(stored().enabled)
        assertEquals(context.getString(R.string.voice_pace_tolerance_value, 15), view.value(R.id.row_pace_tolerance))
    }

    @Test
    fun `with miles a footnote says the voice speaks kilometres`() {
        prefs.unitSystem = "imperial"
        val view = launch()

        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.textView_units_note).visibility)
    }

    @Test
    fun `section titles are headings`() {
        val view = launch()

        for (id in listOf(R.id.textView_section_free, R.id.textView_section_intervals, R.id.textView_section_target)) {
            assertTrue("heading $id", androidx.core.view.ViewCompat.isAccessibilityHeading(view.findViewById(id)))
        }
    }
}
