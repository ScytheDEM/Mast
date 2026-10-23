package au.signalatlas.core.acma.hrp

import java.io.InputStream

/** The HRP CSV is not in the shape the importer relies on (e.g. a changed header). */
class HrpFormatException(message: String) : Exception(message)

/**
 * Byte-level reader of ACMA's `LICENCE_.csv` (horizontal radiation patterns), about 330 million
 * rows. It allocates nothing per row: fields are parsed by position straight from one read buffer.
 *
 * The header must equal [HEADER] exactly (else [HrpFormatException]). For each row [next] sets:
 * - [sddId]: field 1 as an ASCII decimal integer (surrounding whitespace allowed, ≤ 2^63−1), or
 *   [INVALID_SDD] when unparseable;
 * - [startAngle], [stopAngle], [power]: fields 3–5 as decimal numbers (`[+-]digits[.digits]`,
 *   whitespace allowed; the half-degree grid has `0.5`), each NaN when unparseable;
 * - [valid]: the row has exactly five fields and all three numbers parse.
 *
 * Quoting follows Python's `csv` defaults (a field opening with `"` is quoted, `""` is a literal
 * quote), so a quoted identifier cannot shift the field positions. A malformed row never throws;
 * it is just not [valid]. Rows end at LF, CR or CRLF.
 */
class HrpStreamParser(private val input: InputStream) {
    private val buf = ByteArray(BUFFER_BYTES)
    private var pos = 0
    private var limit = 0
    private var eof = false
    private var headerRead = false

    var sddId: Long = INVALID_SDD
        private set
    /** START_ANGLE in degrees (the interval is `[startAngle, stopAngle)`), or NaN. */
    var startAngle: Double = Double.NaN
        private set
    var stopAngle: Double = Double.NaN
        private set
    /** POWER (nominally dBW), parsed exactly as Python's `float()` (correctly rounded double), or NaN. */
    var power: Double = Double.NaN
        private set
    val powerDbw: Float get() = power.toFloat()
    var valid: Boolean = false
        private set

    // Per-row field accumulators: SDD_ID digits, and the raw bytes of fields 3–5.
    private var sddState = LEAD
    private var sddValue = 0L
    private val numText = Array(3) { ByteArray(MAX_NUMBER_BYTES) }
    private val numLen = IntArray(3)

    /** Advances to the next data row; false at end of input. */
    fun next(): Boolean {
        if (!headerRead) readHeader()
        var b = readByte()
        if (b < 0) return false

        sddState = LEAD
        sddValue = 0L
        numLen.fill(0)
        var field = 0
        var fieldStart = true
        var quoted = false
        while (b >= 0) {
            if (quoted) {
                if (b == QUOTE) {
                    val after = readByte()
                    if (after == QUOTE) {
                        feed(field, QUOTE) // "" inside quotes
                        b = readByte()
                    } else {
                        quoted = false
                        b = after
                    }
                    continue
                }
                feed(field, b)
                b = readByte()
                continue
            }
            if (b == COMMA) {
                field++
                fieldStart = true
                b = readByte()
                continue
            }
            if (b == LF) break
            if (b == CR) {
                if ((pos < limit || fill()) && buf[pos].toInt() == LF) pos++
                break
            }
            if (b == QUOTE && fieldStart) {
                quoted = true
            } else {
                feed(field, b)
            }
            fieldStart = false
            b = readByte()
        }

        sddId = if (sddState == DIGITS || sddState == TRAIL) sddValue else INVALID_SDD
        startAngle = parseNumber(0)
        stopAngle = parseNumber(1)
        power = parseNumber(2)
        valid = field == 4 && !startAngle.isNaN() && !stopAngle.isNaN() && !power.isNaN()
        return true
    }

    private fun feed(field: Int, b: Int) {
        when (field) {
            0 -> feedSdd(b)
            2, 3, 4 -> {
                val i = field - 2
                val n = numLen[i]
                if (n < MAX_NUMBER_BYTES) {
                    numText[i][n] = b.toByte()
                    numLen[i] = n + 1
                } else {
                    numLen[i] = TOO_LONG
                }
            }
        }
    }

