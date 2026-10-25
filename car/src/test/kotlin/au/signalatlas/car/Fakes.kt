package au.signalatlas.car

import au.signalatlas.core.model.Network
import au.signalatlas.core.radio.CarrierAggregation
import au.signalatlas.core.radio.DisplayLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

fun sim(
    slot: Int = 1,
    carrier: String = "Telstra",
    label: DisplayLabel? = DisplayLabel.FIVE_G_PLUS,
    band: String? = "n78",
    ca: CarrierAggregation? = CarrierAggregation(listOf(20000, 20000, 20000)),
    rsrp: Int? = -92,
    dist: Double? = 1234.0,
    bearing: Double? = 45.0,
) = CarSim(
    subId = slot, slot = slot, carrier = carrier, network = Network.TELSTRA, label = label, band = band, ca = ca,
    rsrpDbm = rsrp, sinrDb = 12, towerDistanceM = dist, towerBearingDeg = bearing,
    towerLat = if (dist != null) -33.86 else null, towerLon = if (dist != null) 151.2 else null, isData = slot == 1,
    neighbours = 4, predictedDbm = -96,
)

class FakeSource(initial: List<CarSim> = listOf(sim())) : CarDataSource {
    override val sims = MutableStateFlow(initial)
    override val fix = MutableStateFlow<CarFix?>(null)
    override val trip = MutableStateFlow(CarTripState(false))
    override val alerts: Flow<String> = emptyFlow()
    override val coverageTemplate: Flow<String?> = MutableStateFlow(null)
    override suspend fun styleJson(dark: Boolean): String? = null
    override fun sitesNear(lat: Double, lon: Double) = emptyList<CarSite>()
    var carActive = false
    override fun setActive(active: Boolean) { carActive = active }
    var starts = 0
    var stops = 0
    override fun startTrip() { starts++; trip.value = CarTripState(true) }
    override fun stopTrip() { stops++; trip.value = CarTripState(false) }
}
