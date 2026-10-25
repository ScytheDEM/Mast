package au.signalatlas.car

/** The trip as the policy sees it. */
interface TripControl {
    val isRunning: Boolean
    fun start()
    fun stop()
}

/**
 * Starts the trip when Android Auto projection connects and stops it on disconnect, unless the driver started it by
 * hand. A trip that was already running when the car connected is the driver's and is left alone too.
 */
class AutoTripPolicy(private val control: TripControl) {
    private enum class Origin { NONE, AUTO, MANUAL }

    private var origin = Origin.NONE

    /** [type] is a `CarConnection.CONNECTION_TYPE_*` value. */
    fun onConnection(type: Int) {
        if (type == PROJECTION) {
            if (control.isRunning) {
                if (origin == Origin.NONE) origin = Origin.MANUAL
            } else {
                origin = Origin.AUTO
                control.start()
            }
        } else if (type == NOT_CONNECTED) {
            if (origin == Origin.AUTO && control.isRunning) control.stop()
            origin = Origin.NONE
        }
    }

    fun manualStart() {
        origin = Origin.MANUAL
        if (!control.isRunning) control.start()
    }

    fun manualStop() {
        origin = Origin.NONE
        if (control.isRunning) control.stop()
    }

    companion object {
        const val NOT_CONNECTED = 0 // CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        const val PROJECTION = 2 // CarConnection.CONNECTION_TYPE_PROJECTION
    }
}
