package au.signalatlas.core.radio

/**
 * Modems report "unknown" as Int.MAX_VALUE / Long.MAX_VALUE (CellInfo.UNAVAILABLE) or junk outside the
 * spec range (Exynos, Tensor). Anything not provably valid becomes null, never a value.
 */
object Sentinels {
    fun rsrp(v: Int): Int? = v.takeIf { it in -140..-44 }
    fun rsrq(v: Int): Int? = v.takeIf { it in -34..3 }
    fun sinr(v: Int): Int? = v.takeIf { it in -23..40 }
    fun pciLte(v: Int): Int? = v.takeIf { it in 0..503 }
    fun pciNr(v: Int): Int? = v.takeIf { it in 0..1007 }
    fun earfcn(v: Int): Int? = v.takeIf { it in 0..262143 }
    fun nrarfcn(v: Int): Int? = v.takeIf { it in 0..ChannelTables.NR_ARFCN_MAX }
    fun tacLte(v: Int): Int? = v.takeIf { it in 0..65535 }
    fun tacNr(v: Int): Int? = v.takeIf { it in 0..16777215 }
    fun eci(v: Int): Long? = v.takeIf { it in 0..268435455 }?.toLong()
    fun nci(v: Long): Long? = v.takeIf { it in 0L..68719476735L }

    const val LTE_TA_METERS = 78.12

    /** LTE timing advance in 0..1282 to metres. */
    fun lteTaMeters(ta: Int): Double? = if (ta in 0..1282) ta * LTE_TA_METERS else null
}
