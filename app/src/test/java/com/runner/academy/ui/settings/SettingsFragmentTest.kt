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
import org.junit.Assert.assertEquals
import org.junit.Before
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
}
