package au.signalatlas.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import au.signalatlas.core.map.NetworkColors

/** Car-contrast tonal palette for the map surface (the app palette; wallpaper colour is not available in the car). */
internal class CarPalette(val dark: Boolean) {
    val panel = if (dark) 0xF2212A31.toInt() else 0xF2F7F9FA.toInt()
    val onPanel = if (dark) 0xFFE6EDF2.toInt() else 0xFF14191D.toInt()
    val muted = if (dark) 0xFFA9B7C2.toInt() else 0xFF4A5963.toInt()
    val outline = if (dark) 0x55FFFFFF else 0x33000000
    val halo = if (dark) 0xFF10161B.toInt() else 0xFFFFFFFF.toInt()
    val barOff = if (dark) 0x44FFFFFF else 0x33000000
    val tower = if (dark) 0xFFB8C7D3.toInt() else 0xFF37474F.toInt()

    fun simColor(sim: CarSim): Int = sim.network?.let(NetworkColors::argb) ?: muted
}

/** Draws the rounded signal panel (one two-line row per SIM) onto the car surface. */
internal class SignalPanelPainter(private val unit: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = unit * 1.5f }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = unit * 27f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = unit * 23f }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    val rowHeight get() = unit * 82f
    private val pad get() = unit * 18f

    fun heightFor(rows: Int): Float = pad * 2 + rowHeight * rows.coerceAtLeast(1)

    /** Draws into [area]'s bottom-left corner; returns the panel's rectangle. */
    fun draw(canvas: Canvas, sims: List<CarSim>, area: RectF, p: CarPalette): RectF {
        val rows = sims.ifEmpty { listOf(null) }
        val w = minOf(area.width() * 0.56f, unit * 640f)
        val h = heightFor(rows.size)
        val r = RectF(area.left + unit * 20f, area.bottom - unit * 20f - h, area.left + unit * 20f + w, area.bottom - unit * 20f)
        fill.color = p.panel
        canvas.drawRoundRect(r, unit * 28f, unit * 28f, fill)
        stroke.color = p.outline
        canvas.drawRoundRect(r, unit * 28f, unit * 28f, stroke)
        rows.forEachIndexed { i, sim ->
            val top = r.top + pad + i * rowHeight
            if (sim == null) {
                title.color = p.onPanel
                canvas.drawText("Waiting for signal…", r.left + pad, top + unit * 40f, title)
            } else {
                drawRow(canvas, sim, r.left + pad, top, r.right - pad, p)
            }
        }
        return r
    }

    private fun drawRow(canvas: Canvas, sim: CarSim, left: Float, top: Float, right: Float, p: CarPalette) {
        // Colour chip: the carrier colour, solid for SIM 1 and dashed (like its map line) for SIM 2.
        fill.color = p.simColor(sim)
        val chip = RectF(left, top + unit * 8f, left + unit * 10f, top + rowHeight - unit * 14f)
        if (sim.slot <= 1) {
            canvas.drawRoundRect(chip, unit * 5f, unit * 5f, fill)
        } else {
            val seg = chip.height() / 5f
            for (k in 0 until 3) {
                val y = chip.top + k * seg * 2f
                canvas.drawRoundRect(RectF(chip.left, y, chip.right, y + seg), unit * 5f, unit * 5f, fill)
            }
        }
        val x = left + unit * 24f
        val barsW = unit * 64f
        title.color = p.onPanel
        val head = listOfNotNull(PanelText.header(sim), PanelText.tech(sim)).joinToString("   ")
        canvas.drawText(ellipsize(head, title, right - x - barsW - unit * 12f), x, top + unit * 30f, title)
        body.color = p.muted
        canvas.drawText(ellipsize(PanelText.detail(sim), body, right - x), x, top + unit * 64f, body)
        drawBars(canvas, PanelText.bars(sim.rsrpDbm), right - barsW, top + unit * 4f, p)
    }

    private fun drawBars(canvas: Canvas, bars: Int, left: Float, top: Float, p: CarPalette) {
        val bw = unit * 11f
        for (i in 0 until 4) {
            val h = unit * (10f + i * 7f)
            bar.color = if (i < bars) p.onPanel else p.barOff
            val x = left + i * (bw + unit * 4f)
            canvas.drawRoundRect(RectF(x, top + unit * 31f - h, x + bw, top + unit * 31f), unit * 2f, unit * 2f, bar)
        }
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
}
