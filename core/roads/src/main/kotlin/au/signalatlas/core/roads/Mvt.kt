package au.signalatlas.core.roads

/**
 * One decoded Mapbox Vector Tile feature. [type] is 1 point, 2 line string, 3 polygon; [geometry] holds one
 * `[x0, y0, x1, y1, ...]` array per line / ring in tile units (0 until the layer extent, signed beyond the buffer).
 */
class MvtFeature(val type: Int, val properties: Map<String, Any?>, val geometry: List<IntArray>)

class MvtLayer(val name: String, val extent: Int, val features: List<MvtFeature>)

/** A minimal protobuf reader for the MVT schema (varint, fixed and length-delimited fields only). */
internal class ProtoReader(private val buf: ByteArray, var pos: Int = 0, private val end: Int = buf.size) {
    val hasMore: Boolean get() = pos < end

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= end || shift > 63) throw IllegalArgumentException("truncated varint")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b >= 0) return result
            shift += 7
        }
    }

    /** The next field header as `fieldNumber shl 3 or wireType`. */
    fun tag(): Int = varint().toInt()

    fun fixed32(): Int {
        require(pos + 4 <= end) { "truncated fixed32" }
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun fixed64(): Long {
        require(pos + 8 <= end) { "truncated fixed64" }
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    /** A length-delimited field's payload as a reader over the same buffer. */
    fun message(): ProtoReader {
        val len = varint().toInt()
        require(len >= 0 && pos + len <= end) { "truncated message" }
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun string(): String {
        val len = varint().toInt()
        require(len >= 0 && pos + len <= end) { "truncated string" }
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> varint()
            1 -> { require(pos + 8 <= end); pos += 8 }
            2 -> { val len = varint().toInt(); require(len >= 0 && pos + len <= end); pos += len }
            5 -> { require(pos + 4 <= end); pos += 4 }
            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
    }
}

object Mvt {
    /** Decodes the layers of an (uncompressed) tile; only layers named in [only] when given. Throws IllegalArgumentException on malformed input. */
    fun decode(bytes: ByteArray, only: Set<String>? = null): List<MvtLayer> {
        val out = ArrayList<MvtLayer>()
        val r = ProtoReader(bytes)
        while (r.hasMore) {
            val tag = r.tag()
            if (tag == (3 shl 3 or 2)) {
                decodeLayer(r.message(), only)?.let { out += it }
            } else {
                r.skip(tag and 7)
            }
        }
        return out
    }

    private class RawFeature(val tags: IntArray, val type: Int, val geometry: IntArray)

    private fun decodeLayer(r: ProtoReader, only: Set<String>?): MvtLayer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val raw = ArrayList<RawFeature>()
        while (r.hasMore) {
            val tag = r.tag()
            when (tag) {
                1 shl 3 or 2 -> name = r.string()
                2 shl 3 or 2 -> raw += decodeFeature(r.message())
                3 shl 3 or 2 -> keys += r.string()
                4 shl 3 or 2 -> values += decodeValue(r.message())
                5 shl 3 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (only != null && name !in only) return null
        val features = raw.map { f ->
            val props = LinkedHashMap<String, Any?>()
            var i = 0
            while (i + 1 < f.tags.size) {
                val k = keys.getOrNull(f.tags[i])
                if (k != null && f.tags[i + 1] in values.indices) props[k] = values[f.tags[i + 1]]
                i += 2
            }
            MvtFeature(f.type, props, decodeGeometry(f.geometry))
        }
        return MvtLayer(name, extent, features)
    }

    private fun decodeFeature(r: ProtoReader): RawFeature {
        var tags = IntArray(0)
        var type = 0
        var geom = IntArray(0)
        while (r.hasMore) {
            val tag = r.tag()
            when (tag) {
                2 shl 3 or 2 -> tags = packed(r.message())
                3 shl 3 -> type = r.varint().toInt()
                4 shl 3 or 2 -> geom = packed(r.message())
                else -> r.skip(tag and 7)
            }
        }
        return RawFeature(tags, type, geom)
    }

    private fun packed(r: ProtoReader): IntArray {
        val out = ArrayList<Int>()
        while (r.hasMore) out += r.varint().toInt()
        return out.toIntArray()
    }

    private fun decodeValue(r: ProtoReader): Any? {
        var v: Any? = null
        while (r.hasMore) {
            val tag = r.tag()
            v = when (tag) {
                1 shl 3 or 2 -> r.string()
                2 shl 3 or 5 -> Float.fromBits(r.fixed32())
                3 shl 3 or 1 -> Double.fromBits(r.fixed64())
                4 shl 3, 5 shl 3 -> r.varint()
                6 shl 3 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                7 shl 3 -> r.varint() != 0L
                else -> { r.skip(tag and 7); v }
            }
        }
        return v
    }

    /** MoveTo(1) / LineTo(2) / ClosePath(7) commands with zig-zag deltas into one coordinate array per line or ring. */
    internal fun decodeGeometry(cmds: IntArray): List<IntArray> {
        val lines = ArrayList<IntArray>()
        var cur = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        while (i < cmds.size) {
            val header = cmds[i++]
            val id = header and 7
            val count = header ushr 3
            when (id) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= cmds.size) throw IllegalArgumentException("truncated geometry")
                    val dx = cmds[i++]
                    val dy = cmds[i++]
                    x += (dx ushr 1) xor -(dx and 1)
                    y += (dy ushr 1) xor -(dy and 1)
                    if (id == 1 && cur.isNotEmpty()) {
                        lines += cur.toIntArray()
                        cur = ArrayList()
                    }
                    cur += x
                    cur += y
                }
                7 -> if (cur.size >= 2) { cur += cur[0]; cur += cur[1] }
                else -> throw IllegalArgumentException("bad geometry command $id")
            }
        }
        if (cur.isNotEmpty()) lines += cur.toIntArray()
        return lines
    }
}
