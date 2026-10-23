package au.signalatlas.core.geo

import android.content.Context
import android.net.ConnectivityManager

/** Settings of the elevation cache. */
class GeoPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("geo_prefs", Context.MODE_PRIVATE)

    /** Cache budget in bytes: one of [BUDGET_OPTIONS_MIB] (MiB), 2 GiB by default. */
    val cacheBudgetBytes: Long get() = cacheBudgetMib * MIB

    var cacheBudgetMib: Int
        get() = prefs.getInt(KEY_BUDGET_MIB, DEFAULT_BUDGET_MIB).takeIf { it in BUDGET_OPTIONS_MIB } ?: DEFAULT_BUDGET_MIB
        set(value) {
            require(value in BUDGET_OPTIONS_MIB) { "Budget must be one of $BUDGET_OPTIONS_MIB MiB" }
            prefs.edit().putInt(KEY_BUDGET_MIB, value).apply()
        }

    companion object {
        /** 0.5 / 1 / 2 / 4 GiB. */
        val BUDGET_OPTIONS_MIB = listOf(512, 1024, 2048, 4096)
        const val DEFAULT_BUDGET_MIB = 2048
        private const val MIB = 1024L * 1024
        private const val KEY_BUDGET_MIB = "cache_budget_mib"
    }
}

object GeoNetwork {
    /** True when the active network is metered, or when that cannot be determined (the safe answer). */
    fun isActiveNetworkMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm == null || cm.isActiveNetworkMetered
    } catch (_: SecurityException) {
        true
    }
}
