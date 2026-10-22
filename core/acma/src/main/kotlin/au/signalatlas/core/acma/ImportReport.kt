package au.signalatlas.core.acma

import kotlinx.serialization.Serializable

/** Outcome of one ACMA import (Import rule 10). [carriersByNetwork] is keyed by `Network.name`. */
@Serializable
data class ImportReport(
    val sites: Int,
    val sectors: Int,
    val carriers: Int,
    val antennas: Int,
    val skippedUnknownBand: Int,
    val skippedNoPower: Int,
    val skippedBadSite: Int,
    val skippedMalformed: Int,
    val carriersByNetwork: Map<String, Int>,
)
