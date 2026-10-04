package com.runner.academy.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Personal step-length model: `stride = intercept + slope · cadence` (metres per step, cadence in
 * steps/min), used to estimate distance from steps while GPS is lost.
 *
 * **Prior from height** ([fromHeight]). Step length scales with body height. The line passes
 * through two anchor points expressed as fractions of height:
 * - walking, 110 spm → 0.415 · h (the classic pedometer walking factor);
 * - easy running, 165 spm → 0.65 · h (≈1.14 m for 175 cm, i.e. ~5:20 min/km).
 * That gives `slope ≈ 0.00427 · h` per spm and `intercept ≈ −0.055 · h`; at 180 spm a 175 cm
 * runner gets ~1.25 m (≈4:27 min/km), in line with typical recreational data.
 *
 * **Learning** ([learn]). Each good-GPS stretch gives one observation `distance / steps` at its
 * mean cadence. Parameters are updated by a two-parameter Kalman / recursive-least-squares step
 * (centred at [CENTER_CADENCE] for conditioning), where:
 * - the measurement noise shrinks with more steps (GPS distance error spread over more steps),
 *   so long stretches weigh more;
 * - a small process noise keeps the model adaptive between runs (fitness, shoes, terrain);
 * - samples with fewer than [MIN_LEARN_STEPS] steps, shorter than [MIN_LEARN_DISTANCE_M],
 *   with cadence outside [MIN_CADENCE]..[MAX_CADENCE] or an implausible stride, are ignored;
 * - samples off the current prediction by more than [OUTLIER_FRACTION] are rejected as GPS
 *   glitches, and any accepted update moves the prediction by at most [MAX_UPDATE_FRACTION].
 *
 * State is persisted with [serialize] / [deserialize] (see `UserPreferences.loadStrideModel`).
 * A model that has never learned is rebuilt from the current height, so editing the height in
 * settings still matters until real data arrives.
 *
 * Mutable and not thread-safe: use it from one thread.
 */
