package au.signalatlas.core.data

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Checks a candidate DB before an installer may make it live. Must never throw. */
fun interface CandidateValidator {
    fun validate(db: File): Validation
}

/**
 * Swaps a validated candidate DB in as `<name>.db` (default `towers.db`), keeping the prior one as
 * `<name>.prev.db`. Several installers with different [name]s can share one [dir].
 *
 * Invariant: once a first DB has been installed, `<name>.db` exists at every instant. The old live
 * DB is preserved as `previous` by hard link (or copy) rather than moved, and the candidate then
 * replaces `live` with one atomic rename, so a crash at any point leaves either the old or the new
 * complete DB as `live`. The candidate must live in [dir] so the rename is same-directory.
 *
 * [cleanupStale] must only run before this installer's import starts (it deletes in-progress
 * `<name>.*.building` files).
 */
class DbInstaller(
    private val dir: File,
    private val validator: CandidateValidator,
    private val name: String = "towers",
    private val mover: (Path, Path) -> Unit = { from, to -> Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING) },
) {
    val live: File = File(dir, "$name.db")
    val previous: File = File(dir, "$name.prev.db")
    private val previousTmp: File = File(dir, "$name.prev.db.tmp")

    @Synchronized
    fun install(candidate: File): Validation {
        recover()
        val result = validator.validate(candidate)
        if (result !is Validation.Ok) {
            deleteWithSiblings(candidate)
            return result
        }
        try {
            deleteSiblings(candidate)
            force(candidate)
            if (live.exists()) preservePrevious()
            deleteSiblings(live) // a stale hot journal must not be applied to the new file
            mover(candidate.toPath(), live.toPath())
        } catch (t: Throwable) {
            deleteWithSiblings(candidate)
            previousTmp.delete()
            throw t
        }
        return Validation.Ok
    }

    @Synchronized
    fun rollback(): Boolean {
        if (!previous.exists()) return false
        deleteSiblings(previous)
        deleteSiblings(live)
        mover(previous.toPath(), live.toPath())
        return true
    }

    /** Restores `live` from `previous` if a crash left `live` missing. Returns true if it did. */
    @Synchronized
    fun recover(): Boolean {
        if (live.exists() || !previous.exists()) return false
        mover(previous.toPath(), live.toPath())
        return true
    }

    /**
     * Recovers a missing live DB, then removes this installer's leftovers of a crashed import
     * (`<name>.*.building`, `<name>.*.tmp` and their journals). Never touches live or previous, nor
     * another installer's files in the same directory.
     */
    @Synchronized
    fun cleanupStale() {
        recover()
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (n.startsWith("$name.") && STALE_SUFFIXES.any { n.endsWith(it) || n.endsWith("$it-journal") }) f.delete()
        }
    }

    private fun preservePrevious() {
        previousTmp.delete()
        try {
            Files.createLink(previousTmp.toPath(), live.toPath())
        } catch (e: Exception) {
            // Hard links unsupported: copy and sync instead.
            previousTmp.delete()
            Files.copy(live.toPath(), previousTmp.toPath())
            force(previousTmp)
        }
        deleteSiblings(previous)
        mover(previousTmp.toPath(), previous.toPath())
    }

    private fun force(f: File) {
        FileChannel.open(f.toPath(), READ, WRITE).use { it.force(true) }
    }

    private fun deleteSiblings(f: File) {
        for (s in SIBLINGS) File(f.path + s).delete()
    }

    private fun deleteWithSiblings(f: File) {
        f.delete()
        deleteSiblings(f)
    }

    private companion object {
        val SIBLINGS = listOf("-journal", "-wal", "-shm")
        val STALE_SUFFIXES = listOf(".building", ".tmp")
    }
}
