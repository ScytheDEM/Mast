package au.signalatlas.core.data

import java.io.IOException

/** The opened `towers.db` is not schema version [expected] ([found] is null when it records none). */
class SchemaMismatchException(val found: Int?, val expected: Int) :
    IOException("tower DB schema version ${found ?: "(none)"}, expected $expected")

/**
 * Structure-of-arrays view of every carrier on the sites in some bounds, ordered by site id then
 * sdd id; all arrays have exactly [size] entries. NaN marks "not applicable / unknown": azimuth for
 * an omni sector, and the antenna fields when the sector has no antenna record (or the value).
 *
 * [tech] and [network] hold the `Tech` / `Network` ordinals. [bandIdx] indexes [bandLabels]
 * (read it unsigned: `bandIdx[i].toInt() and 0xFF`); the labels are all of the DB's bands sorted
 * by frequency, the same list as [TowerStore.allBands].
 */
class CarrierBatch(
    val size: Int,
    val siteLat: DoubleArray,
    val siteLon: DoubleArray,
    val heightM: FloatArray,
    val azimuthDeg: FloatArray,
    val tiltDeg: FloatArray,
    val eirpDbm: FloatArray,
    val freqMhz: FloatArray,
    val bandwidthHz: IntArray,
    val tech: ByteArray,
    val network: ByteArray,
    val hBeamwidthDeg: FloatArray,
    val frontToBackDb: FloatArray,
    val gainDbi: FloatArray,
    val sectorId: IntArray,
    val sddId: LongArray,
    val bandIdx: ByteArray,
    val bandLabels: List<String>,
)