class StrideModel private constructor(
    /** Stride at [CENTER_CADENCE], metres. */
    private var alpha: Double,
    /** Metres per (step/min). */
    private var beta: Double,
    private var pAA: Double,
    private var pAB: Double,
    private var pBB: Double,
    sampleCount: Int
) {

    /** Number of accepted learning samples since the height prior. */
    var sampleCount: Int = sampleCount
        private set

    /** `a` of `a + b · cadence`, metres. */
    val intercept: Float get() = (alpha - beta * CENTER_CADENCE).toFloat()

    /** `b` of `a + b · cadence`, metres per (step/min). */
    val slope: Float get() = beta.toFloat()

    /** Step length in metres at [cadence] (clamped to the plausible cadence and stride range). */
    fun strideMeters(cadence: Float): Float {
        val c = if (cadence.isNaN()) DEFAULT_CADENCE else cadence.coerceIn(MIN_CADENCE, MAX_CADENCE)
        return predict(c.toDouble()).toFloat().coerceIn(MIN_STRIDE_M, MAX_STRIDE_M)
    }

    /** Distance for [steps] at [cadence]; an unknown cadence uses [DEFAULT_CADENCE]. */
    fun distanceMeters(steps: Int, cadence: Float?): Float {
        if (steps <= 0) return 0f
        return strideMeters(cadence ?: DEFAULT_CADENCE) * steps
    }

    /**
     * Learns from a stretch with good GPS: [distanceMeters] covered in [steps] at mean [cadence].
     * Returns true when the sample was used, false when it was ignored or rejected.
     */
    fun learn(distanceMeters: Float, steps: Int, cadence: Float): Boolean {
        if (!distanceMeters.isFinite() || !cadence.isFinite()) return false
        if (steps < MIN_LEARN_STEPS || distanceMeters < MIN_LEARN_DISTANCE_M) return false
        if (cadence < MIN_CADENCE || cadence > MAX_CADENCE) return false
        val observed = distanceMeters.toDouble() / steps
        if (observed < MIN_STRIDE_M || observed > MAX_STRIDE_M) return false

        val x = cadence - CENTER_CADENCE
        val predicted = max(predict(cadence.toDouble()), MIN_STRIDE_M.toDouble())
        val error = observed - predicted
        if (abs(error) > OUTLIER_FRACTION * predicted) return false

        // Random-walk process noise keeps the model learning across runs.
        pAA += PROCESS_NOISE_ALPHA
        pBB += PROCESS_NOISE_BETA

        val noise = (GPS_DISTANCE_SIGMA_M / steps).let { it * it } + STRIDE_SIGMA_M * STRIDE_SIGMA_M
        // P·h with h = (1, x)
        val phA = pAA + pAB * x
        val phB = pAB + pBB * x
        val innovationVar = phA + phB * x + noise
        val gainA = phA / innovationVar
        val gainB = phB / innovationVar

        var dAlpha = gainA * error
        var dBeta = gainB * error
        val change = abs(dAlpha + dBeta * x)
        val maxChange = MAX_UPDATE_FRACTION * predicted
        if (change > maxChange) {
            val scale = maxChange / change
            dAlpha *= scale
            dBeta *= scale
        }
        alpha += dAlpha
        beta = (beta + dBeta).coerceIn(MIN_SLOPE, MAX_SLOPE)
        alpha = alpha.coerceIn(MIN_STRIDE_M.toDouble(), MAX_STRIDE_M.toDouble())

        // P = (I − K·hᵀ)·P, written out for the symmetric 2×2 case.
        val newAA = pAA - gainA * phA
        val newAB = pAB - gainA * phB
        val newBB = pBB - gainB * phB
        pAA = newAA.coerceIn(MIN_VARIANCE, PRIOR_VAR_ALPHA)
        pBB = newBB.coerceIn(MIN_VARIANCE, PRIOR_VAR_BETA)
        val maxCov = kotlin.math.sqrt(pAA * pBB)
        pAB = newAB.coerceIn(-maxCov, maxCov)

        sampleCount++
        return true
    }

    /** An independent copy: learning on one does not change the other. */
    fun copy(): StrideModel = StrideModel(alpha, beta, pAA, pAB, pBB, sampleCount)

    /**
     * Distance estimator over a frozen copy of the current state: what one run uses for its
     * bridges while the run's good stretches keep teaching this model.
     */
    fun frozenEstimator(): StepDistanceEstimator = copy().let { frozen -> StepDistanceEstimator(frozen::distanceMeters) }

    /** Compact text form for preferences; read back with [deserialize]. */
    fun serialize(): String = listOf(alpha, beta, pAA, pAB, pBB)
        .joinToString(separator = SEPARATOR, prefix = VERSION + SEPARATOR, postfix = SEPARATOR + sampleCount) {
            String.format(Locale.US, "%.9g", it)
        }

    private fun predict(cadence: Double): Double = alpha + beta * (cadence - CENTER_CADENCE)

    companion object {
        const val MIN_STRIDE_M = 0.3f
        const val MAX_STRIDE_M = 2.0f
        const val MIN_CADENCE = 60f
        const val MAX_CADENCE = 230f
        /** Cadence assumed when it is unknown (typical easy run). */
        const val DEFAULT_CADENCE = 160f
        /** Largest change of the predicted stride from one accepted sample. */
        const val MAX_UPDATE_FRACTION = 0.10f
        /** Samples further than this from the prediction are treated as GPS glitches. */
        const val OUTLIER_FRACTION = 0.35
        const val MIN_LEARN_STEPS = 40
        const val MIN_LEARN_DISTANCE_M = 30f

        const val DEFAULT_HEIGHT_CM = 175f
        private const val MIN_HEIGHT_CM = 100f
        private const val MAX_HEIGHT_CM = 230f

        // Height anchors (see class docs)
        private const val WALK_CADENCE = 110.0
        private const val WALK_STRIDE_PER_HEIGHT = 0.415
        private const val RUN_CADENCE = 165.0
        private const val RUN_STRIDE_PER_HEIGHT = 0.65

        private const val CENTER_CADENCE = 160.0
        private const val MIN_SLOPE = 0.0
        private const val MAX_SLOPE = 0.02

        private const val PRIOR_VAR_ALPHA = 0.15 * 0.15
        private const val PRIOR_VAR_BETA = 0.004 * 0.004
        private const val PROCESS_NOISE_ALPHA = 1e-5
        private const val PROCESS_NOISE_BETA = 1e-9
        private const val MIN_VARIANCE = 1e-10
        private const val GPS_DISTANCE_SIGMA_M = 5.0
        private const val STRIDE_SIGMA_M = 0.05

        private const val VERSION = "v1"
        private const val SEPARATOR = ";"

        /** Height-based prior (cm); an implausible height uses [DEFAULT_HEIGHT_CM]. */
        fun fromHeight(heightCm: Float): StrideModel {
            val h = (if (heightCm.isFinite() && heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM) heightCm else DEFAULT_HEIGHT_CM) / 100.0
            val slope = (RUN_STRIDE_PER_HEIGHT - WALK_STRIDE_PER_HEIGHT) * h / (RUN_CADENCE - WALK_CADENCE)
            val strideAtRun = RUN_STRIDE_PER_HEIGHT * h
            val alpha = strideAtRun + slope * (CENTER_CADENCE - RUN_CADENCE)
            return StrideModel(alpha, slope, PRIOR_VAR_ALPHA, 0.0, PRIOR_VAR_BETA, 0)
        }

        /**
         * Restores [serialize]d state. Missing, corrupt or never-trained state yields the prior
         * from [heightCm].
         */
        fun deserialize(state: String?, heightCm: Float): StrideModel {
            val model = deserializeExact(state) ?: return fromHeight(heightCm)
            return if (model.sampleCount > 0) model else fromHeight(heightCm)
        }

        /**
         * Restores [serialize]d state exactly as it was, an untrained prior included (it is not
         * rebuilt from the current height): a run's frozen stride must give the save path the
         * same bridges as the live path even if the height changed meanwhile. Null if corrupt.
         */
        fun deserializeExact(state: String?): StrideModel? {
            val parts = state?.split(SEPARATOR) ?: return null
            if (parts.size != 7 || parts[0] != VERSION) return null
            val values = parts.subList(1, 6).map { it.toDoubleOrNull() ?: return null }
            val count = parts[6].toIntOrNull() ?: return null
            if (values.any { !it.isFinite() } || count < 0) return null
            val (alpha, beta, pAA, pAB, pBB) = values
            if (alpha !in MIN_STRIDE_M.toDouble()..MAX_STRIDE_M.toDouble() || beta !in MIN_SLOPE..MAX_SLOPE) return null
            if (pAA <= 0 || pBB <= 0) return null
            return StrideModel(alpha, beta, pAA, pAB, pBB, count)
        }

        /**
         * The estimator a run froze in [state] (see [frozenEstimator]); a corrupt state falls
         * back to the prior from [fallbackHeightCm].
         */
        fun frozenEstimatorOf(state: String, fallbackHeightCm: Float): StepDistanceEstimator =
            (deserializeExact(state) ?: fromHeight(fallbackHeightCm)).frozenEstimator()
    }
}
