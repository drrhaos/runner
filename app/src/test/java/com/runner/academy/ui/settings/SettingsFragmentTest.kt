package com.runner.academy.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.Navigation
import androidx.navigation.Navigator
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.R
import com.runner.academy.RunnerApplication
import com.runner.academy.appContainer
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.PairedSensorStore
import com.runner.academy.sensor.SensorType
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertNull
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RunnerApplication::class)
class SettingsFragmentTest {

    /** Stands in for the fragment navigator: records the destination, creates nothing. */
    @Navigator.Name("fragment")
    private class RecordingNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun navigate(destination: NavDestination, args: Bundle?, navOptions: NavOptions?, navigatorExtras: Extras?) =
            destination
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    private fun launch(): Pair<SettingsFragment, NavHostController> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().start().resume().get()
        activity.setTheme(R.style.Theme_Runner)
        val fragment = SettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val navController = NavHostController(activity).apply {
            navigatorProvider.addNavigator(RecordingNavigator())
            setGraph(R.navigation.mobile_navigation)
            navigate(R.id.nav_settings)
        }
        Navigation.setViewNavController(fragment.requireView(), navController)
        shadowOf(Looper.getMainLooper()).idle()
        return fragment to navController
    }

    @Test
    fun `the devices row says no monitor is added`() {
        val (fragment, _) = launch()
        val view = fragment.requireView()

        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.row_devices).visibility)
        assertEquals(
            context.getString(R.string.settings_devices_none),
            view.findViewById<TextView>(R.id.textView_devices_summary).text.toString()
        )
    }

    @Test
    fun `the devices row names the paired monitor`() {
        context.appContainer().pairedSensorStore.put(
            PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)
        )
        val (fragment, _) = launch()

        assertEquals(
            context.getString(R.string.settings_devices_summary, "HR Strap 10"),
            fragment.requireView().findViewById<TextView>(R.id.textView_devices_summary).text.toString()
        )
    }

    @Test
    fun `tapping the devices row opens Devices`() {
        val (fragment, navController) = launch()

        fragment.requireView().findViewById<View>(R.id.row_devices).performClick()

        assertEquals(R.id.nav_devices, navController.currentDestination?.id)
    }

    @Test
    fun `a double tap opens Devices once`() {
        val (fragment, navController) = launch()
        val row = fragment.requireView().findViewById<View>(R.id.row_devices)

        row.performClick()
        row.performClick()
        navController.popBackStack()

        assertEquals(R.id.nav_settings, navController.currentDestination?.id)
    }

    // --- Voice coaching ---

    @Test
    fun `the voice row says off by default and reads as one group`() {
        val view = launch().first.requireView()
        val row = view.findViewById<View>(R.id.row_voice_settings)

        assertEquals(context.getString(R.string.settings_voice_summary_off), view.text(R.id.textView_voice_summary))
        assertEquals(
            context.getString(R.string.settings_label_voice) + ", " + context.getString(R.string.settings_voice_summary_off),
            row.contentDescription.toString()
        )
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        row.onInitializeAccessibilityNodeInfo(info)
        assertEquals(
            context.getString(R.string.settings_voice_open_a11y),
            info.actionList.first { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }.label.toString()
        )
    }

    @Test
    fun `the voice row names the free run summary when on`() {
        prefs.setVoiceSettings(com.runner.academy.voice.VoiceSettings.DEFAULT.copy(enabled = true))
        val view = launch().first.requireView()

        assertEquals(
            context.getString(R.string.settings_voice_summary_on, context.getString(R.string.voice_every_short_km_1)),
            view.text(R.id.textView_voice_summary)
        )
    }

    @Test
    fun `tapping the voice row opens the voice screen once`() {
        val (fragment, navController) = launch()
        val row = fragment.requireView().findViewById<View>(R.id.row_voice_settings)

        row.performClick()
        assertEquals(R.id.nav_voice_settings, navController.currentDestination?.id)
        row.performClick()
        navController.popBackStack()

        assertEquals(R.id.nav_settings, navController.currentDestination?.id)
    }

    @Test
    fun `back from the voice screen the row shows what was changed there`() {
        val (fragment, _) = launch()
        prefs.setVoiceSettings(com.runner.academy.voice.VoiceSettings.DEFAULT.copy(enabled = true))

        fragment.onPause()
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            context.getString(R.string.settings_voice_summary_on, context.getString(R.string.voice_every_short_km_1)),
            fragment.requireView().text(R.id.textView_voice_summary)
        )
    }

    // --- Maximum heart rate ---

    private val prefs get() = context.appContainer().userPreferences

    /** Turned [years] some weeks ago: the age is [years] whatever today is. */
    private fun bornYearsAgo(years: Long) {
        prefs.userBirthDate = LocalDate.now().minusYears(years).minusDays(20)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun `without a birth date or a manual value the zones are said to be off`() {
        val view = launch().first.requireView()

        assertEquals(context.getString(R.string.settings_hr_max_none), view.text(R.id.textView_hr_max_value))
        assertEquals(context.getString(R.string.settings_hr_max_source_none), view.text(R.id.textView_hr_max_source))
        assertEquals(
            context.getString(R.string.settings_hr_max_none_a11y),
            view.findViewById<View>(R.id.row_hr_max).contentDescription.toString()
        )
    }

    @Test
    fun `with a birth date the row shows 220 minus the age`() {
        bornYearsAgo(40)
        val view = launch().first.requireView()

        assertEquals("180", view.text(R.id.textView_hr_max_value))
        assertEquals(context.getString(R.string.settings_hr_max_source_auto, 40), view.text(R.id.textView_hr_max_source))
        assertEquals(
            context.getString(
                R.string.settings_hr_max_a11y,
                context.resources.getQuantityString(R.plurals.heart_rate_beats_per_minute, 180, 180),
                context.getString(R.string.hr_max_source_auto_short)
            ),
            view.findViewById<View>(R.id.row_hr_max).contentDescription.toString()
        )
    }

    @Test
    fun `a manual value is said to be set by hand`() {
        bornYearsAgo(40)
        prefs.hrMaxManual = 192
        val view = launch().first.requireView()

        assertEquals("192", view.text(R.id.textView_hr_max_value))
        assertEquals(context.getString(R.string.settings_hr_max_source_manual), view.text(R.id.textView_hr_max_source))
    }

    @Test
    fun `without a birth date the dialog starts at 190, shows its zones and saves it`() {
        val (fragment, _) = launch()

        fragment.requireView().findViewById<View>(R.id.row_hr_max).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = latestDialog()

        assertEquals(190, dialog.findViewById<NumberPicker>(R.id.numberPicker_hr_max)!!.value)
        assertEquals(
            context.getString(R.string.settings_dialog_hr_max_no_birth),
            dialog.findViewById<TextView>(R.id.textView_hr_max_explain)!!.text.toString()
        )
        assertEquals(
            "Z5 171–190",
            dialog.findViewById<TextView>(R.id.textView_hr_max_zones_preview)!!.text.lines().first()
        )
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(190, prefs.hrMaxManual)
        assertEquals("190", fragment.requireView().text(R.id.textView_hr_max_value))
    }

    @Test
    fun `saving an untouched age value keeps it by age`() {
        bornYearsAgo(40)
        val (fragment, _) = launch()

        fragment.requireView().findViewById<View>(R.id.row_hr_max).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = latestDialog()
        assertEquals(
            context.getString(R.string.settings_dialog_hr_max_auto_formula, 40, 180),
            dialog.findViewById<TextView>(R.id.textView_hr_max_explain)!!.text.toString()
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, prefs.hrMaxManual)
        // Nothing was saved: no "saved" toast
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `a manual value without a birth date can turn the zones off`() {
        prefs.hrMaxManual = 192
        val (fragment, _) = launch()

        fragment.requireView().findViewById<View>(R.id.row_hr_max).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val zonesOff = latestDialog().getButton(AlertDialog.BUTTON_NEUTRAL)
        assertTrue(zonesOff.isShown)
        assertEquals(context.getString(R.string.settings_dialog_hr_max_zones_off), zonesOff.text.toString())
        zonesOff.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, prefs.hrMaxManual)
        assertEquals(context.getString(R.string.settings_hr_max_none), fragment.requireView().text(R.id.textView_hr_max_value))
        assertEquals(context.getString(R.string.settings_hr_max_zones_off_done), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an age out of 10 to 100 is explained as such, not as no birth date`() {
        bornYearsAgo(5)
        val (fragment, _) = launch()

        fragment.requireView().findViewById<View>(R.id.row_hr_max).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            context.getString(R.string.settings_dialog_hr_max_age_out_of_range),
            latestDialog().findViewById<TextView>(R.id.textView_hr_max_explain)!!.text.toString()
        )
    }

    @Test
    fun `By age drops a manual value`() {
        bornYearsAgo(40)
        prefs.hrMaxManual = 192
        val (fragment, _) = launch()

        fragment.requireView().findViewById<View>(R.id.row_hr_max).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val byAge = latestDialog().getButton(AlertDialog.BUTTON_NEUTRAL)
        assertTrue(byAge.isShown)
        byAge.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, prefs.hrMaxManual)
        assertEquals("180", fragment.requireView().text(R.id.textView_hr_max_value))
    }

    @Test
    fun `a new birth date recounts the row`() {
        val (fragment, _) = launch()
        val viewModel = androidx.lifecycle.ViewModelProvider(fragment)[SettingsViewModel::class.java]

        viewModel.updateUserBirthDate(
            LocalDate.now().minusYears(30).minusDays(20).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("190", fragment.requireView().text(R.id.textView_hr_max_value))
    }
}
