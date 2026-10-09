package com.runner.academy.gpsreplay

import com.runner.academy.data.ElevationSource
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Elevation gain on the bench (release acceptance, `product.md`): a hill of known height within
 * 15 %, a flat 5 km within 15 m by GPS, nothing counted across a dropped stretch or a gap.
 * The same profiles by the barometer (`baro_m`: the truth off by the weather, drifting 8 m/h,
 * σ 0.3 m), and GPS whenever the barometer is missing or covers too few fixes.
 * Each scenario runs over [SEEDS] noise realisations through the whole save path (session →
 * saved track → derivation), so the tuned [com.runner.academy.util.ElevationConfig] is not
 * fitted to one lucky seed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevationReplayTest {

    /** What the save path stores for the replayed run. */
    private fun derived(run: SyntheticRun, settings: ReplaySettings = ReplaySettings()): Derived {
        val saved = SessionReplay.run(run, settings).savedWorkout
        return WorkoutDerivation.derive(DerivationInput(saved.trackData, saved.type, saved.duration))
    }

    /** Out and back along a straight 3 km street: no corners to cut. */
    private val street = listOf(0.0 to 0.0, 3_000.0 to 0.0, 0.0 to 0.0)

    /** Three hills of [height] m over the 6 km, each 2 km long (up 1 km, down 1 km: 4 %). */
    private fun threeHills(height: Double): (Double) -> Double = { d ->
        val phase = (d % 2_000.0) / 2_000.0
        150.0 + height * (1 - cos(2 * PI * phase)) / 2
    }

    @Test
    fun `three hills of 40 m with GPS noise gain 120 m within 15 percent`() {
        val gains = NOISES.map { (seed, decay) ->
            val d = derived(
                SyntheticRun(
                    route = street, elevation = threeHills(40.0),
                    altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed
                )
            )
            assertEquals(ElevationSource.GPS, d.elevationSource)
            assertNotNull(d.elevationLoss)
            d.elevationGain!!.toDouble()
        }

        val worst = gains.maxOf { abs(it - 120.0) / 120.0 }
        println("ElevationReplay hill: gains=${gains.map { it.toInt() }} worst error=${"%.1f".format(worst * 100)} %")
        assertTrue("worst error ${worst * 100} %", worst <= 0.15)
    }

    /**
     * Short rollers: 6 hills of 15 m and then 3 of 8 m, each up 200 m and down 200 m (7.5 % and
     * 4 %), 100 m flat between them: 114 m of true gain.
     */
    private val rollers: (Double) -> Double = { d ->
        val hills = List(6) { 15.0 } + List(3) { 8.0 }
        val period = 500.0
        val i = (d / period).toInt()
        val local = d - i * period
        val height = hills.getOrNull(i) ?: 0.0
        if (local >= 400.0) 150.0 else 150.0 + height * (1 - cos(2 * PI * local / 400.0)) / 2
    }

    /**
     * The known cost of 60 s / 10 m: a hill passed in about two minutes is averaged down to
     * about two thirds of its height, so the 15 m ones hover at the 10 m band and the 8 m ones
     * fall under it. Measured over the 20 noises: 40–80 m of the true 114 (30–65 % short).
     * The bound pins that behaviour; a change of the parameters shows here.
     */
    @Test
    fun `short rollers of 15 and 8 m lose a part of their gain`() {
        val gains = NOISES.map { (seed, decay) ->
            derived(
                SyntheticRun(
                    route = street, elevation = rollers,
                    altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed
                )
            ).elevationGain!!.toDouble()
        }

        println("ElevationReplay rollers (true 114 m): gains=${gains.map { it.toInt() }} min=${gains.min().toInt()} max=${gains.max().toInt()}")
        // Never more than the truth, and at least a third of it
        assertTrue("gains $gains", gains.all { it in 35.0..114.0 })
        assertTrue("average ${gains.average()}", gains.average() in 50.0..70.0)
    }

    @Test
    fun `a flat 5 km round a stadium gains at most 15 m`() {
        val gains = NOISES.map { (seed, decay) ->
            derived(
                SyntheticRun(route = stadium(laps = 13), altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed)
            ).elevationGain!!.toDouble()
        }

        println("ElevationReplay stadium: gains=${gains.map { it.toInt() }} worst=${gains.max().toInt()} m")
        assertTrue("worst gain ${gains.max()} m", gains.max() <= 15.0)
    }

    @Test
    fun `a climb inside a dropped stretch or a gap counts nothing`() {
        // Flat at 150 m, a 30 m climb only while the signal is false (bridged by steps) and a
        // 30 m descent only while there are no fixes at all, flat again after
        val climbFromM = 600.0
        val descentFromM = 2_100.0
        val profile: (Double) -> Double = { d ->
            when {
                d < climbFromM -> 150.0
                d < climbFromM + 300 -> 150.0 + 30 * (d - climbFromM) / 300
                d < descentFromM -> 180.0
                d < descentFromM + 300 -> 180.0 - 30 * (d - descentFromM) / 300
                else -> 150.0
            }
        }
        val speed = 3.3
        val spoofSec = (climbFromM / speed).toInt()..((climbFromM + 300) / speed).toInt()
        val gapSec = (descentFromM / speed).toInt()..((descentFromM + 300) / speed).toInt()
        val gains = SEEDS.map { seed ->
            val d = derived(
                SyntheticRun(
                    route = street,
                    elevation = profile,
                    altitudeNoiseM = NOISE_M,
                    spoof = SyntheticRun.Spoof.Teleport(spoofSec),
                    gapSec = gapSec,
                    strideM = 1.1,
                    seed = seed
                ),
                ReplaySettings(stepDistance = StepDistanceEstimator { steps, _ -> steps * 1.1f })
            )
            d.elevationGain!!.toDouble() to d.elevationLoss!!.toDouble()
        }

        println("ElevationReplay bridge+gap: (gain, loss)=${gains.map { (g, l) -> g.toInt() to l.toInt() }}")
        // Only the noise of the flat pieces may count, never the 30 m across the breaks
        assertTrue(gains.all { (gain, loss) -> gain <= 15.0 && loss <= 15.0 })
    }

    @Test
    fun `fixes without altitude are skipped, not read as zero`() {
        val d = derived(
            SyntheticRun(route = street, elevation = threeHills(40.0), missingAltitude = 500..700)
        )

        // The stretch without altitudes is on the second hill's climb: some of it is missed,
        // but nothing like the 150 m drop to zero and back
        assertTrue("gain ${d.elevationGain}", d.elevationGain!! in 80f..125f)
    }

    @Test
    fun `a run without any altitude has none`() {
        val d = derived(SyntheticRun(route = street, missingAltitude = 0..10_000))

        assertEquals(ElevationSource.NONE, d.elevationSource)
        assertEquals(null, d.elevationGain)
    }

    // --- Barometer: the true profile in baro_m, off by the weather, drifting 8 m/h ---

    /** [run] (with its GPS noise) and a barometer on every fix. */
    private fun withBarometer(run: SyntheticRun) = run.copy(barometer = SyntheticRun.Barometer())

    @Test
    fun `three hills of 40 m by the barometer gain 120 m within 5 percent`() {
        val gains = NOISES.map { (seed, decay) ->
            val d = derived(
                withBarometer(
                    SyntheticRun(
                        route = street, elevation = threeHills(40.0),
                        altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed
                    )
                )
            )
            assertEquals(ElevationSource.BAROMETER, d.elevationSource)
            d.elevationGain!!.toDouble()
        }

        val worst = gains.maxOf { abs(it - 120.0) / 120.0 }
        println("ElevationReplay barometer hill: gains=${gains.map { "%.1f".format(it) }} worst error=${"%.1f".format(worst * 100)} %")
        assertTrue("worst error ${worst * 100} %", worst <= BARO_HILL_MAX_ERROR)
    }

    @Test
    fun `short rollers by the barometer keep nearly all their gain`() {
        val gains = NOISES.map { (seed, decay) ->
            derived(
                withBarometer(
                    SyntheticRun(
                        route = street, elevation = rollers,
                        altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed
                    )
                )
            ).elevationGain!!.toDouble()
        }

        val worst = gains.maxOf { abs(it - 114.0) / 114.0 }
        println("ElevationReplay barometer rollers (true 114 m): gains=${gains.map { "%.1f".format(it) }} worst error=${"%.1f".format(worst * 100)} %")
        assertTrue("worst error ${worst * 100} %", worst <= BARO_ROLLERS_MAX_ERROR)
        // GPS keeps 40–80 m of these (see above): every 8 m hill counts here
        assertTrue("gains $gains", gains.all { it >= 105.0 })
    }

    @Test
    fun `a flat 5 km round a stadium by the barometer gains at most 15 m`() {
        val gains = NOISES.map { (seed, decay) ->
            derived(
                withBarometer(
                    SyntheticRun(route = stadium(laps = 13), altitudeNoiseM = NOISE_M, altitudeNoiseDecay = decay, seed = seed)
                )
            ).elevationGain!!.toDouble()
        }

        println("ElevationReplay barometer stadium: gains=${gains.map { "%.1f".format(it) }} worst=${"%.1f".format(gains.max())} m")
        // Measured 3.8–4.3 m: only the drift of the run counts (8 m/h over ~25 min) and the
        // noise at the turns; the bound is the spec's
        assertTrue("worst gain ${gains.max()} m", gains.max() <= 15.0)
    }

    @Test
    fun `a phone without a barometer records GPS elevation`() {
        val d = derived(
            withBarometer(SyntheticRun(route = street, elevation = threeHills(40.0), altitudeNoiseM = NOISE_M)),
            ReplaySettings(barometerAvailable = false)
        )

        assertEquals(ElevationSource.GPS, d.elevationSource)
        assertNotNull(d.elevationGain)
    }

    @Test
    fun `a barometer on too few fixes leaves the elevation to GPS`() {
        val run = SyntheticRun(route = street, elevation = threeHills(40.0), altitudeNoiseM = NOISE_M)
        // No readings for the last 30 % of the run
        val from = (run.durationSec * 0.7).toInt()
        val d = derived(run.copy(barometer = SyntheticRun.Barometer(missingSec = from..run.durationSec)))

        assertEquals(ElevationSource.GPS, d.elevationSource)
    }

    /** A 400 m running track (two 100 m straights, two half circles of 100 m), [laps] times. */
    private fun stadium(laps: Int): List<Pair<Double, Double>> {
        val r = 100.0 / PI
        val lap = buildList {
            add(0.0 to 0.0)
            add(100.0 to 0.0)
            for (i in 1..12) add(100.0 + r * kotlin.math.sin(PI * i / 12) to r - r * cos(PI * i / 12))
            add(0.0 to 2 * r)
            for (i in 1..12) add(-r * kotlin.math.sin(PI * i / 12) to r + r * cos(PI * i / 12))
        }
        return List(laps) { lap.dropLast(1) }.flatten() + (0.0 to 0.0)
    }

    private companion object {
        val SEEDS = (1L..10L).toList()

        /** Each seed with the position's error decay (20 s) and a slower drift (50 s). */
        val NOISES = SEEDS.flatMap { seed -> listOf(seed to 0.95, seed to 0.98) }

        /** GPS altitude noise "±5 m": standard deviation 2.5 m of the AR(1) error (±2σ). */
        const val NOISE_M = 2.5

        /**
         * Measured over the 20 noises (barometer σ 0.3 m, drift 8 m/h, 5 s / 1.5 m): the hill
         * 122.7–123.3 m of 120 (+2.8 % at worst), the rollers 118.3–120.4 m of 114 (+5.6 %),
         * the stadium 3.8–4.3 m. The surplus is the drift (~4 m over the 30 min run) climbing
         * with the runner; the bounds leave about twice that room.
         */
        const val BARO_HILL_MAX_ERROR = 0.05
        const val BARO_ROLLERS_MAX_ERROR = 0.10
    }
}
