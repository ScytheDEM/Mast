package au.signalatlas.feature.trip

/** How well a stretch of the road ahead is covered. */
enum class CoverageLevel(val color: Int) {
    COVERED(0xFF2A9D8F.toInt()),
    WEAK(0xFFF4A261.toInt()),
    DEAD(0xFF9E9E9E.toInt()),
}

/** One run of the coverage strip, [lengthM] metres long. */
data class StripSegment(val lengthM: Int, val level: CoverageLevel)

/** What a trip notification shows. [progress] is the tracker position in metres along the strip (0 = here). */
data class TripContent(
    val title: String,
    val text: String,
    val shortCritical: String?,
    val segments: List<StripSegment>,
    val progress: Int,
) {
    companion object {
        const val STRIP_MAX_M = 10_000
        const val COVERED_P = 0.8f

        /** P >= 0.8 covered, 0.5..0.8 weak (marginal), below 0.5 dead (alert rule 3). */
        fun level(p: Float) = when {
            p >= COVERED_P -> CoverageLevel.COVERED
            p >= AlertEngine.COVERED_P -> CoverageLevel.WEAK
            else -> CoverageLevel.DEAD
        }

        /** The strip for the 50 m samples [ahead] (index 0 = here), runs merged, at most [STRIP_MAX_M]. */
        fun strip(ahead: List<Float>): List<StripSegment> {
            val out = ArrayList<StripSegment>()
            val n = minOf(ahead.size, (STRIP_MAX_M / AlertEngine.STEP_M).toInt())
            val step = AlertEngine.STEP_M.toInt()
            for (i in 0 until n) {
                val l = level(ahead[i])
                val last = out.lastOrNull()
                if (last != null && last.level == l) out[out.lastIndex] = last.copy(lengthM = last.lengthM + step) else out += StripSegment(step, l)
            }
            return out
        }

        fun of(stats: TripStats?, title: String = "Trip", waiting: String = TripController.WAITING_GPS): TripContent {
            val label = stats?.let { listOfNotNull(it.network, it.tech).joinToString(" ") }?.takeIf { it.isNotEmpty() }
            val status = stats?.status
            return TripContent(
                title = if (label != null) "$title · $label" else title,
                text = status?.text ?: stats?.message ?: waiting,
                shortCritical = status?.shortText,
                segments = status?.let { strip(it.ahead) }.orEmpty(),
                progress = 0,
            )
        }
    }
}
