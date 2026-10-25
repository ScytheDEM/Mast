@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package au.signalatlas.app.ui.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.packs.PackRecord
import au.signalatlas.app.packs.PackState
import au.signalatlas.app.ui.storage.formatSize
import au.signalatlas.core.geo.packs.PackCatalogue
import au.signalatlas.core.geo.packs.PackDef

/** Settings: the offline regions on the device, and a catalogue to add more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineRegionsScreen(
    state: OfflineUiState,
    onOffer: (PackDef) -> Unit,
    onDrawBox: () -> Unit,
    onUpdate: (PackRecord) -> Unit,
    onDelete: (String) -> Unit,
    onMobileData: (Boolean) -> Unit,
    onDismissOffer: () -> Unit,
    onConfirmOffer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalContext.current.resources
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val have = state.packs.map { it.id }.toSet()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.offline_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().navigationBarsPadding().testTag("offline-list")) {
            item {
                Text(
                    stringResource(R.string.offline_intro), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Header(R.string.offline_downloaded) }
            if (state.packs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.offline_none), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.packs, key = { "pack-" + it.id }) { pack ->
                PackRow(res, pack, onUpdate = { onUpdate(pack) }, onDelete = { deleting = pack.id })
                HorizontalDivider()
            }
            item { Header(R.string.offline_add) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.offline_draw)) },
                    modifier = Modifier.clickableRow(onDrawBox).testTag("offline-draw"),
                )
            }
            catalogue(R.string.offline_states, PackCatalogue.states, have, onOffer)
            catalogue(R.string.offline_metros, PackCatalogue.metros, have, onOffer)
            catalogue(R.string.offline_corridors, PackCatalogue.corridors, have, onOffer)
        }
    }

    deleting?.let { id ->
        val name = state.packs.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.offline_delete_title, name)) },
            text = { Text(stringResource(R.string.offline_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(id) }) { Text(stringResource(R.string.offline_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.offline_cancel)) } },
        )
    }
    state.offer?.let { OfferDialog(it, onMobileData, onDismissOffer, onConfirmOffer) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.catalogue(
    title: Int, defs: List<PackDef>, have: Set<String>, onOffer: (PackDef) -> Unit,
) {
    val todo = defs.filter { it.id !in have }
    if (todo.isEmpty()) return
    item { Header(title, small = true) }
    items(todo, key = { "def-" + it.id }) { def ->
        ListItem(
            headlineContent = { Text(def.name) },
            modifier = Modifier.clickableRow { onOffer(def) }.testTag("offer-" + def.id),
        )
    }
}

@Composable
private fun Header(title: Int, small: Boolean = false) {
    Text(
        stringResource(title),
        Modifier.padding(start = 16.dp, end = 16.dp, top = if (small) 12.dp else 20.dp, bottom = 4.dp),
        style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
        color = if (small) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun PackRow(res: android.content.res.Resources, pack: PackRecord, onUpdate: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pack-" + pack.id)) {
        Text(pack.name, style = MaterialTheme.typography.bodyLarge)
        val status = when (pack.state) {
            PackState.READY -> stringResource(R.string.offline_size_ready, formatSize(res, pack.bytes))
            PackState.DOWNLOADING -> stringResource(R.string.offline_status_downloading, (pack.progress * 100).toInt())
            PackState.QUEUED -> pack.error
                ?: stringResource(if (pack.allowMetered) R.string.offline_status_queued_mobile else R.string.offline_status_queued)
            PackState.FAILED -> stringResource(R.string.offline_status_failed, pack.error.orEmpty())
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pack.state == PackState.DOWNLOADING || pack.state == PackState.QUEUED) {
            LinearWavyProgressIndicator(progress = { pack.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (pack.state == PackState.READY || pack.state == PackState.FAILED) {
                TextButton(onClick = onUpdate) {
                    Text(stringResource(if (pack.state == PackState.FAILED) R.string.offline_retry else R.string.offline_update))
                }
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.offline_delete)) }
        }
    }
}

@Composable
private fun OfferDialog(offer: DownloadOffer, onMobileData: (Boolean) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val res = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_download_title, offer.def.name)) },
        text = {
            Column {
                val e = offer.estimate
                Text(
                    if (e == null) stringResource(R.string.offline_estimating)
                    else stringResource(
                        R.string.offline_estimate, formatSize(res, e.totalBytes), formatSize(res, e.basemapBytes), formatSize(res, e.elevationBytes),
                    ),
                    Modifier.testTag("offer-size"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(offer.allowMetered, onMobileData, Modifier.testTag("offer-mobile"))
                    Text(stringResource(R.string.offline_mobile_data))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = offer.estimate != null) { Text(stringResource(R.string.offline_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.offline_cancel)) } },
    )
}
