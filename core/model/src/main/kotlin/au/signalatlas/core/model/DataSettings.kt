package au.signalatlas.core.model

import java.util.concurrent.CopyOnWriteArrayList

/** The coverage map's quality setting. */
enum class CoverageQuality {
    /** Terrain is downloaded automatically on Wi-Fi (or any network when mobile data counts as unmetered). */
    BALANCED,

    /** Never downloads terrain or buildings; the fast model only. */
    QUICK,

    /** Terrain and buildings are downloaded automatically on any network. */
    DETAILED,
}

/**
 * The user's data settings, read wherever a download decides whether it may use the current network. The app loads
 * them from its preferences at start-up and on every change ([update]); listeners re-evaluate their network rules.
 */
object DataSettings {
    @Volatile var quality: CoverageQuality = CoverageQuality.BALANCED
        private set

    /** "Treat mobile data as unmetered": background downloads ignore whether the network is metered. */
    @Volatile var mobileDataUnmetered: Boolean = false
        private set

    /** Tower radiation patterns on the map account for terrain, buildings and antenna height (and packs include it). */
    @Volatile var patternTerrain: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Terrain and buildings may be fetched automatically on a metered network. */
    val terrainOnAnyNetwork: Boolean get() = mobileDataUnmetered || quality == CoverageQuality.DETAILED

    fun update(
        quality: CoverageQuality = this.quality,
        mobileDataUnmetered: Boolean = this.mobileDataUnmetered,
        patternTerrain: Boolean = this.patternTerrain,
    ) {
        if (quality == this.quality && mobileDataUnmetered == this.mobileDataUnmetered && patternTerrain == this.patternTerrain) return
        this.quality = quality
        this.mobileDataUnmetered = mobileDataUnmetered
        this.patternTerrain = patternTerrain
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }
}
