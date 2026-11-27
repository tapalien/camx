package com.kamecam.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Style { LINE, FLAME }

/**
 * Satu preset efek.
 * - FLAME + forcedColor  -> api anime dengan palet api tetap
 * - FLAME + null         -> api dengan warna mengikuti warna mobil
 * - LINE                 -> jejak cahaya (warna tetap atau mengikuti mobil)
 */
data class Preset(val name: String, val style: Style, val forcedColor: Int?, val lifeMs: Long)

val PRESETS = listOf(
    Preset("Api Anime", Style.FLAME, Color.rgb(255, 109, 0), 650L),
    Preset("Aura Warna", Style.FLAME, null, 650L),
    Preset("Jejak Cahaya", Style.LINE, null, 900L),
    Preset("Neon Biru", Style.LINE, Color.rgb(0, 200, 255), 900L)
)

private val FIRE = intArrayOf(
    Color.argb(200, 230, 40, 20),
    Color.argb(225, 255, 110, 0),
    Color.argb(240, 255, 200, 20),
    Color.argb(250, 255, 250, 220)
)

private fun lighten(c: Int, f: Float, alpha: Int): Int {
    val r = Color.red(c) + ((255 - Color.red(c)) * f).toInt()
    val g = Color.green(c) + ((255 - Color.green(c)) * f).toInt()
    val b = Color.blue(c) + ((255 - Color.blue(c)) * f).toInt()
    return Color.argb(alpha, r, g, b)
}

private fun palette(carColor: Int, preset: Preset): IntArray {
    if (preset.style == Style.FLAME && preset.forcedColor != null) return FIRE
    val base = if (preset.style == Style.LINE) (preset.forcedColor ?: carColor) else carColor
    return intArrayOf(
        lighten(base, 0f, 190),
        lighten(base, 0.35f, 220),
        lighten(base, 0.7f, 240),
        Color.argb(250, 255, 255, 255)
    )
}

private fun withAlpha(c: Int, f: Float): Int =
    Color.argb(
        (Color.alpha(c) * f).toInt().coerceIn(0, 255),
        Color.red(c), Color.green(c), Color.blue(c)
    )

private class TrailPoint(
    val x: Float,
    val y: Float,
    val color: Int,
    val width: Float,
    val t: Long
)

private class Particle(
    val x0: Float,
    val y0: Float,
    val vx: Float,
    val vy: Float,
    val born: Long,
    val lifeNs: Long,
    val size: Float,
    val phase: Float,
    val color: Int
)

/** Menggambar jejak cahaya atau api anime di atas preview dan video. */
class TrailRenderer {
    private val lock = Any()
    private val points = ArrayList<TrailPoint>()
    private val particles = ArrayList<Particle>()
    private val rnd = Random(System.nanoTime())
    private var vx = 0f
    private var vy = 0f

    @Volatile var preset: Preset = PRESETS[0]

    /** Tampilkan cincin di posisi deteksi mentah untuk menguji pelacakan. */
    @Volatile var debug = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    fun add(x: Float, y: Float, color: Int, width: Float, tNanos: Long) {
        synchronized(lock) {
            val last = points.lastOrNull()
            if (last != null) {
                val dt = (tNanos - last.t) / 1e9f
                if (dt > 0.001f && dt < 0.2f) {
                    vx = vx * 0.5f + (x - last.x) / dt * 0.5f
                    vy = vy * 0.5f + (y - last.y) / dt * 0.5f
                } else {
                    vx = 0f
                    vy = 0f
                }
            } else {
                vx = 0f
                vy = 0f
            }
            points.add(TrailPoint(x, y, color, width, tNanos))
            val cutoff = tNanos - 2_000_000_000L
            while (points.isNotEmpty() && points[0].t < cutoff) points.removeAt(0)
            if (preset.style == Style.FLAME) spawn(x, y, color, width, tNanos)
        }
    }

    private fun spawn(x: Float, y: Float, color: Int, width: Float, t: Long) {
        val speed = hypot(vx, vy)
        if (speed < width * 3f) return // hampir diam: tidak ada api
        val dx = -vx / speed // arah ke belakang gerakan
        val dy = -vy / speed
        val px = -dy
        val py = dx
        repeat(3) {
            val off = (rnd.nextFloat() - 0.5f) * width * 2.2f
            val back = speed * (0.10f + rnd.nextFloat() * 0.30f)
            val spread = (rnd.nextFloat() - 0.5f) * width * 14f
            particles.add(
                Particle(
                    x0 = x + px * off,
                    y0 = y + py * off,
                    vx = dx * back + px * spread,
                    vy = dy * back + py * spread,
                    born = t,
                    lifeNs = (preset.lifeMs * (0.6f + rnd.nextFloat() * 0.4f) * 1_000_000f).toLong(),
                    size = width * (1.4f + rnd.nextFloat() * 1.6f),
                    phase = rnd.nextFloat() * 6.28f,
                    color = color
                )
            )
        }
        while (particles.size > 400) particles.removeAt(0)
    }

    fun clear() {
        synchronized(lock) {
            points.clear()
            particles.clear()
            vx = 0f
            vy = 0f
        }
    }

