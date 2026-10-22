package au.signalatlas.core.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Finds the newest Protomaps daily build (the last entry of builds.json), remembering it so the
 * map works offline and builds.json is fetched at most once every 7 days.
 */
class BasemapLocator(
    private val http: OkHttpClient,
    private val prefs: DataStore<Preferences>,
    private val buildsUrl: String = BUILDS_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The cached key, refreshed when older than 7 days; null only if never fetched and offline. */
    suspend fun currentKey(): String? {
        val p = prefs.data.first()
        val cached = p[KEY]
        val fetchedAt = p[FETCHED_AT]
        val now = clock()
        if (cached != null && fetchedAt != null && now - fetchedAt in 0 until MAX_AGE_MS) return cached
        val fresh = fetchLatest() ?: return cached
        prefs.edit {
            it[KEY] = fresh
            it[FETCHED_AT] = now
        }
        return fresh
    }

    /** The last key in builds.json, or null on any network, HTTP or format failure. */
    private suspend fun fetchLatest(): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(buildsUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val key = Json.parseToJsonElement(response.body.string()).jsonArray
                    .lastOrNull()?.jsonObject?.get("key")?.jsonPrimitive?.content
                key?.takeIf { KEY_PATTERN.matches(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, timeout or a malformed document: keep whatever is cached.
            null
        }
    }

    companion object {
        const val BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
        /** Keys become a URL path segment, so accept only plain file names such as `20261022.pmtiles`. */
        private val KEY_PATTERN = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.pmtiles")
        private val KEY = stringPreferencesKey("basemap_key")
        private val FETCHED_AT = longPreferencesKey("basemap_fetched_at")
    }
}
