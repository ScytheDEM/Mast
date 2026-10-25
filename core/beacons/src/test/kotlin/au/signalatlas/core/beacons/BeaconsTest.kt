package au.signalatlas.core.beacons

import au.signalatlas.core.model.LatLon
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun hexBytes(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class BeaconParserTest {
    // Flags AD, then manufacturer data: Apple, iBeacon 0x02 0x15, UUID, major 0x0001, minor 0x0203, TX power.
    private val ibeacon = hexBytes("020106 1AFF4C000215 B9407F30F5F8466EAFF925556B57FE6D 0001 0203 C5")

    @Test fun iBeaconPayload() {
        val s = BeaconParser.parse(ibeacon, -63, 7)!!
        assertThat(s.id).isEqualTo("ib:b9407f30-f5f8-466e-aff9-25556b57fe6d:1:515")
        assertThat(s.rssi).isEqualTo(-63)
        assertThat(s.timeMs).isEqualTo(7)
    }

    @Test fun eddystoneUid() {
        val uid = hexBytes("020106 0303AAFE 1716AAFE00E7 00112233445566778899 AABBCCDDEEFF 0000")
        assertThat(BeaconParser.parse(uid, -70)!!.id).isEqualTo("eu:00112233445566778899:aabbccddeeff")
    }

    @Test fun eddystoneUrlWithSchemeAndExpansion() {
        val url = bytes(0x02, 0x01, 0x06, 0x03, 0x03, 0xAA, 0xFE, 0x0D, 0x16, 0xAA, 0xFE, 0x10, 0xE7, 0x00, 'g'.code, 'o'.code, 'o'.code, 'g'.code, 'l'.code, 'e'.code, 0x07)
        assertThat(BeaconParser.parse(url, -50)!!.id).isEqualTo("eurl:http://www.google.com")
        val https = bytes(0x09, 0x16, 0xAA, 0xFE, 0x10, 0xE7, 0x03, 'a'.code, 'b'.code, 0x00)
        assertThat(BeaconParser.parse(https, -50)!!.id).isEqualTo("eurl:https://ab.com/")
    }

    @Test fun otherAdvertisementsAndTruncatedOnesAreIgnored() {
        assertThat(BeaconParser.parse(hexBytes("020106 0A094D79446576696365"), -60)).isNull() // flags + local name
        assertThat(BeaconParser.parse(hexBytes("020106 1AFF4C000215 B9407F30"), -60)).isNull() // length runs past the end
        assertThat(BeaconParser.parse(hexBytes("020106 0BFF4C0009060102030405"), -60)).isNull() // Apple, not iBeacon
        assertThat(BeaconParser.parse(ByteArray(0), -60)).isNull()
        // Eddystone TLM (0x20) is not a position.
        assertThat(BeaconParser.parse(hexBytes("0916AAFE20000BA400000000"), -60)).isNull()
    }

    @Test fun idNormalisation() {
        val canonical = "ib:b9407f30-f5f8-466e-aff9-25556b57fe6d:1:515"
        assertThat(BeaconIds.normalise("B9407F30-F5F8-466E-AFF9-25556B57FE6D:1:515")).isEqualTo(canonical)
        assertThat(BeaconIds.normalise("b9407f30f5f8466eaff925556b57fe6d:1:515")).isEqualTo(canonical)
        assertThat(BeaconIds.normalise("B9407F30-F5F8-466E-AFF9-25556B57FE6D-1-515")).isEqualTo(canonical)
        assertThat(BeaconIds.normalise("00112233445566778899AABBCCDDEEFF")).isEqualTo("eu:00112233445566778899:aabbccddeeff")
        assertThat(BeaconIds.normalise("ib:b9407f30-f5f8-466e-aff9-25556b57fe6d:70000:1")).isNull()
        assertThat(BeaconIds.normalise("nonsense")).isNull()
    }
}

class BeaconStoreTest {
    @Test fun csvImportStoresPositionsAndCountsSkips() {
        val store = BeaconStore.openInMemory()
        val r = store.importCsv(
            """id,lat,lon
            |# a comment
            |00112233445566778899AABBCCDDEEFF,-33.85,151.21
            |ib:b9407f30-f5f8-466e-aff9-25556b57fe6d:1:2,-33.86,151.22
            |bogus,1,2
            |eurl:http://x.com,-95,10
            |
            """.trimMargin(),
        )
        assertThat(r).isEqualTo(CsvImportResult(2, 2))
        assertThat(store.get("eu:00112233445566778899:aabbccddeeff")!!.pos).isEqualTo(LatLon(-33.85, 151.21))
        assertThat(store.get("ib:b9407f30-f5f8-466e-aff9-25556b57fe6d:1:2")!!.source).isEqualTo(KnownBeacon.Source.IMPORTED)
        assertThat(store.count()).isEqualTo(2)
    }

    @Test fun persistsAcrossReopen() {
        val f = java.io.File.createTempFile("beacons", ".db")
        try {
            BeaconStore.open(f).use { it.put(KnownBeacon("eurl:a", LatLon(-1.0, 2.0), KnownBeacon.Source.LEARNED, 4.0, 3, -60)) }
            val b = BeaconStore.open(f).use { it.get("eurl:a") }!!
            assertThat(b.pos).isEqualTo(LatLon(-1.0, 2.0))
            assertThat(b.n).isEqualTo(3)
            assertThat(b.peakRssi).isEqualTo(-60)
        } finally {
            f.delete()
        }
    }
}

class BeaconLearnerTest {
    private val store = BeaconStore.openInMemory()
    private val learner = BeaconLearner(store) { 1L }
    private fun s(rssi: Int) = BeaconSighting("eurl:t", rssi)

    @Test fun weightedCentroidLeansTowardThePeak() {
        // Three fixes along a line: weak, peak, weak.
        assertThat(learner.learn(s(-74), LatLon(-33.0, 151.000), 5.0)).isTrue()
        assertThat(learner.learn(s(-55), LatLon(-33.0, 151.001), 5.0)).isTrue()
        assertThat(learner.learn(s(-74), LatLon(-33.0, 151.002), 5.0)).isTrue()
        val b = store.get("eurl:t")!!
        // Weights 1.26 / 100 / 1.26: the centroid is within 2 per cent of the way from the peak toward the others.
        assertThat(b.pos.lon).isWithin(0.00003).of(151.001)
        assertThat(b.n).isEqualTo(3)
        assertThat(b.peakRssi).isEqualTo(-55)
        // Equal weights would have given exactly the mean 151.001; a one-sided weak pair shifts it measurably.
        learner.learn(s(-70), LatLon(-33.0, 151.010), 5.0)
        assertThat(store.get("eurl:t")!!.pos.lon).isGreaterThan(151.001)
    }

    @Test fun onlyGoodFixesAndStrongSignalsCount() {
        assertThat(learner.learn(s(-76), LatLon(-33.0, 151.0), 5.0)).isFalse()
        assertThat(learner.learn(s(-60), LatLon(-33.0, 151.0), 15.1)).isFalse()
        assertThat(store.count()).isEqualTo(0)
        assertThat(learner.learn(s(-60), LatLon(-33.0, 151.0), 15.0)).isTrue()
    }

    @Test fun importedPositionsAreNeverMoved() {
        store.importCsv("eurl:t,-10.0,100.0\n".replace("eurl:t", "eurl:t"))
        assertThat(learner.learn(s(-50), LatLon(-33.0, 151.0), 3.0)).isFalse()
        assertThat(store.get("eurl:t")!!.pos).isEqualTo(LatLon(-10.0, 100.0))
    }
}

class BeaconSnapperTest {
    private val store = BeaconStore.openInMemory().also {
        it.put(KnownBeacon("a", LatLon(-33.0, 151.0), KnownBeacon.Source.IMPORTED))
        it.put(KnownBeacon("b", LatLon(-33.0, 151.01), KnownBeacon.Source.IMPORTED))
    }
    private val snapper = BeaconSnapper(store)

    @Test fun strongestKnownBeaconAtOrAboveMinus70WinsAndIsProjected() {
        snapper.onSighting(BeaconSighting("a", -69, 1_000))
        snapper.onSighting(BeaconSighting("b", -64, 1_000))
        snapper.onSighting(BeaconSighting("unknown", -40, 1_000))
        val t = snapper.target(2_000) { LatLon(it.lat - 0.001, it.lon) }!!
        assertThat(t.beaconId).isEqualTo("b")
        assertThat(t.position).isEqualTo(LatLon(-33.001, 151.01))
        assertThat(t.strength).isWithin(1e-9).of(0.8)
    }

    @Test fun tooWeakOrStaleSightingsGiveNoTarget() {
        snapper.onSighting(BeaconSighting("a", -71, 1_000))
        assertThat(snapper.target(1_500) { it }).isNull()
        snapper.onSighting(BeaconSighting("a", -60, 1_000))
        assertThat(snapper.target(1_500) { it }).isNotNull()
        assertThat(snapper.target(7_000) { it }).isNull()
    }

    @Test fun strengthRampsFromHalfAtMinus70ToFullAtMinus60() {
        assertThat(BeaconSnapper.strength(-70)).isWithin(1e-9).of(0.5)
        assertThat(BeaconSnapper.strength(-60)).isWithin(1e-9).of(1.0)
        assertThat(BeaconSnapper.strength(-40)).isWithin(1e-9).of(1.0)
    }
}
