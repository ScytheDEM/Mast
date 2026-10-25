package au.signalatlas.car

/** One Signal-screen row: the SIM's headline, then its detail lines (a pane row shows two). */
data class PaneRowModel(val title: String, val lines: List<String>)

/** The Signal screen's content (a PaneTemplate allows four rows). Pure. */
object SignalPaneModel {
    const val MAX_ROWS = 4

    fun bars(rsrp: Int?): String = PanelText.bars(rsrp).let { "▮".repeat(it) + "▯".repeat(4 - it) }

    fun rows(sims: List<CarSim>, trip: CarTripState): List<PaneRowModel> {
        val out = ArrayList<PaneRowModel>()
        for (sim in sims.take(MAX_ROWS - 1)) {
            val title = listOfNotNull(PanelText.header(sim), PanelText.tech(sim)).joinToString("   ")
            val signal = PanelText.signal(sim)?.let { "${bars(sim.rsrpDbm)}  $it" }
            val first = listOfNotNull(PanelText.ca(sim), signal).joinToString("   ")
            val second = listOfNotNull(PanelText.tower(sim)?.let { "Tower $it" }, PanelText.predicted(sim)).joinToString("   ")
            val lines = listOf(first, second).filter { it.isNotEmpty() }.ifEmpty { listOf(sim.note ?: "Searching…") }
            out += PaneRowModel(title, lines)
        }
        if (out.isEmpty()) out += PaneRowModel("No SIM signal", listOf("Check phone and location permissions"))
        val neighbours = sims.firstOrNull { it.isData }?.neighbours ?: sims.firstOrNull()?.neighbours ?: 0
        out += PaneRowModel(
            if (trip.running) "Trip running" else "Trip stopped",
            listOfNotNull(
                trip.banner ?: if (trip.running) "Watching for dead zones" else "Starts with Android Auto",
                if (neighbours > 0) "$neighbours neighbour cells" else null,
            ),
        )
        return out.take(MAX_ROWS)
    }
}
