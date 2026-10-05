package com.runner.academy.ui.workout

import com.runner.academy.data.WorkoutListItem
import com.runner.academy.util.RoutePreview
import com.runner.academy.util.RoutePreviewCodec
import com.runner.academy.util.WorkoutDerivation

/** What a list or route-picker row shows in its preview slot. */
sealed class RoutePreviewState {

    /** "No route": a workout without a track, or one whose track is broken or too short. */
    data object NoRoute : RoutePreviewState()

    /** The track is there but the background pass has not computed its preview yet. */
    data object Pending : RoutePreviewState()

    data class Ready(val preview: RoutePreview) : RoutePreviewState()

    companion object {
        fun of(item: WorkoutListItem): RoutePreviewState {
            if (!item.hasTrack) return NoRoute
            if (item.routePreview == null) {
                // Rows computed before previews existed get theirs from the pass, like new ones
                return if (item.metricsVersion < WorkoutDerivation.CURRENT_METRICS_VERSION) Pending else NoRoute
            }
            return RoutePreviewCodec.decode(item.routePreview)?.let(::Ready) ?: NoRoute
        }
    }
}
