package com.livewall.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The motion behind a live wallpaper: a slow breathing zoom, parallax that follows the phone's tilt,
 * and glowing particles tinted with the picture's own brightest colours. Pure state; [GlRenderer] draws it.
 */
class Scene(var effects: Effects, private val palette: List<Int>) {

    private val particles = List(PARTICLE_COUNT) { Particle().also { p -> p.respawn(Random.nextFloat()) } }
    private val startNanos = System.nanoTime()
    private var t = 0f

    // Tilt in -1..1, smoothed. The neutral point slowly follows however the phone is being held.
    private var tiltX = 0f
    private var tiltY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var neutralX = Float.NaN
    private var neutralY = Float.NaN

    fun onGravity(gx: Float, gy: Float) {
        if (neutralX.isNaN()) {
            neutralX = gx
            neutralY = gy
        }
        neutralX += (gx - neutralX) * 0.005f
        neutralY += (gy - neutralY) * 0.005f
        targetX = ((gx - neutralX) / 3.5f).coerceIn(-1f, 1f)
        targetY = ((gy - neutralY) / 3.5f).coerceIn(-1f, 1f)
    }

    fun step(nanos: Long) {
        t = (nanos - startNanos) / 1e9f
        if (effects.tilt) {
            tiltX += (targetX - tiltX) * 0.08f
            tiltY += (targetY - tiltY) * 0.08f
        } else {
            tiltX *= 0.9f
            tiltY *= 0.9f
        }
        if (effects.particles) {
            for (p in particles) {
                p.y -= p.speed
                if (p.y < -0.05f) p.respawn(1.05f)
            }
        }
    }

    /** Where the picture goes on a w×h screen, in pixels: covers the screen with headroom for zoom and tilt. */
    fun contentRect(w: Int, h: Int, contentW: Int, contentH: Int, out: RectF) {
        val cover = max(w / contentW.toFloat(), h / contentH.toFloat())
        val breathe = if (effects.zoom) 0.05f * (0.5f - 0.5f * cos(2f * PI.toFloat() * t / ZOOM_PERIOD_S)) else 0f
        val scale = cover * (1.08f + breathe)
        val drawW = contentW * scale
        val drawH = contentH * scale
        val spareX = (drawW - w) / 2f
        val spareY = (drawH - h) / 2f
        val left = -spareX - tiltX * spareX * 0.9f
        val top = -spareY + tiltY * spareY * 0.9f
        out.set(left, top, left + drawW, top + drawH)
    }

    /**
     * Writes particles as (x, y, pointSize, r, g, b, intensity) in pixels into [out] and returns how many.
     * Nearer (bigger) particles shift further with tilt, which sells the depth.
     */
    fun writeParticles(w: Int, h: Int, out: FloatArray): Int {
        if (!effects.particles) return 0
        val unit = w / 400f
        particles.forEachIndexed { i, p ->
            val o = i * FLOATS_PER_PARTICLE
            out[o] = (p.x + 0.02f * sin(t * p.wobble + p.phase)) * w - tiltX * p.depth * 40f * unit
            out[o + 1] = p.y * h + tiltY * p.depth * 40f * unit
            out[o + 2] = p.size * unit * 8f
            out[o + 3] = Color.red(p.color) / 255f
            out[o + 4] = Color.green(p.color) / 255f
            out[o + 5] = Color.blue(p.color) / 255f
            out[o + 6] = 0.55f + 0.45f * sin(t * p.twinkle + p.phase)
        }
        return particles.size
    }

    private inner class Particle {
        var x = 0f
        var y = 0f
        var speed = 0f
        var size = 0f
        var depth = 0f
        var wobble = 0f
        var twinkle = 0f
        var phase = 0f
        var color = Color.WHITE

        fun respawn(atY: Float) {
            depth = Random.nextFloat()
            x = Random.nextFloat()
            y = atY
            size = 1f + depth * 2.5f
            speed = 0.0004f + depth * 0.0012f
            wobble = 0.3f + Random.nextFloat() * 0.6f
            twinkle = 1f + Random.nextFloat() * 2.5f
            phase = Random.nextFloat() * 2f * PI.toFloat()
            color = palette.random()
        }
    }

    companion object {
        const val PARTICLE_COUNT = 70
        const val FLOATS_PER_PARTICLE = 7
        private const val ZOOM_PERIOD_S = 24f

        val DEFAULT_PALETTE = listOf(Color.rgb(255, 236, 200), Color.WHITE)

        /** The picture's most vivid bright colours, lightened so particles read as light, not paint. */
        fun paletteOf(bitmap: Bitmap): List<Int> {
            val small = Bitmap.createScaledBitmap(bitmap, 24, 40, true)
            val hsv = FloatArray(3)
            val scored = mutableListOf<Pair<Float, FloatArray>>()
            for (y in 0 until small.height) for (x in 0 until small.width) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                scored += hsv[1] * hsv[2] * hsv[2] to hsv.copyOf()
            }
            if (small != bitmap) small.recycle()
            val picks = scored.sortedByDescending { it.first }.take(6)
                .filter { it.first > 0.15f }
                .map { (_, c) -> Color.HSVToColor(floatArrayOf(c[0], c[1] * 0.6f, 1f)) }
            return picks.ifEmpty { DEFAULT_PALETTE }
        }
    }
}
