package au.signalatlas.core.acma.csv

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvReaderTest {
    private fun reader(text: String, bufferSize: Int = 1 shl 16) =
        CsvReader(text.toByteArray(Charsets.ISO_8859_1).inputStream(), bufferSize)

    @Test
    fun plainRow() {
        reader("A,B,C\n1,2,3\n").use { r ->
            assertThat(r.header).containsExactly("A", "B", "C").inOrder()
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("1")
            assertThat(r[r.column("C")]).isEqualTo("3")
            assertThat(r.next()).isFalse()
        }
    }

    @Test
    fun quotedCommaAndEscapedQuote() {
        reader("A,B\n\"a,\"\"b\"\"\",x\n").use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("a,\"b\"")
            assertThat(r[1]).isEqualTo("x")
        }
    }

    @Test
    fun quotedEmbeddedNewline() {
        reader("A,B\r\n\"line1\r\nline2\",y\r\nz,w\r\n").use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("line1\r\nline2")
            assertThat(r[1]).isEqualTo("y")
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("z")
            assertThat(r.next()).isFalse()
        }
    }

    @Test
    fun crlfAndLfEndings() {
        for (nl in listOf("\r\n", "\n")) {
            reader("A,B${nl}1,2${nl}3,4${nl}").use { r ->
                assertThat(r.header).containsExactly("A", "B").inOrder()
                assertThat(r.next()).isTrue()
                assertThat(r[1]).isEqualTo("2")
                assertThat(r.next()).isTrue()
                assertThat(r[1]).isEqualTo("4")
                assertThat(r.next()).isFalse()
            }
        }
    }

    @Test
    fun finalLineWithoutNewlineIsARow() {
        reader("A,B\n1,2").use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[1]).isEqualTo("2")
            assertThat(r.next()).isFalse()
        }
    }

    @Test
    fun emptyTrailingLineIsNotARow() {
        reader("A,B\n1,2\n").use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r.next()).isFalse()
        }
    }

    @Test
    fun emptyFieldAndShortRow() {
        reader("A,B,C\n1,,3\n4\n").use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[1]).isEqualTo("")
            assertThat(r[2]).isEqualTo("3")
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("4")
            assertThat(r[1]).isEqualTo("")
            assertThat(r[2]).isEqualTo("")
            assertThat(r[99]).isEqualTo("")
        }
    }

    @Test
    fun decodesIso88591() {
        val bytes = "A\n".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xE9.toByte(), '\n'.code.toByte())
        CsvReader(bytes.inputStream()).use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("é")
        }
    }

    @Test
    fun missingColumnThrows() {
        reader("A,B\n").use { r ->
            assertThat(r.column("B")).isEqualTo(1)
            val e = assertThrows(AcmaFormatException::class.java) { r.column("NOPE") }
            assertThat(e).hasMessageThat().contains("missing column NOPE")
        }
    }

    @Test
    fun recordsSpanningTinyBuffer() {
        reader("A,B\n\"x\"\"y\",2\r\n3,4\r\n", bufferSize = 2).use { r ->
            assertThat(r.next()).isTrue()
            assertThat(r[0]).isEqualTo("x\"y")
            assertThat(r.next()).isTrue()
            assertThat(r[1]).isEqualTo("4")
            assertThat(r.next()).isFalse()
        }
    }

    @Test
    fun parsesTwoHundredThousandRows() {
        val sb = StringBuilder("ID,NAME\n")
        for (i in 0 until 200_000) sb.append(i).append(",\"n,").append(i).append("\"\n")
        reader(sb.toString()).use { r ->
            var n = 0
            while (r.next()) {
                assertThat(r[0]).isEqualTo(n.toString())
                n++
            }
            assertThat(n).isEqualTo(200_000)
        }
    }
}
