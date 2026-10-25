package com.livewall.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The motion behind a live wallpaper: breathing zoom, tilt and home-screen-scroll parallax, a fade-in,
 * and particles of the chosen style that react to touch. Pure state; [GlRenderer] draws it.
 */
class Scene(effects: Effects, private val palette: List<Int>) {

    var effects: Effects = effects
        set(value) {
            val restyle = value.style != field.style
            field = value
            if (restyle) particles.forEach { it.respawn(Spawn.ANYWHERE) }
        }

    private val particles = List(MAX_PARTICLES) { Particle().also { it.respawn(Spawn.ANYWHERE) } }
    private val startNanos = System.nanoTime()
    private var lastNanos = 0L
    private var t = 0f

    /** 0 → 1 over the first moments, so a new wallpaper fades in instead of popping. */
    val fade get() = (t / FADE_S).coerceIn(0f, 1f)

    // Tilt in -1..1, smoothed. The neutral point slowly follows however the phone is being held.
    private var tiltX = 0f
    private var tiltY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var neutralX = Float.NaN
    private var neutralY = Float.NaN

    // Home screen page position, 0 (first page) .. 1 (last page); smoothed.
    private var scroll = 0.5f
    private var scrollTarget = 0.5f

    private var screenW = 1
    private var screenH = 1

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

    fun onScroll(xOffset: Float) {
        scrollTarget = xOffset.coerceIn(0f, 1f)
    }

    /** Finger dragging across the home screen: particles nearby get pushed away. */
    fun onTouchMove(x: Float, y: Float) {
        if (!effects.touch || !effects.particles) return
        val radius = 0.18f
        val aspect = screenH / screenW.toFloat()
        val nx = x / screenW
        val ny = y / screenH
        for (i in 0 until activeCount()) {
            val p = particles[i]
            val dx = p.x - nx
            val dy = (p.y - ny) * aspect
            val d = sqrt(dx * dx + dy * dy)
            if (d < radius && d > 0.0001f) {
                val push = (1f - d / radius) * 1.2f
                p.vx += dx / d * push
                p.vy += dy / d * push / aspect
            }
        }
    }

    /** A tap: a little burst of light flies out from the finger. */
    fun onTap(x: Float, y: Float) {
        if (!effects.touch) return
        val count = activeCount().coerceAtLeast(12)
        val nx = x / screenW
        val ny = y / screenH
        repeat(BURST) {
            val p = particles[Random.nextInt(count.coerceAtMost(MAX_PARTICLES))]
            p.respawn(Spawn.ANYWHERE)
            p.x = nx
            p.y = ny
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val power = 0.25f + Random.nextFloat() * 0.5f
            p.vx = cos(angle) * power
            p.vy = sin(angle) * power * screenW / screenH
            p.burst = 1f
        }
    }

    fun step(nanos: Long, w: Int, h: Int) {
        screenW = max(w, 1)
        screenH = max(h, 1)
        val dt = if (lastNanos == 0L) 0f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        t = (nanos - startNanos) / 1e9f

        val follow = 1f - exp(-dt * 5f)
        if (effects.tilt) {
            tiltX += (targetX - tiltX) * follow
            tiltY += (targetY - tiltY) * follow
        } else {
            tiltX -= tiltX * follow
            tiltY -= tiltY * follow
        }
        scroll += ((if (effects.scroll) scrollTarget else 0.5f) - scroll) * (1f - exp(-dt * 8f))

        if (effects.particles || particles.any { it.burst > 0f }) {
            val speed = 0.4f + effects.speed * 1.6f
            for (i in 0 until MAX_PARTICLES) particles[i].move(dt, speed, i < activeCount())
        }
    }

    /** Where the picture goes on a w×h screen, in pixels: covers the screen with room for every movement. */
    fun contentRect(w: Int, h: Int, contentW: Int, contentH: Int, out: RectF) {
        val e = effects
        val tiltPxX = if (e.tilt) w * (0.02f + 0.05f * e.tiltAmount) else 0f
        val tiltPxY = if (e.tilt) h * (0.012f + 0.025f * e.tiltAmount) else 0f
        val scrollPx = if (e.scroll) w * 0.05f else 0f
        val needX = tiltPxX + scrollPx
        val headroom = max((w + 2f * needX) / w, (h + 2f * tiltPxY) / h) + 0.01f
        val breathe = if (e.zoom) {
            (0.02f + 0.10f * e.zoomAmount) * (0.5f - 0.5f * cos(2f * PI.toFloat() * t / ZOOM_PERIOD_S))
        } else 0f

        val scale = max(w / contentW.toFloat(), h / contentH.toFloat()) * headroom * (1f + breathe)
        val drawW = contentW * scale
        val drawH = contentH * scale
        val left = (w - drawW) / 2f - tiltX * tiltPxX + (0.5f - scroll) * 2f * scrollPx
        val top = (h - drawH) / 2f + tiltY * tiltPxY
        out.set(left, top, left + drawW, top + drawH)
    }

