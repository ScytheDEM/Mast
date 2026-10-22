package au.signalatlas.core.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Swaps a validated candidate DB in as `towers.db`, keeping the prior one as `towers.prev.db`.
 * The candidate must live in [dir] so every move is a same-directory atomic rename; the live
 * file is therefore always either the old complete DB or the new validated one.
 */
class DbInstaller(private val dir: File, private val validator: DbValidator) {
    val live: File = File(dir, "towers.db")
    val previous: File = File(dir, "towers.prev.db")

    @Synchronized
    fun install(candidate: File): Validation {
        val result = validator.validate(candidate)
        if (result !is Validation.Ok) {
            deleteWithSiblings(candidate)
            return result
        }
        deleteSiblings(candidate)
        deleteSiblings(live)
        if (live.exists()) move(live, previous)
        try {
            move(candidate, live)
        } catch (t: Throwable) {
            // Restore the working DB if the final step fails.
            if (!live.exists() && previous.exists()) runCatching { move(previous, live) }
            throw t
        }
        return Validation.Ok
    }

    @Synchronized
    fun rollback(): Boolean {
        if (!previous.exists()) return false
        deleteSiblings(previous)
        deleteSiblings(live)
        move(previous, live)
        return true
    }

    /** Removes leftovers of a crashed import; never touches towers.db or towers.prev.db. */
    fun cleanupStale() {
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (STALE_SUFFIXES.any { n.endsWith(it) || n.endsWith("$it-journal") }) f.delete()
        }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
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
