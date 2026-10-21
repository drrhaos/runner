package com.runner.academy.ui.devices

/**
 * Tells "refused for good" (only system settings can help) from a dialog merely dismissed.
 * `shouldShowRequestPermissionRationale` is false both before the first refusal and after the
 * last one, so the answer depends on what it was before the request and on earlier requests.
 * Kept by a view model: it outlives a rotation.
 */
class PermissionAsks {

    private var rationaleBefore = false
    private var quietDenials = 0

    /** Call right before launching the request, with the rationale flag at that moment. */
    fun beforeRequest(rationale: Boolean) {
        rationaleBefore = rationale
    }

    /** The request came back not granted; returns true when it was refused for good. */
    fun afterDenial(rationale: Boolean): Boolean {
        if (rationale) {
            quietDenials = 0
            return false
        }
        // The rationale was there and is gone: "don't ask again" (or the second refusal)
        if (rationaleBefore) return true
        // No rationale either side: a dismissed first dialog, or the system no longer asks
        quietDenials++
        return quietDenials >= QUIET_DENIALS_FOR_GOOD
    }

    private companion object {
        const val QUIET_DENIALS_FOR_GOOD = 2
    }
}