    private fun feedSdd(b: Int) {
        val d = b - '0'.code
        when (sddState) {
            LEAD, DIGITS -> when {
                d in 0..9 -> if (sddValue > (Long.MAX_VALUE - d) / 10) {
                    sddState = BAD
                } else {
                    sddValue = sddValue * 10 + d
                    sddState = DIGITS
                }
                isSpace(b) -> if (sddState == DIGITS) sddState = TRAIL
                else -> sddState = BAD
            }
            TRAIL -> if (!isSpace(b)) sddState = BAD
        }
    }

    /**
     * Field `3 + i` as `[+-]?digits*(.digits*)?` with at least one digit, surrounded by optional
     * whitespace; else NaN.
     */
    private fun parseNumber(field: Int): Double {
        val text = numText[field]
        var end = numLen[field]
        if (end <= 0 || end == TOO_LONG) return Double.NaN
        var i = 0
        while (i < end && isSpace(text[i].toInt() and 0xFF)) i++
        while (end > i && isSpace(text[end - 1].toInt() and 0xFF)) end--
        if (i == end) return Double.NaN
        val start = i
        var negative = false
        val sign = text[i].toInt()
        if (sign == '-'.code || sign == '+'.code) {
            negative = sign == '-'.code
            i++
        }
        var mantissa = 0L
        var digits = 0
        var fraction = 0
        var dot = false
        while (i < end) {
            val c = text[i].toInt()
            if (c == '.'.code && !dot) {
                dot = true
            } else if (c in '0'.code..'9'.code) {
                if (digits < MAX_EXACT_DIGITS) mantissa = mantissa * 10 + (c - '0'.code)
                digits++
                if (dot) fraction++
            } else {
                return Double.NaN
            }
            i++
        }
        if (digits == 0) return Double.NaN
        if (digits > MAX_EXACT_DIGITS) {
            // Not exactly representable by the fast path (never in ACMA data): let the JDK round it.
            return String(text, start, end - start, Charsets.ISO_8859_1).toDouble()
        }
        // Both operands are exact doubles, so the IEEE quotient is the correctly rounded value.
        val v = mantissa.toDouble() / POW10[fraction]
        return if (negative) -v else v
    }

    private fun readHeader() {
        headerRead = true
        val line = ByteArray(HEADER_BYTES.size + 2)
        var n = 0
        while (true) {
            val b = readByte()
            if (b < 0 || b == LF) break
            if (b == CR) {
                if ((pos < limit || fill()) && buf[pos].toInt() == LF) pos++
                break
            }
            if (n == line.size) throw HrpFormatException("HRP header too long")
            line[n++] = b.toByte()
        }
        if (n != HEADER_BYTES.size || !HEADER_BYTES.indices.all { line[it] == HEADER_BYTES[it] }) {
            throw HrpFormatException("unexpected HRP header: ${String(line, 0, n, Charsets.ISO_8859_1)}")
        }
    }

    private fun readByte(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    private fun fill(): Boolean {
        if (eof) return false
        while (true) {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) {
                eof = true
                return false
            }
            if (n > 0) {
                pos = 0
                limit = n
                return true
            }
        }
    }

    companion object {
        const val HEADER = "SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER"
        /** [sddId] of a row whose SDD_ID does not parse (real IDs are ≥ 0). */
        const val INVALID_SDD = -1L

        private val HEADER_BYTES = HEADER.toByteArray(Charsets.ISO_8859_1)
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_NUMBER_BYTES = 64
        /** [numLen] of a numeric field longer than [MAX_NUMBER_BYTES] (rejected). */
        private const val TOO_LONG = MAX_NUMBER_BYTES + 1
        /** Up to 15 digits the mantissa is below 2^53 and therefore exact. */
        private const val MAX_EXACT_DIGITS = 15
        private val POW10 = DoubleArray(MAX_EXACT_DIGITS + 1) { Math.pow(10.0, it.toDouble()) } // exact (≤ 1e22)

        private const val COMMA = ','.code
        private const val QUOTE = '"'.code
        private const val LF = '\n'.code
        private const val CR = '\r'.code

        private const val LEAD = 0
        private const val DIGITS = 1
        private const val TRAIL = 2
        private const val BAD = 3

        /** Python's `str.strip()` whitespace over ISO-8859-1 bytes. */
        private fun isSpace(b: Int): Boolean =
            b == 0x20 || b in 0x09..0x0D || b in 0x1C..0x1F || b == 0x85 || b == 0xA0
    }
}
