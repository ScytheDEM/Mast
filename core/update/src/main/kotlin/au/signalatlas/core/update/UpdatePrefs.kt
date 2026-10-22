package au.signalatlas.core.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Remembers when ACMA was last checked and what it published then (Ruling P). Which data is
 * installed is never stored here: the live DB's own meta is the truth for that.
 */
class UpdatePrefs(private val store: DataStore<Preferences>) {
    /** Epoch millis of the last successful check, or null if never checked. */
    suspend fun lastCheckAt(): Long? = store.data.first()[LAST_CHECK_AT]

    /** What ACMA published at the last successful check, or null if never checked. */
    suspend fun lastRemote(): RemoteInfo? {
        val p = store.data.first()
        return RemoteInfo(
            extractStamp = p[REMOTE_STAMP] ?: return null,
            etag = p[REMOTE_ETAG] ?: return null,
            sizeBytes = p[REMOTE_SIZE] ?: return null,
        )
    }

    suspend fun recordCheck(atMillis: Long, info: RemoteInfo) {
        store.edit {
            it[LAST_CHECK_AT] = atMillis
            it[REMOTE_STAMP] = info.extractStamp
            it[REMOTE_ETAG] = info.etag
            it[REMOTE_SIZE] = info.sizeBytes
        }
    }

    companion object {
        private val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        private val REMOTE_STAMP = stringPreferencesKey("remote_extract_stamp")
        private val REMOTE_ETAG = stringPreferencesKey("remote_etag")
        private val REMOTE_SIZE = longPreferencesKey("remote_size_bytes")

        /**
         * Opens the prefs stored in [file] (must end in `.preferences_pb`). DataStore allows one
         * instance per file per process; it lives as long as [scope].
         */
        fun create(file: File, scope: CoroutineScope): UpdatePrefs =
            UpdatePrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }
}
