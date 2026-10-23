package au.signalatlas.core.geo

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The COG is not the GLO-90 layout this reader supports (or is damaged). */
class CogFormatException(message: String) : IOException(message)

/**
 * What a GLO-90 COG's first IFD says, enough to find and decode its internal tiles.
 *
 * GLO-90 layout (checked against the real files, see tools/engine/glo90_crop.py): little-endian TIFF, 1200 x 1200
 * float32 (one sample per 3 arcseconds), tiled, DEFLATE, predictor 3 (or none), PixelIsPoint, whole-degree tiepoint
 * (pixel (0, 0) -> west, north). Node (row, col) lies at latitude `latNorth - row / 1200` and longitude
 * `lonWest + col / 1200`. [offsets] and [byteCounts] are per internal tile, row-major.
 */
class CogMeta(
    val etag: String,
    val fileLength: Long,
    val latNorth: Int,
    val lonWest: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val predictor: Int,
    val noData: Float?,
    val offsets: LongArray,
    val byteCounts: LongArray,
) {
    val tilesAcross: Int get() = (SIZE + tileWidth - 1) / tileWidth
    val tilesDown: Int get() = (SIZE + tileHeight - 1) / tileHeight

    companion object {
        const val SIZE = ChunkMath.NODES_PER_DEGREE
    }
}

/** The IFD needs bytes beyond the prefix given to [CogReader.parseHeader]: the first [bytesNeeded] bytes are required. */
class NeedMoreHeader(val bytesNeeded: Long) : Exception("Header needs $bytesNeeded bytes", null, false, false)

object CogReader {
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_SAMPLES = 277
    private const val TAG_PLANAR = 284
    private const val TAG_PREDICTOR = 317
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_HEIGHT = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_COUNTS = 325
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_PIXEL_SCALE = 33550
    private const val TAG_TIEPOINT = 33922
    private const val TAG_GEOKEYS = 34735
    private const val TAG_NODATA = 42113
    private const val GEOKEY_RASTER_TYPE = 1025
    private const val RASTER_PIXEL_IS_POINT = 2

