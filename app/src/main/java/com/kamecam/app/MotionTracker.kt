package com.kamecam.app

import android.graphics.Color
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Posisi benda bergerak (koordinat buffer gambar analisis) dan warnanya. */
data class Detection(val x: Float, val y: Float, val color: Int)

/**
 * Pelacak gerakan ringan: membandingkan frame sekarang dengan model latar belakang
 * yang pelan-pelan beradaptasi. Piksel yang beda = benda bergerak.
 * Kamera harus diam (pakai tripod) supaya hasilnya bagus.
 */
class MotionTracker {
    private val step = 4
    private var bg: IntArray? = null
    private var gridW = 0
    private var gridH = 0
    private var smR = 255f
    private var smG = 255f
    private var smB = 255f

    @Volatile var threshold = 30
    @Volatile var minPixels = 4

    fun analyze(image: ImageProxy): Detection? {
        val w = image.width
        val h = image.height
        val gw = w / step
        val gh = h / step

        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val yRow = yP.rowStride
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride

        val existing = bg
        val init = existing == null || gw != gridW || gh != gridH
        val model: IntArray = if (init) {
            IntArray(gw * gh).also {
                bg = it
                gridW = gw
                gridH = gh
            }
        } else {
            existing!!
        }

        var count = 0
        var sx = 0L
        var sy = 0L
        var sY = 0L
        var sU = 0L
        var sV = 0L

        for (gy in 0 until gh) {
            val py = gy * step
            for (gx in 0 until gw) {
                val px = gx * step
                val l = yB.get(py * yRow + px).toInt() and 0xFF
                val i = gy * gw + gx
                val l16 = l shl 4
                if (init) {
                    model[i] = l16
                    continue
                }
                val d = abs(l16 - model[i]) shr 4
                if (d > threshold) {
                    val cx = px shr 1
                    val cy = py shr 1
                    val u = uB.get(cy * uRow + cx * uPix).toInt() and 0xFF
                    val v = vB.get(cy * vRow + cx * vPix).toInt() and 0xFF
                    count++
                    sx += gx
                    sy += gy
                    sY += l
                    sU += u
                    sV += v
                }
                model[i] += (l16 - model[i]) shr 3
            }
        }

        if (init) return null
        // Terlalu banyak piksel berubah = kamera goyang / cahaya berubah, abaikan.
        if (count < minPixels || count > gw * gh / 4) return null

        val avgY = sY.toFloat() / count
        val u = sU.toFloat() / count - 128f
        val v = sV.toFloat() / count - 128f
        val r = (avgY + 1.402f * v).coerceIn(0f, 255f)
        val g = (avgY - 0.344f * u - 0.714f * v).coerceIn(0f, 255f)
        val b = (avgY + 1.772f * u).coerceIn(0f, 255f)

        smR = smR * 0.6f + r * 0.4f
        smG = smG * 0.6f + g * 0.4f
        smB = smB * 0.6f + b * 0.4f

        // Buat warna lebih "menyala": saturasi dinaikkan, kecerahan penuh.
        val hsv = FloatArray(3)
        Color.RGBToHSV(smR.toInt(), smG.toInt(), smB.toInt(), hsv)
        if (hsv[1] >= 0.15f) hsv[1] = (hsv[1] * 1.4f + 0.15f).coerceAtMost(1f)
        hsv[2] = 1f
        val color = Color.HSVToColor(hsv)

        val x = (sx.toFloat() / count) * step + step / 2f
        val y = (sy.toFloat() / count) * step + step / 2f
        return Detection(x, y, color)
    }
}
