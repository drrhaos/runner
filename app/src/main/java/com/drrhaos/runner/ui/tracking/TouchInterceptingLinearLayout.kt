package com.drrhaos.runner.ui.tracking

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout

class TouchInterceptingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var gestureDetector: GestureDetector? = null

    fun setGestureDetector(detector: GestureDetector) {
        this.gestureDetector = detector
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { gestureDetector?.onTouchEvent(it) }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureDetector?.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }
}
