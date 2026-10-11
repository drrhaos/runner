package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidBleAdaptersTest {

    private val app = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())

    @Test
    fun `adapter switches are delivered until stop`() {
        val watch = AndroidAdapterWatch(app, handler)
        val states = mutableListOf<Int>()
        watch.start { states += it }

        app.sendBroadcast(stateChanged(BluetoothAdapter.STATE_TURNING_OFF))
        app.sendBroadcast(stateChanged(BluetoothAdapter.STATE_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        watch.stop()
        app.sendBroadcast(stateChanged(BluetoothAdapter.STATE_ON))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF), states)
    }

    @Test
    fun `the handler scheduler runs, delays and cancels by token`() {
        val scheduler = HandlerScheduler(handler)
        val ran = mutableListOf<String>()
        val keep = Any()
        val drop = Any()
        scheduler.post { ran += "now" }
        scheduler.postDelayed(keep, 1_000) { ran += "keep" }
        scheduler.postDelayed(drop, 500) { ran += "drop" }
        scheduler.cancel(drop)

        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertEquals(listOf("now"), ran)
        looper.idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(listOf("now", "keep"), ran)
    }

    private fun stateChanged(state: Int) =
        Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state)
}
