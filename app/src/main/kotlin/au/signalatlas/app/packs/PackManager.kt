package au.signalatlas.app.packs

import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackContentSource
import au.signalatlas.core.geo.packs.PackDef
import au.signalatlas.core.geo.packs.PackKind

/** Runs a pack's download in the background (WorkManager in the app). */
interface PackScheduler {
    /** Schedules pack [packId]'s download, replacing a scheduled one (e.g. to switch to mobile data). */
    fun enqueue(packId: String, allowMetered: Boolean)
    fun cancel(packId: String)
}

data class PackEstimate(val basemapBytes: Long, val elevationBytes: Long) {
    val totalBytes: Long get() = basemapBytes + elevationBytes
}

/** What the Offline regions screen does: size, download, update and delete packs. */
class PackManager(
    val repo: PackRepository,
    private val sources: Map<String, PackContentSource>,
    private val scheduler: PackScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The download size shown before starting; elevation already on the device is not counted. */
    suspend fun estimate(area: PackArea): PackEstimate = PackEstimate(
        basemapBytes = sources[BASEMAP]?.estimateBytes(area) ?: 0L,
        elevationBytes = sources[ELEVATION]?.estimateBytes(area) ?: 0L,
    )

    /** Starts downloading [def]; a pack that is already present is left alone (use [update]). */
    fun download(def: PackDef, allowMetered: Boolean) {
        if (repo.get(def.id) != null) return
        repo.put(
            PackRecord(
                id = def.id, name = def.name, kind = def.kind, area = def.area, state = PackState.QUEUED,
                generation = clock(), allowMetered = allowMetered,
            ),
        )
        scheduler.enqueue(def.id, allowMetered)
    }

    /** A user-drawn box. */
    fun downloadCustom(name: String, area: PackArea.Box, allowMetered: Boolean): PackDef {
        val def = PackDef("custom-${clock()}", name, PackKind.CUSTOM, area)
        download(def, allowMetered)
        return def
    }

    /** Fetches the pack again (a new base map build; elevation already held is kept). Also retries a failed pack. */
    fun update(packId: String, allowMetered: Boolean) {
        val pack = repo.get(packId) ?: return
        repo.update(packId) { it.copy(state = PackState.QUEUED, progress = 0f, generation = clock(), allowMetered = allowMetered, error = null) }
        scheduler.enqueue(pack.id, allowMetered)
    }

    /** Stops a running download and releases the pack's data (elevation chunks become cache again). */
    suspend fun delete(packId: String) {
        val pack = repo.get(packId) ?: return
        scheduler.cancel(packId)
        repo.remove(packId)
        for (s in sources.values) s.remove(packId, pack.area)
    }

    companion object {
        const val BASEMAP = "basemap"
        const val ELEVATION = "dem"
    }
}
