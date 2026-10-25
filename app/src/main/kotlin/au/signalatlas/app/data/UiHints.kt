package au.signalatlas.app.data

import android.content.Context
import androidx.core.content.edit

/** One-time hints the user has already seen, and small layout choices worth keeping across launches. */
interface UiHints {
    fun coverageExplainerSeen(): Boolean
    fun markCoverageExplainerSeen()

    /** The site sheet's "Radiation pattern" plots are shown (true by default). */
    fun patternExpanded(): Boolean = true
    fun setPatternExpanded(expanded: Boolean) {}

    /** Remembers for the process only (tests, previews). */
    class InMemory(private var seen: Boolean = false, private var pattern: Boolean = true) : UiHints {
        override fun coverageExplainerSeen() = seen
        override fun markCoverageExplainerSeen() { seen = true }
        override fun patternExpanded() = pattern
        override fun setPatternExpanded(expanded: Boolean) { pattern = expanded }
    }
}

class PrefsUiHints(context: Context) : UiHints {
    private val prefs = context.applicationContext.getSharedPreferences("ui_hints", Context.MODE_PRIVATE)
    override fun coverageExplainerSeen() = prefs.getBoolean(COVERAGE, false)
    override fun markCoverageExplainerSeen() = prefs.edit { putBoolean(COVERAGE, true) }
    override fun patternExpanded() = prefs.getBoolean(PATTERN, true)
    override fun setPatternExpanded(expanded: Boolean) = prefs.edit { putBoolean(PATTERN, expanded) }

    private companion object {
        const val COVERAGE = "coverage_explainer_seen"
        const val PATTERN = "pattern_expanded"
    }
}