    /**
     * Writes particles as (x, y, pointSize, r, g, b, intensity) in pixels into [out] and returns how many.
     * Nearer (bigger) particles shift further with tilt and scroll, which sells the depth.
     */
    fun writeParticles(w: Int, h: Int, out: FloatArray): Int {
        val unit = w / 400f
        val style = effects.style
        var n = 0
        val active = if (effects.particles) activeCount() else 0
        for (i in 0 until MAX_PARTICLES) {
            val p = particles[i]
            if (i >= active && p.burst <= 0f) continue
            val o = n * FLOATS_PER_PARTICLE
            val wob = when (style) {
                ParticleStyle.SPARKLE, ParticleStyle.BUBBLE -> 0.02f * sin(t * p.wobble + p.phase)
                ParticleStyle.SNOW -> 0.035f * sin(t * p.wobble + p.phase)
                else -> 0f
            }
            val shift = p.depth * 40f * unit
            out[o] = (p.x + wob) * w - tiltX * shift + (0.5f - scroll) * shift * 1.5f
            out[o + 1] = p.y * h + tiltY * shift
            out[o + 2] = p.pointSize(style) * unit
            out[o + 3] = Color.red(p.color) / 255f
            out[o + 4] = Color.green(p.color) / 255f
            out[o + 5] = Color.blue(p.color) / 255f
            out[o + 6] = max(p.intensity(style), p.burst)
            n++
        }
        return n
    }

    private fun activeCount() = (10 + effects.amount * (MAX_PARTICLES - 10)).toInt().coerceIn(0, MAX_PARTICLES)

    private enum class Spawn { ANYWHERE, ENTER }

    private inner class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var burst = 0f
        var size = 0f
        var depth = 0f
        var wobble = 0f
        var twinkle = 0f
        var phase = 0f
        var color = Color.WHITE

        fun respawn(spawn: Spawn) {
            val style = effects.style
            depth = Random.nextFloat()
            x = Random.nextFloat()
            y = when {
                spawn == Spawn.ANYWHERE -> Random.nextFloat()
                style.fallsDown() -> -0.05f - Random.nextFloat() * 0.1f
                else -> 1.05f + Random.nextFloat() * 0.1f
            }
            vx = 0f
            vy = 0f
            burst = 0f
            size = 1f + depth * 2.5f
            wobble = 0.3f + Random.nextFloat() * 0.6f
            twinkle = 1f + Random.nextFloat() * 2.5f
            phase = Random.nextFloat() * 2f * PI.toFloat()
            color = when (style) {
                ParticleStyle.SNOW -> Color.rgb(240, 246, 255)
                ParticleStyle.RAIN -> Color.rgb(190, 215, 255)
                ParticleStyle.FIREFLY -> if (Random.nextBoolean()) Color.rgb(215, 255, 110) else Color.rgb(255, 225, 110)
                else -> palette.random()
            }
        }

        fun move(dt: Float, speed: Float, active: Boolean) {
            val style = effects.style
            // Base drift in screen-heights per second; nearer particles move faster.
            val drift = when (style) {
                ParticleStyle.SPARKLE -> -(0.012f + depth * 0.036f)
                ParticleStyle.SNOW -> 0.04f + depth * 0.08f
                ParticleStyle.RAIN -> 0.8f + depth * 0.8f
                ParticleStyle.BUBBLE -> -(0.03f + depth * 0.06f)
                ParticleStyle.FIREFLY -> 0.025f * cos(t * wobble * 0.7f + phase)
                ParticleStyle.STARS -> 0f
            } * speed
            y += (drift + vy) * dt
            x += vx * dt
            if (style == ParticleStyle.FIREFLY) x += 0.025f * sin(t * wobble + phase) * speed * dt
            // Touch pushes and bursts fade out.
            val decay = exp(-dt * 2.5f)
            vx *= decay
            vy *= decay
            if (burst > 0f) burst = (burst - dt * 0.8f).coerceAtLeast(0f)

            if (!active) return
            val out = y < -0.15f || y > 1.15f || x < -0.15f || x > 1.15f
            if (out) {
                if (style == ParticleStyle.STARS || style == ParticleStyle.FIREFLY) respawn(Spawn.ANYWHERE)
                else respawn(Spawn.ENTER)
            }
        }

        fun pointSize(style: ParticleStyle): Float = when (style) {
            ParticleStyle.SPARKLE -> size * 8f
            ParticleStyle.SNOW -> (2f + depth * 4f) * 3f
            ParticleStyle.RAIN -> 16f + depth * 22f
            ParticleStyle.FIREFLY -> (2f + depth * 2f) * 8f
            ParticleStyle.BUBBLE -> (4f + depth * 10f) * 2.4f
            ParticleStyle.STARS -> (1f + depth * 2f) * 9f
        }

        fun intensity(style: ParticleStyle): Float {
            val s = sin(t * twinkle + phase)
            return when (style) {
                ParticleStyle.SPARKLE -> 0.55f + 0.45f * s
                ParticleStyle.SNOW -> 0.75f + depth * 0.25f
                ParticleStyle.RAIN -> 0.35f + depth * 0.35f
                ParticleStyle.FIREFLY -> max(0.04f, (sin(t * twinkle * 0.5f + phase) - 0.2f) / 0.8f)
                ParticleStyle.BUBBLE -> 0.6f + depth * 0.3f
                ParticleStyle.STARS -> 0.25f + 0.75f * ((0.5f + 0.5f * s) * (0.5f + 0.5f * s))
            }
        }
    }

    companion object {
        const val MAX_PARTICLES = 150
        const val FLOATS_PER_PARTICLE = 7
        private const val BURST = 14
        private const val ZOOM_PERIOD_S = 24f
        private const val FADE_S = 0.8f

        val DEFAULT_PALETTE = listOf(Color.rgb(255, 236, 200), Color.WHITE)

        private fun ParticleStyle.fallsDown() = this == ParticleStyle.SNOW || this == ParticleStyle.RAIN

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
