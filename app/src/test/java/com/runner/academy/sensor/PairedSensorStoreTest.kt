package com.runner.academy.sensor

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairedSensorStoreTest {

    private lateinit var context: Context

    private val strap = PairedSensor(
        type = SensorType.HEART_RATE,
        address = "AA:BB:CC:DD:EE:FF",
        name = "HR Strap 10 1234ABCD",
        pairedAt = 1_700_000_000_000L,
        lastSeenAt = 1_700_000_100_000L,
        batteryPercent = 80
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun `empty store has nothing`() {
        val store = PairedSensorStore(context)
        assertNull(store.get(SensorType.HEART_RATE))
        assertEquals(emptyMap<SensorType, PairedSensor>(), store.changes.value)
    }

    @Test
    fun `put is read back and survives a new instance`() {
        PairedSensorStore(context).put(strap)
        val again = PairedSensorStore(context)
        assertEquals(strap, again.get(SensorType.HEART_RATE))
        assertEquals(mapOf(SensorType.HEART_RATE to strap), again.changes.value)
    }

    @Test
    fun `optional fields may be missing`() {
        val bare = strap.copy(name = null, lastSeenAt = null, batteryPercent = null)
        PairedSensorStore(context).put(bare)
        assertEquals(bare, PairedSensorStore(context).get(SensorType.HEART_RATE))
    }

    @Test
    fun `put replaces the device of the same type`() {
        val store = PairedSensorStore(context)
        store.put(strap)
        val chestStrap = strap.copy(address = "11:22:33:44:55:66", name = "Chest Strap", batteryPercent = null)
        store.put(chestStrap)
        assertEquals(chestStrap, store.get(SensorType.HEART_RATE))
        assertEquals(mapOf(SensorType.HEART_RATE to chestStrap), store.changes.value)
        assertEquals(chestStrap, PairedSensorStore(context).get(SensorType.HEART_RATE))
    }

    @Test
    fun `remove forgets the device`() {
        val store = PairedSensorStore(context)
        store.put(strap)
        store.remove(SensorType.HEART_RATE)
        assertNull(store.get(SensorType.HEART_RATE))
        assertEquals(emptyMap<SensorType, PairedSensor>(), store.changes.value)
        assertNull(PairedSensorStore(context).get(SensorType.HEART_RATE))
    }

    @Test
    fun `changes follow every write`() {
        val store = PairedSensorStore(context)
        val seen = mutableListOf(store.changes.value)
        store.put(strap)
        seen += store.changes.value
        store.put(strap.copy(batteryPercent = 75))
        seen += store.changes.value
        store.remove(SensorType.HEART_RATE)
        seen += store.changes.value
        assertEquals(
            listOf(null, 80, 75, null),
            seen.map { it[SensorType.HEART_RATE]?.batteryPercent }
        )
    }

    @Test
    fun `concurrent writes leave the flow and the file agreeing`() {
        repeat(20) { round ->
            val store = PairedSensorStore(context)
            val start = CountDownLatch(1)
            val threads = (0 until 8).map { t ->
                thread {
                    start.await()
                    repeat(50) { i ->
                        if ((t + i) % 5 == 0) store.remove(SensorType.HEART_RATE)
                        else store.put(strap.copy(batteryPercent = t * 100 + i))
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals("round $round", PairedSensorStore(context).changes.value, store.changes.value)
        }
    }

    @Test
    fun `a corrupt or unknown entry is ignored`() {
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("HEART_RATE", "{not json")
            putString("RUNNING_SPEED", """{"address":"AA","pairedAt":1}""")
        }
        val store = PairedSensorStore(context)
        assertNull(store.get(SensorType.HEART_RATE))
        assertEquals(emptyMap<SensorType, PairedSensor>(), store.changes.value)
    }

    @Test
    fun `an entry without an address is ignored`() {
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("HEART_RATE", """{"name":"x","pairedAt":1}""")
        }
        assertNull(PairedSensorStore(context).get(SensorType.HEART_RATE))
    }
}
