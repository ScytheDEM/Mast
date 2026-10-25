package au.signalatlas.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.packs.PackEstimate
import au.signalatlas.app.packs.PackManager
import au.signalatlas.app.packs.PackRecord
import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackDef
import au.signalatlas.core.geo.packs.PackKind
import au.signalatlas.core.model.GeoBounds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pack the user is about to download: its size (null while it is being worked out) and the mobile data choice. */
data class DownloadOffer(val def: PackDef, val estimate: PackEstimate? = null, val allowMetered: Boolean = false)

data class OfflineUiState(
    val packs: List<PackRecord> = emptyList(),
    val offer: DownloadOffer? = null,
)

class OfflineViewModel(
    private val manager: PackManager,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val offer = MutableStateFlow<DownloadOffer?>(null)
    private var estimating: Job? = null

    val state: StateFlow<OfflineUiState> = combine(manager.repo.packs, offer) { packs, o -> OfflineUiState(packs, o) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OfflineUiState(manager.repo.packs.value))

    /** Shows the size of [def] and the Download button. */
    fun offer(def: PackDef) {
        offer.value = DownloadOffer(def)
        estimating?.cancel()
        estimating = viewModelScope.launch {
            val e = withContext(io) { manager.estimate(def.area) }
            offer.update { o -> o?.takeIf { it.def == def }?.copy(estimate = e) ?: o }
        }
    }

    fun offerCustom(box: GeoBounds, name: String) =
        offer(PackDef("custom-${clock()}", name, PackKind.CUSTOM, PackArea.Box(box)))

    fun setAllowMetered(on: Boolean) = offer.update { it?.copy(allowMetered = on) }

    fun dismissOffer() {
        estimating?.cancel()
        offer.value = null
    }

    fun confirmOffer() {
        val o = offer.value ?: return
        manager.download(o.def, o.allowMetered)
        dismissOffer()
    }

    fun update(packId: String, allowMetered: Boolean = false) = manager.update(packId, allowMetered)

    fun delete(packId: String) {
        viewModelScope.launch { withContext(io) { manager.delete(packId) } }
    }
}
