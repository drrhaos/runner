package com.runner.academy.ui.workout

import com.runner.academy.data.WorkoutListItem
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.RoutePreviewCodec
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class RoutePreviewStateTest {

    private val current = WorkoutDerivation.CURRENT_METRICS_VERSION
    private val preview = "1|42|S:55750000,37600000;100,100"

    private fun item(hasTrack: Boolean, routePreview: String?, metricsVersion: Int) = WorkoutListItem(
        id = 1L,
        date = Date(0L),
        distance = 5f,
        duration = 1_800_000L,
        movingDuration = 1_800_000L,
        avgPace = 6f,
        calories = null,
        notes = null,
        type = WorkoutType.EASY_RUN,
        isFavorite = false,
        hasTrack = hasTrack,
        routePreview = routePreview,
        metricsVersion = metricsVersion
    )

    @Test
    fun manualWorkout_hasNoRoute() {
        assertEquals(RoutePreviewState.NoRoute, RoutePreviewState.of(item(false, null, 0)))
        assertEquals(RoutePreviewState.NoRoute, RoutePreviewState.of(item(false, null, current)))
    }

    @Test
    fun trackNotComputedYet_isPending() {
        assertEquals(RoutePreviewState.Pending, RoutePreviewState.of(item(true, null, 0)))
    }

    @Test
    fun trackComputedBeforePreviewsExisted_isPendingUntilThePassReachesIt() {
        assertEquals(RoutePreviewState.Pending, RoutePreviewState.of(item(true, null, 1)))
    }

    @Test
    fun computedWithoutPreview_hasNoRoute() {
        assertEquals(RoutePreviewState.NoRoute, RoutePreviewState.of(item(true, null, current)))
    }

    @Test
    fun malformedPreview_hasNoRoute() {
        assertEquals(RoutePreviewState.NoRoute, RoutePreviewState.of(item(true, "1|x|junk", current)))
    }

    @Test
    fun computedPreview_isReady() {
        assertEquals(
            RoutePreviewState.Ready(RoutePreviewCodec.decode(preview)!!),
            RoutePreviewState.of(item(true, preview, current))
        )
    }

    @Test
    fun previewFromAnOlderVersion_isStillDrawn() {
        assertEquals(42, (RoutePreviewState.of(item(true, preview, 1)) as RoutePreviewState.Ready).preview.pointCount)
    }
}
