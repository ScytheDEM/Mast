package au.signalatlas.app

import android.os.Build
import android.os.PowerManager

/** Render thread budget (Ruling Z). */
object RenderThreads {
    private const val HEADROOM_LIMIT = 0.85f

    /**
     * 1 thread when hot: headroom >= 0.85 on API 30+, or thermal status >= SEVERE on API 29 (no headroom API).
     * Otherwise min(4, cpus - 2), at least 1. A null or NaN headroom on API 30+ counts as not hot.
     */
    fun budget(sdkInt: Int, headroom: Float?, status: Int, cpus: Int): Int {
        val hot = if (sdkInt >= Build.VERSION_CODES.R) {
            headroom != null && !headroom.isNaN() && headroom >= HEADROOM_LIMIT
        } else {
            status >= PowerManager.THERMAL_STATUS_SEVERE
        }
        return if (hot) 1 else (cpus - 2).coerceIn(1, 4)
    }
}
