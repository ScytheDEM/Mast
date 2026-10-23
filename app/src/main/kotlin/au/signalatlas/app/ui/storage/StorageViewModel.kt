package au.signalatlas.app.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.data.Storage
import au.signalatlas.app.data.StorageSizes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageUiState(
    /** Null until the first measurement. */
    val sizes: StorageSizes? = null,
    val terrainBudgetMib: Int,
)

/** Settings → Storage: what is kept on the device, clearing the caches and the terrain cache limit. */
class StorageViewModel(
    private val storage: Storage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState(terrainBudgetMib = storage.terrainBudgetMib))
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    /** Measures again (file IO, off the main thread). */
    fun refresh() {
        viewModelScope.launch {
            val sizes = withContext(io) { storage.sizes() }
            _state.update { it.copy(sizes = sizes) }
        }
    }

    fun clearTerrain() = clear { storage.clearTerrain() }

    fun clearCoverageCache() = clear { storage.clearCoverageCache() }

    fun setTerrainBudgetMib(mib: Int) {
        viewModelScope.launch {
            // A lower limit evicts at once, which is file IO.
            val applied = withContext(io) {
                storage.terrainBudgetMib = mib
                storage.terrainBudgetMib
            }
            _state.update { it.copy(terrainBudgetMib = applied) }
            refresh()
        }
    }

    private fun clear(action: () -> Unit) {
        viewModelScope.launch {
            withContext(io) { action() }
            refresh()
        }
    }
}
