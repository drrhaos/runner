package com.runner.academy.ui.tracking

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.runner.academy.R
import com.runner.academy.RunnerApplication
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RunnerApplication::class, qualifiers = "en")
class WorkoutModePickerTest {

    private val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().start().resume().get()
        .apply { setTheme(R.style.Theme_Runner) }
    private val binding = FragmentWorkoutTrackingBinding.inflate(activity.layoutInflater)
    private val picker = WorkoutModePicker(activity, binding)

    private val intervals = TrackingWorkoutMode.Template(
        WorkoutTemplateWithSegments(
            WorkoutTemplate(id = 7, name = "Intervals 6×400"),
            (1..6).map {
                WorkoutTemplateSegment(
                    templateId = 7, sortOrder = it, kind = SegmentKind.WORK, title = "",
                    goalType = SegmentGoalType.DISTANCE, distanceMeters = 400f, targetPaceMinPerKm = 4f
                )
            }
        )
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun rows(): List<View> {
        val dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertTrue(dialog.isShowing)
        val groups = dialog.findViewById<ViewGroup>(R.id.layout_mode_groups)!!
        return (0 until groups.childCount).map { groups.getChildAt(it) }.filter { it.findViewById<View>(R.id.text_mode_name) != null }
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `the button names the mode and says it can be changed`() {
        picker.showSelected(intervals)

        assertEquals("Intervals 6×400", binding.textWorkoutModeName.text.toString())
        assertEquals("Workout mode: Intervals 6×400, change", binding.buttonWorkoutMode.contentDescription.toString())
    }

    @Test
    fun `the sheet ticks the chosen mode and describes each`() {
        picker.open(listOf(TrackingWorkoutMode.EasyRun, intervals), selected = intervals, planName = null) {}
        idle()

        val (easy, template) = rows()
        assertEquals("Easy run", easy.text(R.id.text_mode_name))
        assertEquals("No segments", easy.text(R.id.text_mode_subtitle))
        assertFalse(easy.isSelected)
        assertEquals("6 × 400 m · ≈ 10 min", template.text(R.id.text_mode_subtitle))
        assertTrue(template.isSelected)
        assertEquals(View.VISIBLE, template.findViewById<View>(R.id.image_mode_tick).visibility)
    }

    @Test
    fun `a tap picks the mode and closes the sheet`() {
        var picked: TrackingWorkoutMode? = null
        picker.open(listOf(TrackingWorkoutMode.EasyRun, intervals), selected = TrackingWorkoutMode.EasyRun, planName = null) { picked = it }
        idle()

        rows()[1].performClick()
        idle()

        assertEquals(intervals, picked)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }
}
