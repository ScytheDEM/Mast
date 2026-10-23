package au.signalatlas.core.map

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/** Hands MapLibre the app's HTTP stack. */
object MapHttp {
    private const val TAG = "MapHttp"

    @Volatile
    private var client: OkHttpClient? = null

    /**
     * Registers the client MapLibre fetches every tile and style resource through. Pure bookkeeping (no native
     * code), so it is safe at app start; it takes effect when the first map is created, before its first request.
     */
    fun install(client: OkHttpClient) {
        this.client = client
    }

    /** Initialises MapLibre, then applies the registered client (MapLibre's HTTP layer refuses one before that). */
    internal fun initMapLibre(context: Context) {
        MapLibre.getInstance(context.applicationContext)
        val c = client
        if (c == null) Log.w(TAG, "No OkHttp client installed: coverage tiles will not load")
        else HttpRequestUtil.setOkHttpClient(c)
    }
}
