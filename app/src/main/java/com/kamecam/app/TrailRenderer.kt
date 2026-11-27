package com.kamecam.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Satu preset efek. forcedColor = null berarti warna mengikuti warna benda. */
data class Preset(val name: String, val forcedColor: Int?, val lifeMs: Long)

val PRESETS = listOf(
    Preset("Hot Wheels (warna mobil)", null, 900L),
    Preset("Api", Color.rgb(255, 106, 0), 700L),
    Preset("Neon Biru", Color.rgb(0, 200, 255), 900L)
)

private class TrailPoint(
    val x: Float,
    val y: Float,
    val color: Int,
    val width: Float,
    val t: Long
)

/** Menyimpan titik-titik jejak dan menggambarnya sebagai cahaya yang memudar. */
class TrailRenderer {
    private val lock = Any()
    private val points = ArrayList<TrailPoint>()

    @Volatile var preset: Preset = PRESETS[0]

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun add(x: Float, y: Float, color: Int, width: Float, tNanos: Long) {
        synchronized(lock) {
            points.add(TrailPoint(x, y, color, width, tNanos))
            val cutoff = tNanos - 2_000_000_000L
            while (points.isNotEmpty() && points[0].t < cutoff) points.removeAt(0)
        }
    }

    fun clear() = synchronized(lock) { points.clear() }

    fun draw(canvas: Canvas, nowNanos: Long) {
        val pts = synchronized(lock) { ArrayList(points) }
        if (pts.size < 2) return
        val lifeNs = preset.lifeMs * 1_000_000L
        val forced = preset.forcedColor

        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.t - a.t > 200_000_000L) continue // jeda terlalu lama: putus
            val age = (nowNanos - b.t).toFloat() / lifeNs
            if (age >= 1f || age < 0f) continue
            val f = 1f - age
            val col = forced ?: b.color
            val w = b.width * (0.3f + 0.7f * f)

            paint.color = col
            paint.strokeWidth = w * 3.4f
            paint.alpha = (45 * f).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)

            paint.strokeWidth = w * 1.9f
            paint.alpha = (110 * f).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)

            paint.strokeWidth = w
            paint.alpha = (210 * f).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)

            paint.color = Color.WHITE
            paint.strokeWidth = w * 0.4f
            paint.alpha = (255 * f).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }

        // Titik terang di ujung jejak (posisi benda sekarang).
        val last = pts[pts.size - 1]
        val ageLast = (nowNanos - last.t).toFloat() / lifeNs
        if (ageLast in 0f..0.3f) {
            val col = forced ?: last.color
            fill.color = col
            fill.alpha = 90
            canvas.drawCircle(last.x, last.y, last.width * 2.6f, fill)
            fill.color = Color.WHITE
            fill.alpha = 230
            canvas.drawCircle(last.x, last.y, last.width * 0.9f, fill)
        }
    }
}
