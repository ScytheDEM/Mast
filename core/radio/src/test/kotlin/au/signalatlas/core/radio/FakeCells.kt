package au.signalatlas.core.radio

import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr

/** CellInfo constructors are @hide, so the fakes are built reflectively (Robolectric SDK 35 signatures). */
object FakeCells {
    const val NA = Int.MAX_VALUE // CellInfo.UNAVAILABLE

    private fun <T> build(cls: Class<T>, vararg args: Any?): T {
        val ctor = cls.declaredConstructors.first { it.parameterCount == args.size && it.parameterTypes[0] != cls }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args) as T
    }

    fun lte(
        registered: Boolean,
        earfcn: Int = 1300, pci: Int = 100, eci: Int = 1_234_567, tac: Int = 4000,
        bands: IntArray = intArrayOf(),
        rsrp: Int = -95, rsrq: Int = -10, rssnr: Int = 12, ta: Int = NA,
    ): CellInfo {
        val id = build(
            CellIdentityLte::class.java, eci, pci, tac, earfcn, bands, NA, "505", "01", "Telstra", "Telstra",
            emptyList<String>(), null,
        )
        val ss = build(CellSignalStrengthLte::class.java, -70, rsrp, rsrq, rssnr, NA, ta)
        return build(CellInfoLte::class.java, CellInfo.CONNECTION_NONE, registered, 0L, id, ss, null)
    }

    fun nr(
        registered: Boolean,
        nrarfcn: Int = 636666, pci: Int = NA, nci: Long = Long.MAX_VALUE, tac: Int = NA,
        bands: IntArray = intArrayOf(),
        ssRsrp: Int = -100, ssRsrq: Int = -11, ssSinr: Int = 8,
    ): CellInfo {
        val id = build(
            CellIdentityNr::class.java, pci, tac, nrarfcn, bands, "505", "01", nci, "Telstra", "Telstra",
            emptyList<String>(),
        )
        val ss = build(
            CellSignalStrengthNr::class.java, NA, NA, NA, 0, emptyList<Int>(), ssRsrp, ssRsrq, ssSinr, NA,
        )
        return build(CellInfoNr::class.java, CellInfo.CONNECTION_NONE, registered, 0L, id, ss)
    }
}
