package au.signalatlas.core.geo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Writes a GLO-90 style COG (classic little-endian TIFF, 1200 x 1200 float32, tiled, DEFLATE, PixelIsPoint, whole-degree
 * tiepoint) for the tests. The elevations are the integer formula of tools/engine/make_geo_fixture.py, so the Python
 * reference decode of its own COGs is the expected value for these.
 */
object SyntheticCog {
    const val SIZE = 1200

    fun seedOf(latN: Int, lonW: Int) = (latN + 100) * 131 + (lonW + 200) * 17

    /** Elevation of node (r, c): multiples of 1/8 m (many x.5), NaN on a lattice. [bias] shifts the surface (a new "version"). */
    fun sourceValue(seed: Int, r: Int, c: Int, bias: Int = 0): Float {
        if ((r * 7 + c * 3) % 1009 == 0) return Float.NaN
        val n = (r * 31 + c * 17 + (r * c) % 97 + seed * 7) % 6000
        return n / 8f - 120f + bias
    }

    /** The source surface of the COG with north edge [latN] and west edge [lonW]. */
    fun surface(latN: Int, lonW: Int, bias: Int = 0): FloatArray {
        val seed = seedOf(latN, lonW)
        return FloatArray(SIZE * SIZE) { sourceValue(seed, it / SIZE, it % SIZE, bias) }
    }

    /**
     * @param tile internal tile side (a multiple of 16)
     * @param predictor 3 (floating point) or 1 (none)
     * @param headerPad bytes of a dummy ASCII tag placed before the tile arrays, to push them past the first 64 KiB
     * @param noData value to declare in GDAL_NODATA, or null for none
     */
    fun build(
        latN: Int,
        lonW: Int,
        tile: Int = 640,
        predictor: Int = 3,
        headerPad: Int = 0,
        bias: Int = 0,
        noData: String? = null,
        surface: FloatArray = surface(latN, lonW, bias),
    ): ByteArray {
        val across = (SIZE + tile - 1) / tile
        val tiles = ArrayList<ByteArray>()
        for (ty in 0 until across) {
            for (tx in 0 until across) {
                val raw = ByteArray(tile * tile * 4)
                for (rr in 0 until tile) {
                    val row = FloatArray(tile) { cc ->
                        val r = ty * tile + rr
                        val c = tx * tile + cc
                        if (r < SIZE && c < SIZE) surface[r * SIZE + c] else 0f
                    }
                    encodeRow(row, raw, rr * tile * 4, predictor)
                }
                tiles += deflate(raw)
            }
        }

        class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)
        fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
        fun longs(vararg v: Long) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()
        fun doubles(vararg v: Double) = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putDouble(it) } }.array()
        val tags = ArrayList<Tag>()
        fun short(id: Int, vararg v: Int) = tags.add(Tag(id, 3, v.size, shorts(*v)))
        short(256, SIZE); short(257, SIZE); short(258, 32); short(259, 8); short(262, 1); short(277, 1); short(284, 1)
        short(317, predictor); short(322, tile); short(323, tile)
        val offsetsTag = Tag(324, 4, tiles.size, ByteArray(tiles.size * 4))
        tags += offsetsTag
        tags += Tag(325, 4, tiles.size, longs(*LongArray(tiles.size) { tiles[it].size.toLong() }))
        short(339, 3)
        tags += Tag(33550, 12, 3, doubles(1.0 / 1200, 1.0 / 1200, 0.0))
        tags += Tag(33922, 12, 6, doubles(0.0, 0.0, 0.0, lonW.toDouble(), latN.toDouble(), 0.0))
        short(34735, 1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 2, 2048, 0, 1, 4326)
        if (headerPad > 0) tags += Tag(270, 2, headerPad, ByteArray(headerPad) { 'x'.code.toByte() })
        if (noData != null) tags += Tag(42113, 2, noData.length + 1, noData.toByteArray(Charsets.US_ASCII) + 0)
        tags.sortBy { it.id }

        val ifdOffset = 8
        val dataStart = ifdOffset + 2 + 12 * tags.size + 4
        val blobs = ByteArrayOutputStream()
        val entries = ByteBuffer.allocate(12 * tags.size).order(ByteOrder.LITTLE_ENDIAN)
        var offsetsAt = -1
        var offsetsInlineAt = -1
        for (t in tags) {
            entries.putShort(t.id.toShort()).putShort(t.type.toShort()).putInt(t.count)
            if (t.data.size <= 4) {
                if (t === offsetsTag) offsetsInlineAt = entries.position()
                entries.put(t.data).put(ByteArray(4 - t.data.size))
            } else {
                if (t === offsetsTag) offsetsAt = blobs.size()
                entries.putInt(dataStart + blobs.size())
                blobs.write(t.data)
            }
        }
        val tilesStart = dataStart + blobs.size()
        val blobBytes = blobs.toByteArray()
        var pos = tilesStart.toLong()
        for (i in tiles.indices) {
            val target = if (offsetsInlineAt >= 0) ByteBuffer.wrap(entries.array(), offsetsInlineAt, 4) else ByteBuffer.wrap(blobBytes, offsetsAt + 4 * i, 4)
            target.order(ByteOrder.LITTLE_ENDIAN).putInt(pos.toInt())
            pos += tiles[i].size
        }
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(tags.size.toShort()).array())
        out.write(entries.array())
        out.write(ByteArray(4))
        out.write(blobBytes)
        tiles.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Predictor 3: the row's big-endian bytes as four planes, then byte-wise differences. Predictor 1: little-endian floats. */
    private fun encodeRow(row: FloatArray, out: ByteArray, at: Int, predictor: Int) {
        val w = row.size
        if (predictor == 1) {
            val b = ByteBuffer.wrap(out, at, w * 4).order(ByteOrder.LITTLE_ENDIAN)
            row.forEach { b.putFloat(it) }
            return
        }
        for (i in 0 until w) {
            val bits = row[i].toRawBits()
            out[at + i] = (bits ushr 24).toByte()
            out[at + w + i] = (bits ushr 16).toByte()
            out[at + 2 * w + i] = (bits ushr 8).toByte()
            out[at + 3 * w + i] = bits.toByte()
        }
        for (i in 4 * w - 1 downTo 1) out[at + i] = (out[at + i] - out[at + i - 1]).toByte()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(6)
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
