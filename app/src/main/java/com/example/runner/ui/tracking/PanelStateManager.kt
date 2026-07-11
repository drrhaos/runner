package com.example.runner.ui.tracking

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.GestureDetector.SimpleOnGestureListener

/**
 * Manages the collapsible tracking panel on the workout tracking screen.
 *
 * Responsibilities:
 * - Expand/collapse animations for the panel
 * - Touch interaction handling (swipe gestures, double-tap)
 * - Panel visibility state management
 */
class PanelStateManager(
    private val views: Views
) {

    data class Views(
        val layoutWorkoutPanel: View,
        val layoutExpandedInfo: View,
        val textViewWorkoutTime: View,
        val textViewWorkoutDistance: View,
        val textViewWorkoutTimeLabel: View,
        val textViewWorkoutDistanceLabel: View,
    )

    private var isPanelExpanded = false
    private var lastTapTime = 0L
    private lateinit var gestureDetector: GestureDetector

    var expanded: Boolean
        get() = isPanelExpanded
        set(value) {
            isPanelExpanded = value
        }

    fun setupGesture(context: Context) {
        gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                e1?.let {
                    val diffY = e2.y - it.y
                    val diffX = e2.x - it.x

                    // Swipe up to expand
                    if (Math.abs(diffY) > Math.abs(diffX) && diffY < 0 && Math.abs(diffY) > 100) {
                        if (!isPanelExpanded) {
                            expand()
                            return true
                        }
                    }
                    // Swipe down to collapse
                    else if (Math.abs(diffY) > Math.abs(diffX) && diffY > 0 && Math.abs(diffY) > 100) {
                        if (isPanelExpanded) {
                            collapse()
                            return true
                        }
                    }
                    return true
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return handleDoubleTap()
            }
        })

        if (views.layoutWorkoutPanel is TouchInterceptingLinearLayout) {
            (views.layoutWorkoutPanel as TouchInterceptingLinearLayout).setGestureDetector(gestureDetector)
        }
    }

    fun expand() {
        isPanelExpanded = true
        views.layoutExpandedInfo.visibility = View.VISIBLE
        views.textViewWorkoutTime.visibility = View.GONE
        views.textViewWorkoutDistance.visibility = View.GONE
        views.textViewWorkoutTimeLabel.visibility = View.GONE
        views.textViewWorkoutDistanceLabel.visibility = View.GONE

        // Animate expanded info appearance
        views.layoutExpandedInfo.alpha = 0f
        views.layoutExpandedInfo.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    fun collapse() {
        isPanelExpanded = false

        // Animate expanded info disappearance
        views.layoutExpandedInfo.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                views.layoutExpandedInfo.visibility = View.GONE
                views.textViewWorkoutTime.visibility = View.VISIBLE
                views.textViewWorkoutDistance.visibility = View.VISIBLE
                views.textViewWorkoutTimeLabel.visibility = View.VISIBLE
                views.textViewWorkoutDistanceLabel.visibility = View.VISIBLE
            }
            .start()
    }

    fun toggle() {
        if (isPanelExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun handleDoubleTap(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < 300) {
            toggle()
            return true
        }
        lastTapTime = currentTime
        return false
    }
}
