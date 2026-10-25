package au.signalatlas.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.data.DataConfig
import au.signalatlas.core.model.CoverageQuality

/** Where the settings screen's links go. */
class SettingsLinks(
    val onTrip: () -> Unit,
    val onCalibration: () -> Unit,
    val onStorage: () -> Unit,
    val onOffline: () -> Unit,
    val onTowerData: () -> Unit,
)

/** ⋮ → Settings: coverage quality, tower pattern detail, mobile data, and the other settings pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: DataConfig,
    onConfig: ((DataConfig) -> DataConfig) -> Unit,
    links: SettingsLinks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Header(R.string.settings_coverage_quality)
                Group(Modifier.selectableGroup()) {
                    QualityRow(CoverageQuality.BALANCED, R.string.quality_balanced, R.string.quality_balanced_hint, config, onConfig)
                    Divider()
                    QualityRow(CoverageQuality.QUICK, R.string.quality_quick, R.string.quality_quick_hint, config, onConfig)
                    Divider()
                    QualityRow(CoverageQuality.DETAILED, R.string.quality_detailed, R.string.quality_detailed_hint, config, onConfig)
                }
                Header(R.string.settings_patterns)
                Group {
                    SwitchRow(R.string.pattern_terrain, R.string.pattern_terrain_hint, config.patternTerrain, "pattern-terrain") { on ->
                        onConfig { it.copy(patternTerrain = on) }
                    }
                }
                Header(R.string.settings_data)
                Group {
                    SwitchRow(R.string.mobile_unmetered, R.string.mobile_unmetered_hint, config.mobileDataUnmetered, "mobile-unmetered") { on ->
                        onConfig { it.copy(mobileDataUnmetered = on) }
                    }
                }
                Header(R.string.settings_more)
                Group {
                    LinkRow(R.string.menu_tower_data, links.onTowerData)
                    Divider()
                    LinkRow(R.string.menu_offline_regions, links.onOffline)
                    Divider()
                    LinkRow(R.string.settings_trip, links.onTrip)
                    Divider()
                    LinkRow(R.string.settings_calibration, links.onCalibration)
                    Divider()
                    LinkRow(R.string.settings_storage, links.onStorage)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Header(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun TwoLines(@StringRes title: Int, @StringRes hint: Int?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QualityRow(
    quality: CoverageQuality,
    @StringRes title: Int,
    @StringRes hint: Int,
    config: DataConfig,
    onConfig: ((DataConfig) -> DataConfig) -> Unit,
) {
    val selected = config.quality == quality
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, role = Role.RadioButton) { onConfig { it.copy(quality = quality) } }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("quality-${quality.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        TwoLines(title, hint, Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TwoLines(title, hint, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LinkRow(@StringRes title: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TwoLines(title, null, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
