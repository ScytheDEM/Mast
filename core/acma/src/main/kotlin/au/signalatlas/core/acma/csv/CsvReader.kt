package au.signalatlas.core.acma.csv

import java.io.Closeable
import java.io.InputStream

class AcmaFormatException(message: String) : Exception(message)

/**
 * Streaming RFC 4180 CSV reader over raw bytes, decoding ISO-8859-1 (byte -> char).
 *
 * The first record is the header. Fields are accumulated in reused [StringBuilder]s, so the only
 * per-row allocations are the strings handed out by [get]. Not thread-safe.
 */
class CsvReader(private val input: InputStream, bufferSize: Int = 1 shl 16) : Closeable {
    private val buf = ByteArray(maxOf(bufferSize, 1))
    private var pos = 0
    private var limit = 0
    private var eof = false

    private var fields = Array(16) { StringBuilder() }
    private var count = 0
    private var recordNo = 0 // 1-based; the header is record 1

    val header: List<String>
    private val columnIndex: Map<String, Int>

    init {
        header = if (readRecord()) List(count) { fields[it].toString() } else emptyList()
        val index = HashMap<String, Int>(header.size * 2)
        header.forEachIndexed { i, name -> index.putIfAbsent(name, i) }
        columnIndex = index
        count = 0
    }

    fun column(name: String): Int =
        columnIndex[name] ?: throw AcmaFormatException("missing column $name in header $header")

    /** Advances to the next row; false at end of input. */
    fun next(): Boolean {
        if (readRecord()) return true
        count = 0
        return false
    }

    /** Field [col] of the current row; "" if the row has fewer fields. */
    operator fun get(col: Int): String = if (col in 0 until count) fields[col].toString() else ""

    override fun close() = input.close()

    private fun fill(): Boolean {
        if (eof) return false
        val n = input.read(buf, 0, buf.size)
        if (n <= 0) {
            eof = true
            return false
        }
        pos = 0
        limit = n
        return true
    }

    private fun StringBuilder.appendBounded(c: Int) {
        if (length >= MAX_FIELD_CHARS) {
            throw AcmaFormatException("field exceeds $MAX_FIELD_CHARS chars at record $recordNo")
        }
        append(c.toChar())
    }

    private fun startField(): StringBuilder {
        if (count == fields.size) {
            val old = fields
            fields = Array(old.size * 2) { if (it < old.size) old[it] else StringBuilder() }
        }
        return fields[count++].also { it.setLength(0) }
    }

    /** Reads one record into [fields]/[count]. False if the input is exhausted before any byte. */
    private fun readRecord(): Boolean {
        count = 0
        recordNo++
        var cur = startField()
        var inQuotes = false
        // A quote seen inside a quoted field; whether it closes the field or escapes depends on the next byte.
        var pendingQuote = false
        var atFieldStart = true
        var sawAny = false

        while (true) {
            if (pos == limit && !fill()) {
                if (!sawAny) {
                    count = 0
                    return false
                }
                if (inQuotes && !pendingQuote) {
                    throw AcmaFormatException("unterminated quoted field at record $recordNo")
                }
                return true
            }
            val c = (buf[pos++].toInt() and 0xFF)
            sawAny = true

            if (pendingQuote) {
                pendingQuote = false
                if (c == QUOTE) {
                    cur.appendBounded(QUOTE)
                    continue
                }
                inQuotes = false // the quote closed the field; fall through to handle c unquoted
            }

            if (inQuotes) {
                if (c == QUOTE) pendingQuote = true else cur.appendBounded(c)
                continue
            }

            when (c) {
                COMMA -> {
                    cur = startField()
                    atFieldStart = true
                }
                LF -> return true
                CR -> {} // CRLF terminator; the LF ends the record
                QUOTE -> if (atFieldStart) {
                    inQuotes = true
                    atFieldStart = false
                } else {
                    cur.appendBounded(QUOTE)
                }
                else -> {
                    cur.appendBounded(c)
                    atFieldStart = false
                }
            }
        }
    }

    companion object {
        /** Upper bound on a single field, in chars; guards against runaway quotes on a 388 MB file. */
        const val MAX_FIELD_CHARS = 1_048_576
        private const val COMMA = ','.code
        private const val QUOTE = '"'.code
        private const val LF = '\n'.code
        private const val CR = '\r'.code
    }
}
