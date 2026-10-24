package au.signalatlas.app.ui.connected

import android.content.res.Resources
import au.signalatlas.app.R
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.ui.map.SignalQuality
import au.signalatlas.app.ui.map.frequencyLabel
import au.signalatlas.app.ui.map.networkNameRes
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell

/* Pure formatting for the bottom card and the "Cellular details" sheet, so dual-SIM text is unit-testable. */

/** Signal bars (0..4) for an RSRP in dBm; null = none. */
fun signalBars(rsrpDbm: Int?): Int = when {
    rsrpDbm == null -> 0
    rsrpDbm >= -85 -> 4
    rsrpDbm >= -95 -> 3
    rsrpDbm >= -105 -> 2
    rsrpDbm >= -115 -> 1
    else -> 0
}

/** The legend band the reading falls in, or null when weaker than the legend covers (or unknown). */
fun signalQuality(rsrpDbm: Int?): SignalQuality? = rsrpDbm?.let { SignalQuality.of(it) }

/** "−97" (a real minus sign). */
fun dbmNumber(v: Int): String = v.toString().replace('-', '−')

fun dbm(v: Int): String = "${dbmNumber(v)} dBm"

/** "Measured −97 dBm vs −92 dBm predicted here", or null without a prediction or a reading. */
fun measuredVsPredicted(res: Resources, measured: Int?, predicted: Int?): String? =
    if (measured == null || predicted == null) null else res.getString(R.string.cellular_measured_vs_predicted, dbm(measured), dbm(predicted))

/** How far the reading is from the prediction: within 2 dB counts as "in line". */
fun predictionDelta(res: Resources, measured: Int?, predicted: Int?): String? {
    if (measured == null || predicted == null) return null
    val d = measured - predicted
    return when {
        kotlin.math.abs(d) <= 2 -> res.getString(R.string.cellular_delta_match)
        d < 0 -> res.getString(R.string.cellular_delta_weaker, -d)
        else -> res.getString(R.string.cellular_delta_stronger, d)
    }
}

/** (label, value) rows for one cell on the details sheet; unknown values are left out. */
fun cellRows(c: ObservedCell, res: Resources): List<Pair<String, String>> = listOfNotNull(
    res.getString(R.string.cellular_tech) to res.getString(if (c.tech == Tech.NR) R.string.cellular_tech_nr else R.string.cellular_tech_lte),
    c.band?.let { res.getString(R.string.cellular_band) to it },
    c.channel?.let { ch ->
        res.getString(R.string.cellular_channel) to
            (c.freqMhz?.let { res.getString(R.string.cellular_channel_value, ch, frequencyLabel((it * 1e6).toLong())) } ?: ch.toString())
    },
    c.pci?.let { res.getString(R.string.cellular_pci) to it.toString() },
    c.cellId?.let { res.getString(R.string.cellular_cell_id) to it.toString() },
    c.tac?.let { res.getString(R.string.cellular_tac) to it.toString() },
    c.rsrp?.let { res.getString(R.string.cellular_rsrp) to dbm(it) },
    c.rsrq?.let { res.getString(R.string.cellular_rsrq) to res.getString(R.string.cellular_db, dbmNumber(it)) },
    c.sinr?.let { res.getString(R.string.cellular_sinr) to res.getString(R.string.cellular_db, dbmNumber(it)) },
    c.taMeters?.let { res.getString(R.string.cellular_ta) to res.getString(R.string.cellular_ta_value, distance(it)) },
)

/** One line for a neighbour or secondary: "B3 · 1850 MHz · PCI 20 · −104 dBm". */
fun cellLine(c: ObservedCell, res: Resources): String = listOfNotNull(
    c.band ?: res.getString(if (c.tech == Tech.NR) R.string.tech_5g else R.string.tech_4g),
    c.freqMhz?.let { frequencyLabel((it * 1e6).toLong()) },
    c.pci?.let { "${res.getString(R.string.cellular_pci)} $it" },
    c.rsrp?.let { dbm(it) },
).joinToString(" · ")

/** A neighbour's line without its band chip and RSRP, which the row shows separately: "1850 MHz · PCI 20". */
fun cellDetail(c: ObservedCell, res: Resources): String = listOfNotNull(
    if (c.band == null) res.getString(if (c.tech == Tech.NR) R.string.tech_5g else R.string.tech_4g) else null,
    c.freqMhz?.let { frequencyLabel((it * 1e6).toLong()) },
    c.pci?.let { "${res.getString(R.string.cellular_pci)} $it" },
).joinToString(" · ")

/** The SIM's name: its carrier, else its network, else "SIM n". */
fun simName(ui: ConnectedUi, res: Resources, index: Int): String =
    ui.carrier ?: ui.network?.let { res.getString(networkNameRes(it)) } ?: res.getString(R.string.cellular_sim_n, index + 1)

/** One line per SIM for accessibility and tests, e.g. "Telstra · Data SIM · 5G · B28 + n78 · −94 dBm". */
fun simSummary(ui: ConnectedUi, res: Resources, index: Int): String {
    val head = listOfNotNull(simName(ui, res, index), res.getString(R.string.cellular_data_sim).takeIf { ui.isData })
    return if (ui.status != ConnectedStatus.CONNECTED && ui.status != ConnectedStatus.UNTRACKED) {
        (head + connectedText(ui, res)).joinToString(" · ")
    } else {
        (
            head + listOfNotNull(
                res.getString(if (ui.fiveG) R.string.tech_5g else R.string.tech_4g),
                ui.bands,
                ui.rsrpDbm?.let { dbm(it) },
            )
            ).joinToString(" · ")
    }
}
