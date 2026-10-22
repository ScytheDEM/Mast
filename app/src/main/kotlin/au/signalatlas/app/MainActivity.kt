package au.signalatlas.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import au.signalatlas.app.ui.AppScaffold
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.map.HighRefreshRate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        HighRefreshRate.apply(this)
        val container = (application as SignalAtlasApp).container
        lifecycleScope.launch { container.onAppOpen() }
        setContent {
            SignalAtlasTheme {
                AppScaffold(container)
            }
        }
    }

    /** Fold/unfold is handled in place (configChanges); the new display may offer other modes. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        HighRefreshRate.apply(this)
    }
}
