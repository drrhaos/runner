package com.runner.academy.util

/**
 * Distance covered in [steps] at [cadence] (steps/min, null when unknown), in metres.
 *
 * The seam between the per-run [TrackFilter] and the stride model: one frozen estimator per run
 * (see [StrideModel.frozenEstimator]) so the live and the save pipeline bridge the same stretch
 * by the same distance, even while the run's own GPS stretches teach the model.
 */
fun interface StepDistanceEstimator {
    fun distanceMeters(steps: Int, cadence: Float?): Float
}
