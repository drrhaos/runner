package com.runner.academy.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.snackbar.Snackbar
import com.runner.academy.R
import com.runner.academy.util.UserPreferences

/** Which texts explain a settings row; the state warnings join the hint they belong to. */
object SettingsHintTexts {

    /** Without steps the detector relies on GPS alone: the hint says so after the explanation. */
    fun autoPause(hasStepSensor: Boolean, stepsForDistance: Boolean): List<Int> = buildList {
        add(R.string.settings_hint_auto_pause)
        when {
            !hasStepSensor -> add(R.string.settings_hint_auto_pause_no_sensor)
            !stepsForDistance -> add(R.string.settings_hint_auto_pause_gps_only)
        }
    }

    /** No step sensor: the setting does nothing on this phone, which is all there is to say. */
    fun steps(hasStepSensor: Boolean): List<Int> =
        listOf(if (hasStepSensor) R.string.settings_hint_steps_for_distance else R.string.settings_hint_steps_no_sensor)

    fun join(context: Context, texts: List<Int>): String = texts.joinToString("\n\n") { context.getString(it) }
}

/**
 * Explanations of settings rows, kept off the screen: touch and hold a row to read its hint in a
 * card next to it (a tap still does what it did); TalkBack reads it as the row's hint. The text is
 * asked for when shown, so state warnings are current. The card closes on a touch outside it,
 * Back and scrolling of [scroll].
 */
class HoldHints(private val scroll: View) {

    private var popup: PopupWindow? = null
    private val dismissOnScroll = ViewTreeObserver.OnScrollChangedListener { dismiss() }

    /** [a11y]: the row's own node setup (a checkable row), kept since this takes its delegate. */
    fun attach(row: View, a11y: (AccessibilityNodeInfoCompat) -> Unit = {}, text: () -> CharSequence) {
        row.setOnLongClickListener { show(it, text()); true }
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                a11y(info)
                // Read after the name and the state; the popup itself is no extra action
                info.hintText = text()
                info.removeAction(AccessibilityActionCompat.ACTION_LONG_CLICK)
                info.isLongClickable = false
            }
        })
    }

    fun dismiss() {
        popup?.dismiss()
    }

    /**
     * Below the row, or above it when there is no room below (PopupWindow flips it).
     * The card is the popup's own window: there is no parent to inflate it into.
     */
    @SuppressLint("InflateParams")
    private fun show(anchor: View, text: CharSequence) {
        dismiss()
        val content = LayoutInflater.from(anchor.context).inflate(R.layout.popup_hold_hint, null)
        content.findViewById<TextView>(R.id.text_hold_hint).text = text
        val width = anchor.rootView.width
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // A known height lets the window decide whether it fits below the row
        val window = PopupWindow(content, width, content.measuredHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener {
                if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnScrollChangedListener(dismissOnScroll)
                if (popup === this) popup = null
            }
        }
        val location = IntArray(2).also { anchor.getLocationInWindow(it) }
        window.showAsDropDown(anchor, -location[0], 0)
        scroll.viewTreeObserver.addOnScrollChangedListener(dismissOnScroll)
        popup = window
    }

    companion object {
        /** On the first visit to either settings screen: rows can be held. Not brought back by a reset. */
        fun sayOnce(root: View, prefs: UserPreferences) {
            if (prefs.settingsHoldHintShown) return
            prefs.settingsHoldHintShown = true
            Snackbar.make(root, R.string.settings_hold_hint, Snackbar.LENGTH_LONG).show()
        }
    }
}