    /** A view of the file head; reading past its end asks for more bytes with [NeedMoreHeader]. */
    private class Head(private val buf: ByteArray, private val fileLength: Long) {
        fun bytes(offset: Long, n: Int): ByteBuffer {
            if (offset < 0 || n < 0 || offset + n > fileLength) throw CogFormatException("Header read past the end of the file")
            if (offset + n > buf.size) throw NeedMoreHeader(offset + n)
            return ByteBuffer.wrap(buf, offset.toInt(), n).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private class Entry(val type: Int, val count: Long, val valueAt: Long, val inline: Boolean)

    /**
     * Parses the first IFD of the COG whose first bytes are [initial] (a prefix of a file of [fileLength] bytes).
     * Throws [NeedMoreHeader] when the IFD (typically the tile offset arrays) reaches past [initial]; the caller
     * fetches a longer prefix and parses again.
     */
    fun parseHeader(initial: ByteArray, fileLength: Long, etag: String): CogMeta {
        val head = Head(initial, fileLength)
        val first = head.bytes(0, 8)
        if (first.get(0) != 'I'.code.toByte() || first.get(1) != 'I'.code.toByte()) {
            throw CogFormatException("Only little-endian TIFF is supported")
        }
        val big = when (first.getShort(2).toInt()) {
            42 -> false
            43 -> true
            else -> throw CogFormatException("Not a TIFF")
        }
        val ifd = if (big) head.bytes(8, 8).getLong(0) else first.getInt(4).toLong() and 0xFFFFFFFFL
        val countSize = if (big) 8 else 2
        val entrySize = if (big) 20 else 12
        val n = head.bytes(ifd, countSize).let { if (big) it.getLong(0) else (it.getShort(0).toInt() and 0xFFFF).toLong() }
        val table = head.bytes(ifd + countSize, (n * entrySize).toInt())
        val entries = HashMap<Int, Entry>()
        for (k in 0 until n.toInt()) {
            val at = k * entrySize
            val tag = table.getShort(at).toInt() and 0xFFFF
            val type = table.getShort(at + 2).toInt() and 0xFFFF
            val count = if (big) table.getLong(at + 4) else table.getInt(at + 4).toLong() and 0xFFFFFFFFL
            val valueField = at + (if (big) 12 else 8)
            val size = typeSize(type) * count
            val inline = size <= (if (big) 8 else 4)
            val valueAt = if (inline) {
                ifd + countSize + valueField
            } else {
                if (big) table.getLong(valueField) else table.getInt(valueField).toLong() and 0xFFFFFFFFL
            }
            entries[tag] = Entry(type, count, valueAt, inline)
        }

        fun longs(tag: Int): LongArray? {
            val e = entries[tag] ?: return null
            val b = head.bytes(e.valueAt, (typeSize(e.type) * e.count).toInt())
            return LongArray(e.count.toInt()) { i ->
                when (e.type) {
                    3 -> b.getShort(i * 2).toLong() and 0xFFFF
                    4 -> b.getInt(i * 4).toLong() and 0xFFFFFFFFL
                    16 -> b.getLong(i * 8)
                    else -> throw CogFormatException("Tag $tag has unsupported type ${e.type}")
                }
            }
        }

        fun doubles(tag: Int): DoubleArray? {
            val e = entries[tag] ?: return null
            if (e.type != 12) throw CogFormatException("Tag $tag is not a double array")
            val b = head.bytes(e.valueAt, 8 * e.count.toInt())
            return DoubleArray(e.count.toInt()) { b.getDouble(it * 8) }
        }

        fun one(tag: Int, default: Long? = null): Long =
            longs(tag)?.singleOrNull() ?: default ?: throw CogFormatException("Missing tag $tag")

        if (one(TAG_COMPRESSION) != 8L) throw CogFormatException("Only DEFLATE (8) is supported")
        if (one(TAG_BITS) != 32L || one(TAG_SAMPLE_FORMAT) != 3L) throw CogFormatException("Only float32 samples are supported")
        if (one(TAG_SAMPLES, 1) != 1L || one(TAG_PLANAR, 1) != 1L) throw CogFormatException("Only one chunky band is supported")
        val predictor = one(TAG_PREDICTOR, 1).toInt()
        if (predictor != 1 && predictor != 3) throw CogFormatException("Unsupported predictor $predictor")
        if (one(TAG_WIDTH) != CogMeta.SIZE.toLong() || one(TAG_HEIGHT) != CogMeta.SIZE.toLong()) {
            throw CogFormatException("Expected a ${CogMeta.SIZE} x ${CogMeta.SIZE} GLO-90 raster")
        }
        val tw = one(TAG_TILE_WIDTH).toInt()
        val th = one(TAG_TILE_HEIGHT).toInt()
        if (tw <= 0 || th <= 0) throw CogFormatException("Bad tile size")
        val keys = longs(TAG_GEOKEYS) ?: throw CogFormatException("No GeoKeyDirectory")
        var rasterType = -1L
        for (k in 0 until keys[3].toInt()) {
            if (keys[4 + 4 * k] == GEOKEY_RASTER_TYPE.toLong() && keys[5 + 4 * k] == 0L) rasterType = keys[7 + 4 * k]
        }
        if (rasterType != RASTER_PIXEL_IS_POINT.toLong()) throw CogFormatException("Expected PixelIsPoint")
        val scale = doubles(TAG_PIXEL_SCALE) ?: throw CogFormatException("No ModelPixelScale")
        val tie = doubles(TAG_TIEPOINT) ?: throw CogFormatException("No ModelTiepoint")
        val step = 1.0 / CogMeta.SIZE
        if (Math.abs(scale[0] - step) > 1e-12 || Math.abs(scale[1] - step) > 1e-12) throw CogFormatException("Expected 1/1200 degree pixels")
        if (tie[0] != 0.0 || tie[1] != 0.0 || tie[3] != Math.rint(tie[3]) || tie[4] != Math.rint(tie[4])) {
            throw CogFormatException("Expected the tiepoint (0, 0) on whole degrees")
        }
        val tiles = ((CogMeta.SIZE + tw - 1) / tw) * ((CogMeta.SIZE + th - 1) / th)
        val offsets = longs(TAG_TILE_OFFSETS) ?: throw CogFormatException("Not tiled")
        val counts = longs(TAG_TILE_COUNTS) ?: throw CogFormatException("Not tiled")
        if (offsets.size != tiles || counts.size != tiles) throw CogFormatException("Tile arrays do not match $tiles tiles")
        for (i in 0 until tiles) {
            if (counts[i] < 0 || offsets[i] < 0 || offsets[i] + counts[i] > fileLength) throw CogFormatException("Tile $i lies outside the file")
        }
        val noData = entries[TAG_NODATA]?.let { e ->
            if (e.type != 2) return@let null
            val s = Charsets.US_ASCII.decode(head.bytes(e.valueAt, e.count.toInt())).toString().trimEnd('\u0000').trim()
            s.toFloatOrNull()
        }
        return CogMeta(etag, fileLength, tie[4].toInt(), tie[3].toInt(), tw, th, predictor, noData, offsets, counts)
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 7 -> 1
        3 -> 2
        4, 11 -> 4
        5, 12, 16 -> 8
        else -> throw CogFormatException("Unsupported TIFF field type $type")
    }

    /**
     * Inflates one internal tile and undoes the predictor, returning `tileWidth * rows` floats row-major. Only the
     * first [rows] rows are decoded (rows below the raster's last row are padding). A tile with no bytes is a sparse
     * tile: all zeros.
     */
    fun decodeTile(meta: CogMeta, compressed: ByteArray, rows: Int): FloatArray {
        val tw = meta.tileWidth
        val out = FloatArray(tw * rows)
        if (compressed.isEmpty()) return out
        val rowBytes = tw * 4
        val raw = ByteArray(rowBytes * meta.tileHeight)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            var n = 0
            while (n < raw.size) {
                val got = inflater.inflate(raw, n, raw.size - n)
                if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            if (n != raw.size) throw CogFormatException("Tile inflates to $n of ${raw.size} bytes")
        } catch (e: DataFormatException) {
            throw CogFormatException("Corrupt tile: ${e.message}")
        } finally {
            inflater.end()
        }
        for (r in 0 until rows) {
            val base = r * rowBytes
            if (meta.predictor == 3) {
                // Undo the byte-wise horizontal differencing over the whole row, then re-interleave the four byte
                // planes (most significant first) into floats.
                for (i in 1 until rowBytes) raw[base + i] = (raw[base + i] + raw[base + i - 1]).toByte()
                for (i in 0 until tw) {
                    val bits = ((raw[base + i].toInt() and 0xFF) shl 24) or
                        ((raw[base + tw + i].toInt() and 0xFF) shl 16) or
                        ((raw[base + 2 * tw + i].toInt() and 0xFF) shl 8) or
                        (raw[base + 3 * tw + i].toInt() and 0xFF)
                    out[r * tw + i] = Float.fromBits(bits)
                }
            } else {
                for (i in 0 until tw) {
                    val at = base + i * 4
                    val bits = (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8) or
                        ((raw[at + 2].toInt() and 0xFF) shl 16) or ((raw[at + 3].toInt() and 0xFF) shl 24)
                    out[r * tw + i] = Float.fromBits(bits)
                }
            }
        }
        return out
    }
}
