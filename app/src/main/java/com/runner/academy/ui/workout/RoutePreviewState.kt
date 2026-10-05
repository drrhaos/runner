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

        /** Rows on screen plus what scrolling brings back; an entry is about 15 KB of points. */
        private const val DECODED_CACHE_SIZE = 48

        /**
         * Decoded previews by their text: a row is bound again on every scroll back and every
         * page reload, and the text is the whole key, so it can never pick another row's route.
         */
        private val decoded = object : LinkedHashMap<String, RoutePreview>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoutePreview>?) =
                size > DECODED_CACHE_SIZE
        }

        fun of(item: WorkoutListItem): RoutePreviewState {
            if (!item.hasTrack) return NoRoute
            val text = item.routePreview
                // Rows computed before previews existed get theirs from the pass, like new ones
                ?: return if (item.metricsVersion < WorkoutDerivation.CURRENT_METRICS_VERSION) Pending else NoRoute
            return decode(text)?.let(::Ready) ?: NoRoute
        }

        private fun decode(text: String): RoutePreview? {
            synchronized(decoded) { decoded[text]?.let { return it } }
            val preview = RoutePreviewCodec.decode(text) ?: return null
            synchronized(decoded) { decoded[text] = preview }
            return preview
        }
    }
}
