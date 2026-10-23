package au.signalatlas.core.coverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.geo.DemFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where missing elevation chunks come from; [DemFetcherSource] in the app, a fake in tests. */
interface ElevationSource {
    /** Bytes [fetch] would download for the chunks of [chunks] not stored yet. */
    suspend fun estimateBytes(chunks: Set<ChunkId>): Long

    /**
     * Stores every chunk of [chunks]. Throws `MeteredNetworkException` on a metered network unless [allowMetered],
     * `InsufficientStorageException` when the free space is short, `IOException` on network trouble.
     */
    suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit)
}

/** [ElevationSource] over the geo module's [DemFetcher] (share one instance: its fetches are serialised process-wide). */
class DemFetcherSource(private val fetcher: DemFetcher) : ElevationSource {
    override suspend fun estimateBytes(chunks: Set<ChunkId>) = fetcher.estimateBytes(chunks)
    override suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) =
        fetcher.fetch(chunks, allowMetered, progress)
}

/** The device's connectivity as the elevation downloads need it. */
interface NetworkMonitor {
    /** True while the default network is up and not metered (what background auto-caching waits for). */
    val unmetered: StateFlow<Boolean>
    fun isOnline(): Boolean
    fun isMetered(): Boolean
}

/** [NetworkMonitor] following the default network through a [ConnectivityManager] callback (needs ACCESS_NETWORK_STATE). */
class ConnectivityNetworkMonitor(context: Context) : NetworkMonitor {
    private val _unmetered = MutableStateFlow(false)
    override val unmetered: StateFlow<Boolean> get() = _unmetered

    @Volatile private var online = false
    @Volatile private var metered = true

    init {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        update(
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                        )
                    }

                    override fun onLost(network: Network) = update(false, true)
                })
            } catch (_: SecurityException) {
                // No permission to observe the network: stays offline / metered, so nothing is fetched on its own.
            }
        }
    }

    private fun update(online: Boolean, metered: Boolean) {
        this.online = online
        this.metered = metered
        _unmetered.value = online && !metered
    }

    override fun isOnline() = online
    override fun isMetered() = metered
}

/**
 * What [CoverageTileService] needs for terrain: the elevation cache, its source, the table cache, the network and a
 * scope for background work. Without it the service renders with the quick model only.
 *
 * @param settleMs how long the camera must rest before auto-caching starts.
 */
class TerrainSupport(
    val chunks: DemChunkStore,
    val source: ElevationSource,
    val tables: TerrainTableStore,
    val network: NetworkMonitor,
    val scope: CoroutineScope,
    val settleMs: Long = 1_500,
)
