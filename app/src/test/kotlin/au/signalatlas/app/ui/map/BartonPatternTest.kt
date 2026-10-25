package au.signalatlas.app.ui.map

import au.signalatlas.app.data.HrpPattern
import au.signalatlas.app.data.PatternAccess
import au.signalatlas.core.map.SectorBeams
import au.signalatlas.core.model.BandInfo
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real ACMA data for Barton, Canberra (site 101160): 111 carriers in 69 sector rows, every one with a measured pattern
 * (`site_101160_patterns.json`, written by tools/acma/site_patterns.py from the 2026-10-22 RRL and the 2026-10-21 HRP
 * export). Telstra points at 80/200/320°, Optus, TPG and the JV at 10/130/240°, TPG has one more at 315°.
 */
@RunWith(RobolectricTestRunner::class)
class BartonPatternTest {
    private val json = JSONObject(javaClass.classLoader!!.getResource("site_101160_patterns.json")!!.readText())
    private val carriers = json.getJSONArray("carriers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val patterns: Map<Long, HrpPattern> = carriers.filter { it.has("att") }.associate {
        it.getLong("sdd_id") to HrpPattern(hex(it.getString("att")), it.getInt("peakBearing"))
    }
    private val detail = SiteDetail(
        Site(101160, json.getDouble("lat"), json.getDouble("lon"), "Barton", "ACT", "2600", null),
        carriers.groupBy { it.getInt("sector_id") }.map { (id, cs) ->
            val c0 = cs.first()
            Sector(
                id, 101160, Network.valueOf(c0.getString("network")), null,
                c0.optDouble("azimuth_deg").takeUnless { it.isNaN() }, c0.getDouble("height_m"),
                cs.map {
                    Carrier(
                        it.getLong("sdd_id"), it.getLong("freq_hz"), 0, 0.0, 0.0,
                        BandInfo(it.getString("band"), Tech.valueOf(it.getString("tech"))), "", "",
                    )
                },
            )
        },
    )
    private val plots = sectorPlots(detail, PatternAccess { patterns[it] })

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun sixtyNineSectorRowsAreThirteenLobes() {
        assertThat(detail.sectors).hasSize(69)
        assertThat(plots.map { it.key }).containsExactly(
            "TELSTRA-80", "TELSTRA-200", "TELSTRA-320",
            "OPTUS-10", "OPTUS-130", "OPTUS-240",
            "TPG-10", "TPG-130", "TPG-240", "TPG-315",
            "OPTUS_TPG_JV-10", "OPTUS_TPG_JV-130", "OPTUS_TPG_JV-240",
        ).inOrder()
        assertThat(plots.all { it.source == PatternSource.ACMA }).isTrue()
    }

    @Test fun everyLobePeaksAtItsAzimuthWithAFrontToBackDip() {
        for (p in plots) {
            val az = p.azimuthDeg!!
            val att = p.attenuationDb
            val peak = (0 until 360).minBy { att[it] }
            // Each device peaks within 12° of its azimuth; the envelope's flat top may sit a little wider.
            assertThat(angleDiff(peak.toDouble(), az)).isAtMost(20.0)
            val back = att[Math.floorMod(az.toInt() + 180, 360)]
            assertThat(back).isAtLeast(20f) // panel antennas: 25–45 dB front-to-back in the export
            // On the map the back is a sliver: under a tenth of the length.
            assertThat(SectorBeams.radiusFraction(back)).isLessThan(0.1)
        }
    }

    @Test fun theFlatAcmaDevicesDoNotTurnTelstraIntoACircle() {
        // Antenna 40081 (Telstra, 2 carriers per azimuth) is filed as one 0–360° row at 70 dBW: flat.
        val flat = carriers.filter { it.getInt("antenna_id") == 40081 }.map { patterns.getValue(it.getLong("sdd_id")) }
        assertThat(flat).hasSize(6)
        assertThat(flat.all { p -> p.attenuation.all { it.toInt() == 0 } }).isTrue()
        val telstra80 = plots.single { it.key == "TELSTRA-80" }
        assertThat(telstra80.attenuationDb[260]).isAtLeast(20f)
    }

    @Test fun telstraLobesListTheirBands() {
        val t = plots.single { it.key == "TELSTRA-80" }
        assertThat(t.bands.first()).isEqualTo("B28")
        assertThat(t.bands).contains("n78")
        assertThat(t.bands.indexOf("n78")).isGreaterThan(t.bands.indexOf("B3"))
    }
}
