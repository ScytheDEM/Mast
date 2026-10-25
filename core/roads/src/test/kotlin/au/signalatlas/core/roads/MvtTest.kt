package au.signalatlas.core.roads

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MvtTest {
    /** A real Protomaps v4 z14 tile (build 20261024) over Gundagai NSW, 14932/9897, decompressed. */
    private val fixture = javaClass.getResourceAsStream("/gundagai_z14_14932_9897.mvt")!!.readBytes()

    @Test fun decodesTheRoadsLayerOfARealTile() {
        val layers = Mvt.decode(fixture)
        assertThat(layers.map { it.name }).contains("roads")
        val roads = layers.first { it.name == "roads" }
        assertThat(roads.extent).isEqualTo(4096)
        assertThat(roads.features).isNotEmpty()
        val f = roads.features.first { it.type == 2 }
        assertThat(f.properties["kind"]).isInstanceOf(String::class.java)
        assertThat(f.geometry.first().size).isAtLeast(4)
        // The filter keeps only the requested layers.
        assertThat(Mvt.decode(fixture, setOf("roads")).map { it.name }).containsExactly("roads")
    }

    @Test fun roadWaysAreDrivableLinesInsideTheTile() {
        val ways = RoadTileDecoder.decode(fixture, 14932, 9897)
        assertThat(ways).isNotEmpty()
        // Gundagai: -35.065, 148.115.
        val lon0 = RoadTiles.lon(14932.0 - 0.05); val lon1 = RoadTiles.lon(14933.05)
        val lat0 = RoadTiles.lat(9898.05); val lat1 = RoadTiles.lat(9897.0 - 0.05)
        for (w in ways) for (p in w.points) {
            assertThat(p.lon).isIn(com.google.common.collect.Range.closed(lon0, lon1))
            assertThat(p.lat).isIn(com.google.common.collect.Range.closed(lat0, lat1))
        }
        assertThat(RoadTiles.x(148.115)).isEqualTo(14932)
        assertThat(RoadTiles.y(-35.065)).isEqualTo(9897)
        assertThat(ways.map { it.roadClass }.toSet()).isNotEmpty()
        // Paths, rails and ferries are not roads for a car.
        val kinds = Mvt.decode(fixture, setOf("roads")).single().features.map { it.properties["kind"] }
        val drivable = kinds.count { it in setOf("highway", "major_road", "medium_road", "minor_road") }
        assertThat(ways.size).isAtLeast(drivable) // a multi-line feature gives several ways
    }

    @Test fun graphBuiltFromTheRealTileMatchesAndFollowsItsRoads() {
        val ways = RoadTileDecoder.decode(fixture, 14932, 9897)
        val start = ways.first { it.points.size >= 3 }.points.let { it[0] }
        val g = RoadGraph.build(start.lat, start.lon, ways)
        assertThat(g.nodeCount).isGreaterThan(10)
        assertThat(g.edgeCount).isGreaterThan(10)
        val second = ways.first { it.points.size >= 3 }.points[1]
        val heading = Math.toDegrees(Math.atan2((second.lon - start.lon) * Math.cos(Math.toRadians(start.lat)), second.lat - start.lat))
        val m = g.match(start.lat, start.lon, heading)
        assertThat(m).isNotNull()
        assertThat(g.follow(m!!, 1_000.0).points).hasSize(20)
    }

    @Test fun decodesGeometryCommandsAndZigZagDeltas() {
        // MoveTo(5,7); LineTo(+3,-2),(0,+4); MoveTo(-1,-1); LineTo(+2,0); ClosePath
        val cmds = intArrayOf(9, 10, 14, 18, 6, 3, 0, 8, 9, 1, 1, 10, 4, 0, 15)
        val lines = Mvt.decodeGeometry(cmds)
        assertThat(lines).hasSize(2)
        assertThat(lines[0].toList()).containsExactly(5, 7, 8, 5, 8, 9).inOrder()
        assertThat(lines[1].toList()).containsExactly(7, 8, 9, 8, 7, 8).inOrder()
    }

    @Test fun malformedInputIsRejectedNotMisread() {
        assertThrows(IllegalArgumentException::class.java) { Mvt.decode(fixture.copyOf(fixture.size / 2)) }
        assertThrows(IllegalArgumentException::class.java) { Mvt.decodeGeometry(intArrayOf(9, 2)) }
        assertThat(Mvt.decode(ByteArray(0))).isEmpty()
    }
}

/** Hand-encoded MVT: one `roads` layer with a single east-west line feature carrying the given (key, value) properties. */
internal object TestMvt {
    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (x >= 0x80) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
        return out.toByteArray()
    }

    private fun field(num: Int, wire: Int, payload: ByteArray): ByteArray =
        varint((num shl 3 or wire).toLong()) + (if (wire == 2) varint(payload.size.toLong()) else byteArrayOf()) + payload

    private fun packed(vararg v: Int) = v.fold(byteArrayOf()) { acc, i -> acc + varint(i.toLong()) }

    private fun value(v: Any): ByteArray = when (v) {
        is String -> field(1, 2, v.toByteArray())
        is Boolean -> field(7, 0, varint(if (v) 1 else 0))
        else -> error("unsupported $v")
    }

    fun roadsTile(props: List<Pair<String, Any>>): ByteArray {
        val tags = props.indices.flatMap { listOf(it, it) }.toIntArray()
        val feature = field(2, 2, packed(*tags)) + field(3, 0, varint(2)) +
            field(4, 2, packed(9, 0, 4096, 10, 8192, 0))
        var layer = field(1, 2, "roads".toByteArray()) + field(2, 2, feature)
        props.forEach { layer += field(3, 2, it.first.toByteArray()) }
        props.forEach { layer += field(4, 2, value(it.second)) }
        layer += field(5, 0, varint(4096)) + field(15, 0, varint(2))
        return field(3, 2, layer)
    }
}