    fun draw(canvas: Canvas, now: Long) {
        val pr = preset
        var pts: List<TrailPoint> = emptyList()
        var ps: List<Particle> = emptyList()
        var cvx = 0f
        var cvy = 0f
        synchronized(lock) {
            particles.removeAll { now - it.born > it.lifeNs }
            pts = ArrayList(points)
            ps = ArrayList(particles)
            cvx = vx
            cvy = vy
        }

        if (pr.style == Style.LINE) drawLines(canvas, pts, now, pr) else drawFlames(canvas, ps, now, pr)
        if (pts.isNotEmpty()) drawHead(canvas, pts[pts.size - 1], cvx, cvy, now, pr)
    }

    // ---------------- api anime ----------------

    private fun teardrop(
        canvas: Canvas, x: Float, y: Float, angleDeg: Float,
        len: Float, wid: Float, sway: Float, color: Int
    ) {
        path.reset()
        path.moveTo(len, sway)
        path.cubicTo(len * 0.55f, sway * 0.4f - wid * 0.15f, wid * 0.1f, -wid, -wid * 0.45f, 0f)
        path.cubicTo(wid * 0.1f, wid, len * 0.55f, sway * 0.4f + wid * 0.15f, len, sway)
        path.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angleDeg)
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    private fun drawFlames(canvas: Canvas, ps: List<Particle>, now: Long, pr: Preset) {
        for (p in ps) {
            val t = (now - p.born).toFloat() / p.lifeNs
            if (t < 0f || t >= 1f) continue
            val sec = (now - p.born) / 1e9f
            val slow = 1f - 0.4f * t
            val x = p.x0 + p.vx * sec * slow
            val y = p.y0 + p.vy * sec * slow
            val angle = Math.toDegrees(atan2(p.vy, p.vx).toDouble()).toFloat()

            val grow = if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f
            val flicker = 0.85f + 0.15f * sin(p.phase + sec * 30f)
            val size = p.size * (0.4f + 0.6f * grow) * flicker
            val len = size * 3.4f
            val sway = size * 0.7f * sin(p.phase + sec * 14f)
            val fade = 1f - t * t
            val pal = palette(p.color, pr)

            teardrop(canvas, x, y, angle, len, size, sway, withAlpha(pal[0], fade))
            teardrop(canvas, x, y, angle, len * 0.72f, size * 0.68f, sway * 0.7f, withAlpha(pal[1], fade))
            teardrop(canvas, x, y, angle, len * 0.46f, size * 0.40f, sway * 0.4f, withAlpha(pal[2], fade))
            teardrop(canvas, x, y, angle, len * 0.24f, size * 0.20f, 0f, withAlpha(pal[3], fade))
        }
    }

    // ---------------- jejak cahaya ----------------

    private fun drawLines(canvas: Canvas, pts: List<TrailPoint>, now: Long, pr: Preset) {
        if (pts.size < 2) return
        val lifeNs = pr.lifeMs * 1_000_000L
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.t - a.t > 200_000_000L) continue // jeda terlalu lama: putus
            val age = (now - b.t).toFloat() / lifeNs
            if (age >= 1f || age < 0f) continue
            val f = 1f - age
            val pal = palette(b.color, pr)
            val w = b.width * (0.3f + 0.7f * f)

            stroke.color = withAlpha(pal[0], f * 0.25f)
            stroke.strokeWidth = w * 3.4f
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)

            stroke.color = withAlpha(pal[1], f * 0.5f)
            stroke.strokeWidth = w * 1.9f
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)

            stroke.color = withAlpha(pal[2], f)
            stroke.strokeWidth = w
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)

            stroke.color = withAlpha(pal[3], f)
            stroke.strokeWidth = w * 0.4f
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
        }
    }

    // ---------------- cahaya di benda + debug ----------------

    private fun drawHead(canvas: Canvas, last: TrailPoint, cvx: Float, cvy: Float, now: Long, pr: Preset) {
        val since = (now - last.t) / 1e9f
        if (debug) {
            stroke.color = Color.CYAN
            stroke.strokeWidth = last.width * 0.4f
            canvas.drawCircle(last.x, last.y, last.width * 4f, stroke)
        }
        if (since < 0f || since > 0.15f) return
        // Prediksi posisi sekarang supaya cahaya tidak tertinggal dari mobil.
        val lead = min(since, 0.12f)
        val hx = last.x + cvx * lead
        val hy = last.y + cvy * lead
        val pal = palette(last.color, pr)
        val fade = 1f - since / 0.15f

        fill.color = withAlpha(pal[0], 0.35f * fade)
        canvas.drawCircle(hx, hy, last.width * 3.2f, fill)
        fill.color = withAlpha(pal[1], 0.55f * fade)
        canvas.drawCircle(hx, hy, last.width * 2.0f, fill)
        fill.color = withAlpha(pal[2], 0.85f * fade)
        canvas.drawCircle(hx, hy, last.width * 1.1f, fill)
        fill.color = withAlpha(pal[3], fade)
        canvas.drawCircle(hx, hy, last.width * 0.55f, fill)
    }
}
