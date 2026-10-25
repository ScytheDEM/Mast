package au.signalatlas.app.packs

import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class PackState {
    /** Waiting for the network / a worker slot (also after a failed attempt that will be retried). */
    QUEUED,
    DOWNLOADING,
    READY,

    /** Gave up: [PackRecord.error] says why; the user can try again. */
    FAILED,
}

data class PackRecord(
    val id: String,
    val name: String,
    val kind: PackKind,
    val area: PackArea,
    val state: PackState,
    val progress: Float = 0f,
    /** On-disk size, known when [PackState.READY] (and refreshed while downloading). */
    val bytes: Long = 0L,
    /** Changes on every (re)download; sources use it to tell a stale copy from a download to resume. */
    val generation: Long,
    val allowMetered: Boolean = false,
    val error: String? = null,
    /** When the pack last became [PackState.READY]; 0 = never. */
    val updatedAt: Long = 0L,
)

/** The packs the user has asked for, as one JSON file (written atomically). Thread-safe. */
class PackRepository(private val file: File) {
    private val lock = Any()
    private val _packs = MutableStateFlow(load())
    val packs: StateFlow<List<PackRecord>> = _packs.asStateFlow()

    fun get(id: String): PackRecord? = _packs.value.firstOrNull { it.id == id }

    fun put(record: PackRecord) = mutate { list -> list.filter { it.id != record.id } + record }

    /** Applies [change] to the pack [id] (if it still exists). */
    fun update(id: String, change: (PackRecord) -> PackRecord) =
        mutate { list -> list.map { if (it.id == id) change(it) else it } }

    fun remove(id: String) = mutate { list -> list.filter { it.id != id } }

    private fun mutate(change: (List<PackRecord>) -> List<PackRecord>) = synchronized(lock) {
        val next = change(_packs.value)
        if (next != _packs.value) {
            save(next)
            _packs.value = next
        }
    }

    private fun load(): List<PackRecord> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONObject(file.readText()).getJSONArray("packs")
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }
    } catch (_: Exception) {
        emptyList() // an unreadable file behaves like none: the data on disk is only a cache
    }

    private fun save(list: List<PackRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("packs", JSONArray().also { a -> list.forEach { a.put(toJson(it)) } }).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not save ${file.name}" }
        }
    }

    private fun toJson(r: PackRecord) = JSONObject()
        .put("id", r.id).put("name", r.name).put("kind", r.kind.name).put("area", r.area.toJson())
        .put("state", r.state.name).put("progress", r.progress.toDouble()).put("bytes", r.bytes)
        .put("generation", r.generation).put("metered", r.allowMetered).put("error", r.error ?: JSONObject.NULL)
        .put("updatedAt", r.updatedAt)

    private fun fromJson(o: JSONObject) = PackRecord(
        id = o.getString("id"), name = o.getString("name"), kind = PackKind.valueOf(o.getString("kind")),
        area = PackArea.fromJson(o.getJSONObject("area")), state = PackState.valueOf(o.getString("state")),
        progress = o.getDouble("progress").toFloat(), bytes = o.getLong("bytes"), generation = o.getLong("generation"),
        allowMetered = o.getBoolean("metered"), error = if (o.isNull("error")) null else o.getString("error"),
        updatedAt = o.getLong("updatedAt"),
    )
}
