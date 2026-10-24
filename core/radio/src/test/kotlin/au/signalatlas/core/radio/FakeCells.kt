package au.signalatlas.core.radio

import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr

/** CellInfo constructors are @hide, so the fakes are built reflectively (Robolectric SDK 35 signatures; API 29-30 variants below). */
object FakeCells {
    const val NA = Int.MAX_VALUE // CellInfo.UNAVAILABLE

    private fun <T> build(cls: Class<T>, vararg args: Any?): T {
        val ctor = cls.declaredConstructors.first { it.parameterCount == args.size && it.parameterTypes[0] != cls }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args) as T
    }

    private fun legacy() = Build.VERSION.SDK_INT < 31

    /** CellInfo{Lte,Nr} have no usable constructor before API 31: an empty one filled through its setter and fields. */
    private fun <T : CellInfo> legacyInfo(cls: Class<T>, id: Any, ss: Any, registered: Boolean): T {
        val info = cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        info.javaClass.superclass.getDeclaredMethod("setRegistered", Boolean::class.javaPrimitiveType).invoke(info, registered)
        for (v in listOf(id, ss)) {
            val f = cls.declaredFields.first { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == v.javaClass }
            f.isAccessible = true
            f.set(info, v)
        }
        return info
    }

    fun lte(
        registered: Boolean,
        earfcn: Int = 1300, pci: Int = 100, eci: Int = 1_234_567, tac: Int = 4000,
        bands: IntArray = intArrayOf(),
        rsrp: Int = -95, rsrq: Int = -10, rssnr: Int = 12, ta: Int = NA,
    ): CellInfo {
        val id = when {
            Build.VERSION.SDK_INT >= 30 -> build(
                CellIdentityLte::class.java, eci, pci, tac, earfcn, bands, NA, "505", "01", "Telstra", "Telstra",
                emptyList<String>(), null,
            )
            else -> build(CellIdentityLte::class.java, eci, pci, tac, earfcn, NA, "505", "01", "Telstra", "Telstra") // API 29
        }
        val ss = build(CellSignalStrengthLte::class.java, -70, rsrp, rsrq, rssnr, NA, ta)
        if (legacy()) return legacyInfo(CellInfoLte::class.java, id, ss, registered)
        return build(CellInfoLte::class.java, CellInfo.CONNECTION_NONE, registered, 0L, id, ss, null)
    }

    fun nr(
        registered: Boolean,
        nrarfcn: Int = 636666, pci: Int = NA, nci: Long = Long.MAX_VALUE, tac: Int = NA,
        bands: IntArray = intArrayOf(),
        ssRsrp: Int = -100, ssRsrq: Int = -11, ssSinr: Int = 8,
    ): CellInfo {
        val id = when {
            Build.VERSION.SDK_INT >= 30 -> build(
                CellIdentityNr::class.java, pci, tac, nrarfcn, bands, "505", "01", nci, "Telstra", "Telstra",
                emptyList<String>(),
            )
            else -> build(CellIdentityNr::class.java, pci, tac, nrarfcn, "505", "01", nci, "Telstra", "Telstra") // API 29
        }
        val ss = if (legacy()) {
            build(CellSignalStrengthNr::class.java, NA, NA, NA, ssRsrp, ssRsrq, ssSinr)
        } else {
            build(CellSignalStrengthNr::class.java, NA, NA, NA, 0, emptyList<Int>(), ssRsrp, ssRsrq, ssSinr, NA)
        }
        if (legacy()) return legacyInfo(CellInfoNr::class.java, id, ss, registered)
        return build(CellInfoNr::class.java, CellInfo.CONNECTION_NONE, registered, 0L, id, ss)
    }
}
