package au.signalatlas.core.beacons

/** One advertisement understood as a beacon: its canonical [id] (see [BeaconIds]) and the measured [rssi] in dBm. */
data class BeaconSighting(val id: String, val rssi: Int, val timeMs: Long = 0L)

/** Canonical beacon identifiers, lower case: `ib:<uuid>:<major>:<minor>`, `eu:<namespace hex>:<instance hex>`, `eurl:<url>`. */
object BeaconIds {
    fun iBeacon(uuid: String, major: Int, minor: Int) = "ib:${uuid.lowercase()}:$major:$minor"
    fun eddystoneUid(namespaceHex: String, instanceHex: String) = "eu:${namespaceHex.lowercase()}:${instanceHex.lowercase()}"
    fun eddystoneUrl(url: String) = "eurl:$url"

    private val UUID = Regex("[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}")

    /**
     * Canonical form of a user-supplied id (CSV import): the canonical forms themselves; `uuid:major:minor` (or with `-`
     * between the three parts) as iBeacon; 32 hex digits (namespace + instance) as Eddystone-UID. Null when unrecognised.
     */
    fun normalise(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.startsWith("eurl:") && s.length > 5) return s
        s.removePrefix("ib:").let { body ->
            val parts = body.split(':')
            if (parts.size == 3 && UUID.matches(parts[0])) {
                val major = parts[1].toIntOrNull()?.takeIf { it in 0..65535 }
                val minor = parts[2].toIntOrNull()?.takeIf { it in 0..65535 }
                if (major != null && minor != null) return iBeacon(dashed(parts[0]), major, minor)
            }
        }
        s.removePrefix("eu:").let { body ->
            val parts = body.split(':')
            if (parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{20}")) && parts[1].matches(Regex("[0-9a-f]{12}"))) {
                return eddystoneUid(parts[0], parts[1])
            }
            if (parts.size == 1 && parts[0].matches(Regex("[0-9a-f]{32}"))) return eddystoneUid(parts[0].take(20), parts[0].drop(20))
        }
        // "uuid-major-minor": the UUID has 4 dashes, so split from the right.
        val m = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(\\d{1,5})-(\\d{1,5})").matchEntire(s)
        if (m != null) {
            val major = m.groupValues[2].toInt()
            val minor = m.groupValues[3].toInt()
            if (major <= 65535 && minor <= 65535) return iBeacon(m.groupValues[1], major, minor)
        }
        return null
    }

    private fun dashed(u: String): String {
        val h = u.replace("-", "")
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

/** Parses the raw advertisement bytes (a sequence of length / type / data AD structures) of iBeacon and Eddystone-UID / URL. */
object BeaconParser {
    private const val AD_SERVICE_DATA_16 = 0x16
    private const val AD_MANUFACTURER = 0xFF
    private const val APPLE_COMPANY_ID = 0x004C
    private const val EDDYSTONE_SERVICE = 0xFEAA

    private val URL_SCHEMES = listOf("http://www.", "https://www.", "http://", "https://")
    private val URL_EXPANSIONS = listOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov",
    )

    /** The beacon in [record], or null when it holds none (or a malformed one). */
    fun parse(record: ByteArray, rssi: Int, timeMs: Long = 0L): BeaconSighting? {
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > record.size) break
            val type = record[i + 1].toInt() and 0xFF
            val data = record.copyOfRange(i + 2, i + 1 + len)
            val id = when (type) {
                AD_MANUFACTURER -> iBeacon(data)
                AD_SERVICE_DATA_16 -> eddystone(data)
                else -> null
            }
            if (id != null) return BeaconSighting(id, rssi, timeMs)
            i += 1 + len
        }
        return null
    }

    /** data = company id (LE, 2) + 0x02 0x15 + UUID (16) + major (2, BE) + minor (2, BE) + measured power (1). */
    private fun iBeacon(d: ByteArray): String? {
        if (d.size < 25) return null
        val company = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
        if (company != APPLE_COMPANY_ID || d[2].toInt() != 0x02 || d[3].toInt() != 0x15) return null
        val uuid = hex(d, 4, 16).let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
        val major = ((d[20].toInt() and 0xFF) shl 8) or (d[21].toInt() and 0xFF)
        val minor = ((d[22].toInt() and 0xFF) shl 8) or (d[23].toInt() and 0xFF)
        return BeaconIds.iBeacon(uuid, major, minor)
    }

    /** data = service UUID 0xFEAA (LE, 2) + frame type + frame; UID 0x00: tx power, namespace (10), instance (6); URL 0x10: tx power, scheme, url. */
    private fun eddystone(d: ByteArray): String? {
        if (d.size < 4) return null
        val service = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
        if (service != EDDYSTONE_SERVICE) return null
        return when (d[2].toInt() and 0xFF) {
            0x00 -> if (d.size >= 20) BeaconIds.eddystoneUid(hex(d, 4, 10), hex(d, 14, 6)) else null
            0x10 -> {
                if (d.size < 6) return null
                val scheme = URL_SCHEMES.getOrNull(d[4].toInt() and 0xFF) ?: return null
                val sb = StringBuilder(scheme)
                for (k in 5 until d.size) {
                    val b = d[k].toInt() and 0xFF
                    if (b < URL_EXPANSIONS.size) sb.append(URL_EXPANSIONS[b]) else if (b in 0x21..0x7E) sb.append(b.toChar()) else return null
                }
                BeaconIds.eddystoneUrl(sb.toString())
            }
            else -> null
        }
    }

    private fun hex(b: ByteArray, from: Int, n: Int): String {
        val sb = StringBuilder(n * 2)
        for (k in from until from + n) sb.append("%02x".format(b[k].toInt() and 0xFF))
        return sb.toString()
    }
}
