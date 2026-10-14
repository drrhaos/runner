package com.runner.academy.ui.tracking

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.runner.academy.R
import com.runner.academy.data.LivePace
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.SensorLink
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The current-pace tile on the tracking screen: the session's live pace, one TalkBack stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetricsDisplayManagerPaceTest {

    private val app = RuntimeEnvironment.getApplication()
    private val context = ContextThemeWrapper(app, R.style.Theme_Runner)
    private val database = WorkoutDatabase.getInMemoryDatabase(app)
    private val viewModel = WorkoutTrackingViewModel(
        WorkoutRepository(database), app as Application, MutableStateFlow(emptyMap()), FakeHeartRateSensor(SensorLink.IDLE)
    )

    private val pace = TextView(context)
    private val tile = FrameLayout(context)
    private val manager = MetricsDisplayManager(views(), viewModel)

    @After
    fun tearDown() = database.close()

    private fun views(): MetricsDisplayManager.Views {
        fun text() = TextView(context)
        fun caption() = MetricsDisplayManager.TimerCaption(FrameLayout(context), text(), text())
        return MetricsDisplayManager.Views(
            textViewWorkoutTime = text(),
            textViewWorkoutDistance = text(),
            textViewWorkoutTimeExpanded = text(),
            textViewWorkoutDistanceExpanded = text(),
            textViewWorkoutPace = text(),
            textViewWorkoutHeartRate = text(),
            textViewAvgSpeed = text(),
            textViewCurrentPace = pace,
            layoutCurrentPaceTile = tile,
            textViewCaloriesBurned = text(),
            spinnerWorkoutType = Spinner(context),
            buttonStart = ImageButton(context),
            buttonPause = ImageButton(context),
            buttonStop = ImageButton(context),
            timerCaption = caption(),
            timerCaptionExpanded = caption(),
            textViewElapsedTimeExpanded = text(),
            layoutHeartRateTile = View(context),
            imageViewHrLink = ImageView(context),
            imageViewHrLinkCompact = ImageView(context),
            textViewHrZoneBadge = text()
        )
    }

    /** The instant pace the tile showed before: it must not leak back in. */
    private fun session(livePace: LivePace?) =
        WorkoutSession(isTracking = true, currentPace = 3.5f, livePace = livePace)

    @Test
    fun `the tile shows the live pace, not the instant one, and says it`() {
        manager.updateMetrics(session(LivePace(5f, approximate = false)))

        assertEquals("5:00", pace.text.toString())
        assertEquals("Current pace: 5 minutes per kilometer", tile.contentDescription)
        assertTrue(ViewCompat.isScreenReaderFocusable(tile))
    }

    @Test
    fun `a steps pace has the sign, no pace the dashes`() {
        manager.updateMetrics(session(LivePace(5f, approximate = true)))
        assertEquals("≈ 5:00", pace.text.toString())
        assertEquals("Current pace, approximate from steps: 5 minutes per kilometer", tile.contentDescription)

        manager.updateMetrics(session(null))
        assertEquals("--:--", pace.text.toString())
        assertEquals("Current pace: no data", tile.contentDescription)
    }

    @Test
    fun `the description is set only when the text changes`() {
        manager.updateMetrics(session(LivePace(5f, approximate = false)))
        tile.contentDescription = "marker"

        // Another second, the same digits
        manager.updateMetrics(session(LivePace(5.001f, approximate = false)))
        assertEquals("marker", tile.contentDescription)
    }
}
