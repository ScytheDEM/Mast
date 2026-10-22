package au.signalatlas.core.model

data class BandInfo(val band: String, val tech: Tech)

data class Site(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val name: String,
    val state: String?,
    val postcode: String?,
    val elevationM: Double?,
)

data class Antenna(
    val id: Int,
    val gainDbi: Double?,
    val frontToBackDb: Double?,
    val hBeamwidthDeg: Double?,
    val model: String?,
    val manufacturer: String?,
)

data class Carrier(
    val sddId: Long,
    val freqHz: Long,
    val bandwidthHz: Long,
    val eirpDbm: Double,
    val tiltDeg: Double,
    val band: BandInfo,
    val licenceNo: String,
    val deviceRegId: String,
)

data class Sector(
    val id: Int,
    val siteId: Int,
    val network: Network,
    val antenna: Antenna?,
    val azimuthDeg: Double?,
    val heightM: Double,
    val carriers: List<Carrier>,
)

data class SiteSummary(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val name: String,
    val networks: Set<Network>,
    val techs: Set<Tech>,
    val bands: Set<String>,
)

data class SiteDetail(val site: Site, val sectors: List<Sector>)

data class TowerFilter(
    val networks: Set<Network> = Network.entries.toSet(),
    val techs: Set<Tech> = Tech.entries.toSet(),
    val bands: Set<String>? = null,
) {
    fun matches(s: SiteSummary): Boolean =
        s.networks.any { it in networks } &&
            s.techs.any { it in techs } &&
            (bands == null || s.bands.any { it in bands })
}
