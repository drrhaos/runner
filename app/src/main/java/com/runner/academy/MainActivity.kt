package com.runner.academy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.runner.academy.data.BackfillState
import com.runner.academy.databinding.ActivityMainBinding
import com.runner.academy.ui.records.RecordsAnnouncement
import com.runner.academy.ui.records.RecordsUpdatedSnackbar
import com.runner.academy.service.ActiveWorkoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.runner.academy.util.PowerSaveCheck

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var powerSaveWarning: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_tracking,
                R.id.nav_workouts,
                R.id.nav_my_plan,
                R.id.nav_plans,
                R.id.nav_statistics,
                R.id.nav_records,
                R.id.nav_settings
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        handleOpenTrackingIntent(intent)
        announceRecordsUpdatedAfterImport()
    }

    /**
     * A backup import is computed in the background: when its pass ends, "Records updated: N"
     * is shown wherever the user is. Each Done is acknowledged once shown (or found nothing to
     * announce), so a recreated activity does not show it again.
     */
    private fun announceRecordsUpdatedAfterImport() {
        val backfill = appContainer().metricsBackfill
        val activeWorkout = ActiveWorkoutStore(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                backfill.progress.collect { state ->
                    if (state !is BackfillState.Done) return@collect
                    RecordsAnnouncement.afterBackfill(state)?.let { count ->
                        val navController = findNavController(R.id.nav_host_fragment_content_main)
                        val destination = navController.currentDestination?.id
                        val workoutActive = destination == R.id.nav_tracking &&
                            withContext(Dispatchers.IO) { activeWorkout.isWorkoutActive() }
                        RecordsUpdatedSnackbar.show(
                            view = binding.appBarMain.root,
                            navController = navController,
                            count = count,
                            offerOpen = RecordsAnnouncement.offersOpen(destination, workoutActive)
                        )
                    }
                    backfill.acknowledge(state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        showPowerSaveWarningIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTrackingIntent(intent)
    }

    private fun handleOpenTrackingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TRACKING, false) != true) return
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id == R.id.nav_tracking) return
        try {
            navController.navigate(R.id.nav_tracking)
        } catch (e: Exception) {
            Log.w(TAG, "Could not navigate to tracking: ${e.message}")
        }
    }

    /**
     * Battery saver can stop or throttle GPS with the screen off, which the location
     * foreground service does not prevent. Unlike the one-time battery optimization hint,
     * this warns on every launch: the mode is toggled often and a run is exactly when it hurts.
     * Checked in onStart so a warm start and a restore after process death are covered too.
     */
    private fun showPowerSaveWarningIfNeeded() {
        if (powerSaveWarning?.isShowing == true) return
        if (!PowerSaveCheck.affectsScreenOffTracking(PowerSaveCheck.read(this))) return
        powerSaveWarning = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.power_save_warning_title)
            .setMessage(R.string.power_save_warning_message)
            .setPositiveButton(R.string.power_save_warning_open_settings) { _, _ -> openBatterySaverSettings() }
            .setNegativeButton(R.string.power_save_warning_dismiss, null)
            .show()
    }

    private fun openBatterySaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Battery saver settings unavailable: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Log.w(TAG, "Settings unavailable: ${e2.message}")
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_OPEN_TRACKING = "com.runner.academy.EXTRA_OPEN_TRACKING"
    }
}
