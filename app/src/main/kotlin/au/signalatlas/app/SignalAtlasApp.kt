package au.signalatlas.app

import android.app.Application
import androidx.work.Configuration
import au.signalatlas.core.update.TowerUpdateWorkerFactory

class SignalAtlasApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start loading the DB as early as possible; the UI waits on it rather than flashing first-run.
        container.startRepository()
    }

    /** WorkManager's default initializer is removed in the manifest so the worker gets its deps. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(TowerUpdateWorkerFactory(container.updateDeps))
            .build()
}
