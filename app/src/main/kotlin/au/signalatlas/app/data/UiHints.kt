package au.signalatlas.app.data

import android.content.Context
import androidx.core.content.edit

/** One-time hints the user has already seen. */
interface UiHints {
    fun coverageExplainerSeen(): Boolean
    fun markCoverageExplainerSeen()

    /** Remembers for the process only (tests, previews). */
    class InMemory(private var seen: Boolean = false) : UiHints {
        override fun coverageExplainerSeen() = seen
        override fun markCoverageExplainerSeen() { seen = true }
    }
}

class PrefsUiHints(context: Context) : UiHints {
    private val prefs = context.applicationContext.getSharedPreferences("ui_hints", Context.MODE_PRIVATE)
    override fun coverageExplainerSeen() = prefs.getBoolean(COVERAGE, false)
    override fun markCoverageExplainerSeen() = prefs.edit { putBoolean(COVERAGE, true) }

    private companion object {
        const val COVERAGE = "coverage_explainer_seen"
    }
}
