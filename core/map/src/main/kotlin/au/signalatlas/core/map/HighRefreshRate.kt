package au.signalatlas.core.map

import android.app.Activity
import android.os.Build
import android.view.Display

/** Asks for the display's fastest refresh rate (120 Hz where available) at the current resolution. */
object HighRefreshRate {
    /** Plain copy of the [Display.Mode] fields the choice depends on, so it is testable on the JVM. */
    data class ModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    fun apply(activity: Activity) {
        val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: return
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val current = display.mode.spec()
        val best = pickMode(current, display.supportedModes.map { it.spec() })
        val window = activity.window
        if (window.attributes.preferredDisplayModeId == best.id) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.id }
    }

    /** The mode with the highest refresh rate at [current]'s physical size; [current] on a tie. */
    fun pickMode(current: ModeSpec, modes: List<ModeSpec>): ModeSpec =
        modes.filter { it.width == current.width && it.height == current.height && it.refreshRate > current.refreshRate }
            .maxByOrNull { it.refreshRate } ?: current

    private fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
}
