package com.runner.academy.ui.tracking

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.runner.academy.R
import com.runner.academy.data.GpsStatus
import com.runner.academy.util.ErrorHandler

/**
 * Manages the GPS status indicator UI on the workout tracking screen.
 *
 * Responsibilities:
 * - Updating GPS icon based on signal quality
 * - GPS accuracy text display
 * - GPS signal bar visualization with color coding
 */
class GpsStatusUiUpdater(
    private val context: Context,
    private val views: Views
) {

    data class Views(
        val layoutGpsStatus: View,
        val textViewGpsAccuracy: TextView,
        val viewGpsBar1: View,
        val viewGpsBar2: View,
        val viewGpsBar3: View,
        val viewGpsBar4: View,
    )

    private var lastGpsAccuracy = 0f
    private var lastGpsUpdateTime = 0L

    fun setGpsData(accuracy: Float, updateTime: Long) {
        lastGpsAccuracy = accuracy
        lastGpsUpdateTime = updateTime
    }

    fun updateStatusIcon() {
        val gpsStatus = ErrorHandler.determineGpsStatus(
            context,
            lastGpsAccuracy,
            lastGpsUpdateTime
        )
        updateGpsSignalIndicator(gpsStatus, lastGpsAccuracy)
    }

    fun updateGpsSignalIndicator(status: GpsStatus, accuracy: Float) {
        when (status) {
            GpsStatus.SEARCHING -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(1)
                views.textViewGpsAccuracy.visibility = View.VISIBLE
                views.textViewGpsAccuracy.text = context.getString(R.string.gps_accuracy_searching)
            }
            GpsStatus.WEAK -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(2)
                views.textViewGpsAccuracy.visibility = View.VISIBLE
                views.textViewGpsAccuracy.text = context.getString(R.string.gps_accuracy_value, accuracy.toInt())
            }
            GpsStatus.MEDIUM -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(3)
                views.textViewGpsAccuracy.visibility = View.VISIBLE
                views.textViewGpsAccuracy.text = context.getString(R.string.gps_accuracy_value, accuracy.toInt())
            }
            GpsStatus.STRONG, GpsStatus.FOUND -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(4)
                views.textViewGpsAccuracy.visibility = View.INVISIBLE
            }
            GpsStatus.LOST -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(1)
                views.textViewGpsAccuracy.visibility = View.VISIBLE
                views.textViewGpsAccuracy.text = context.getString(R.string.gps_accuracy_lost)
            }
            GpsStatus.DENIED -> {
                views.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(0)
                views.textViewGpsAccuracy.visibility = View.VISIBLE
                views.textViewGpsAccuracy.text = context.getString(R.string.gps_accuracy_denied)
            }
        }
        val label = gpsStatusShortLabel(status)
        views.layoutGpsStatus.contentDescription = context.getString(R.string.gps_status_a11y, label)
    }

    private fun setGpsBarsLevel(level: Int) {
        val bars = listOf(
            views.viewGpsBar1,
            views.viewGpsBar2,
            views.viewGpsBar3,
            views.viewGpsBar4
        )
        val inactiveColor = ContextCompat.getColor(context, R.color.gps_signal_inactive)
        val activeColor = when (level) {
            4 -> ContextCompat.getColor(context, R.color.gps_signal_level_4)
            3 -> ContextCompat.getColor(context, R.color.gps_signal_level_3)
            2 -> ContextCompat.getColor(context, R.color.gps_signal_level_2)
            1 -> ContextCompat.getColor(context, R.color.gps_signal_level_1)
            else -> inactiveColor
        }
        bars.forEachIndexed { index, view ->
            val background = view.background?.let { DrawableCompat.wrap(it).mutate() }
            val color = if (index < level) activeColor else inactiveColor
            background?.let {
                DrawableCompat.setTint(it, color)
                view.background = it
            }
            // Keep bars readable but not loud on the map
            view.alpha = if (index < level) 0.9f else 0.35f
        }
    }

    private fun gpsStatusShortLabel(status: GpsStatus): String = when (status) {
        GpsStatus.SEARCHING -> context.getString(R.string.gps_signal_searching)
        GpsStatus.WEAK -> context.getString(R.string.gps_signal_weak)
        GpsStatus.MEDIUM -> context.getString(R.string.gps_signal_medium)
        GpsStatus.STRONG, GpsStatus.FOUND -> context.getString(R.string.gps_signal_strong)
        GpsStatus.LOST -> context.getString(R.string.gps_signal_lost)
        GpsStatus.DENIED -> context.getString(R.string.gps_signal_denied)
    }

    fun getLastGpsAccuracy(): Float = lastGpsAccuracy
    fun getLastGpsUpdateTime(): Long = lastGpsUpdateTime
}
