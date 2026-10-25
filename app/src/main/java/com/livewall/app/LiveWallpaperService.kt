package com.livewall.app

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveEngine()

    /**
     * One engine per wallpaper instance (the system preview gets its own). Everything is drawn through
     * [GlRenderer]: photos as a texture, videos via a muted looping MediaPlayer decoding into a SurfaceTexture.
     */
    inner class LiveEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        SensorEventListener, Choreographer.FrameCallback {

        private val prefs = Prefs(this@LiveWallpaperService)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val handler = Handler(Looper.getMainLooper())
        private val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        private val power = getSystemService(POWER_SERVICE) as PowerManager
        private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        private val renderer = GlRenderer()
        private val contentRect = RectF()
        private var width = 0
        private var height = 0
        private var visible = false
        private var loadJob: Job? = null

        private var scene: Scene? = null
        private var contentW = 0
        private var contentH = 0
        private var scrollOffset = 0.5f
        private var player: MediaPlayer? = null
        private var playerReady = false
        private var framePending = false
        private var lastFrameNanos = 0L

        private var lastTapMs = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        // Picking a new wallpaper writes two keys at once; reload once for both.
        private val reloadRunnable = Runnable { reload() }

        private val autoChangeTick = object : Runnable {
            override fun run() {
                checkAutoChange()
                handler.postDelayed(this, AUTO_CHECK_MS)
            }
        }

        // "Each wake": switch while the screen is off so the new wallpaper is ready when it turns back on.
        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (prefs.autoChange == AutoChange.SCREEN_ON) prefs.next()
            }
        }
        private var receiverRegistered = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            prefs.raw.registerOnSharedPreferenceChangeListener(this)
            // Only the real wallpaper rotates; the system preview just shows.
            if (!isPreview) {
                ContextCompat.registerReceiver(
                    this@LiveWallpaperService, screenOffReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_EXPORTED,
                )
                receiverRegistered = true
            }
        }

        override fun onDestroy() {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
            if (receiverRegistered) unregisterReceiver(screenOffReceiver)
            handler.removeCallbacksAndMessages(null)
            teardown()
            renderer.stop()
            scope.cancel()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            val sizeChanged = w != width || h != height
            width = w
            height = h
            if (!renderer.isReady) {
                if (!renderer.start(holder.surface)) return
                reload()
            } else if (sizeChanged) {
                reload()
            } else {
                drawFrame(System.nanoTime())
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame(System.nanoTime())
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            teardown()
            renderer.stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            player?.let { if (isVisible && playerReady) play(it) else if (playerReady && it.isPlaying) it.pause() }
            if (isVisible && scene != null) startAnimating() else stopAnimating()
            handler.removeCallbacks(autoChangeTick)
            if (isVisible && !isPreview) handler.post(autoChangeTick)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int,
        ) {
            // Launchers with a single page report 0; treat that as centred.
            scrollOffset = if (xStep <= 0f || xStep >= 1f) 0.5f else xOffset
            scene?.onScroll(scrollOffset)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_DOWN) {
                scene?.onTouchMove(event.x, event.y)
            }
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            // Taps the launcher didn't use (empty home screen space).
            if (action == WallpaperManager.COMMAND_TAP) {
                scene?.onTap(x.toFloat(), y.toFloat())
                val now = System.currentTimeMillis()
                val close = hypot(x - lastTapX, y - lastTapY) < width * 0.15f
                if (prefs.doubleTapNext && now - lastTapMs < DOUBLE_TAP_MS && close) {
                    lastTapMs = 0L
                    prefs.next()
                } else {
                    lastTapMs = now
                    lastTapX = x.toFloat()
                    lastTapY = y.toFloat()
                }
            }
            return null
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when {
                Prefs.isEffectKey(key) -> {
                    val effects = prefs.effects
                    scene?.effects = effects
                    player?.let { if (playerReady && it.isPlaying) applySpeed(it, effects.videoSpeed) }
                    updateSensor()
                    if (!visible) drawFrame(System.nanoTime())
                }
                Prefs.isSelectionKey(key) -> {
                    handler.removeCallbacks(reloadRunnable)
                    handler.post(reloadRunnable)
                }
            }
        }

        private fun checkAutoChange() {
            val auto = prefs.autoChange
            if (auto.millis > 0 && System.currentTimeMillis() - prefs.lastChange >= auto.millis) prefs.next()
        }

        private fun reload() {
            if (!renderer.isReady || width == 0) return
            teardown()
            val selection = prefs.selection
            val effects = prefs.effects
            loadJob = scope.launch {
                // For a video this is its first frame, used only to pick particle colours.
                val bitmap = withContext(Dispatchers.IO) {
                    Images.load(this@LiveWallpaperService, selection, width, height)
                }
                val palette = withContext(Dispatchers.Default) {
                    bitmap?.let { Scene.paletteOf(it) } ?: Scene.DEFAULT_PALETTE
                }
                if (!renderer.isReady) {
                    bitmap?.recycle()
                    return@launch
                }
                if (selection.kind == Kind.VIDEO) {
                    bitmap?.recycle()
                    scene = newScene(effects, palette)
                    startVideo(selection)
                } else if (bitmap != null) {
                    showBitmap(bitmap, newScene(effects, palette))
                } else {
                    showMessage("Couldn't open this photo.\nPick another one in Live Wallpapers.")
                }
            }
        }

        private fun newScene(effects: Effects, palette: List<Int>) =
            Scene(effects, palette).also { it.onScroll(scrollOffset) }

        private fun showBitmap(bitmap: Bitmap, newScene: Scene) {
            renderer.setPhoto(bitmap)
            contentW = bitmap.width
            contentH = bitmap.height
            bitmap.recycle()
            scene = newScene
            updateSensor()
            if (visible) startAnimating() else drawFrame(System.nanoTime())
        }

        private fun startVideo(selection: Selection) {
            val mp = MediaPlayer()
            player = mp
            playerReady = false
            // Until the real size arrives, assume a portrait phone video.
            contentW = 9
            contentH = 16
            try {
                mp.setSurface(renderer.startVideo())
                mp.setDataSource(this@LiveWallpaperService, selection.uri)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        contentW = w
                        contentH = h
                    }
                }
                mp.setOnPreparedListener {
                    playerReady = true
                    if (visible) play(it)
                }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "video error $what/$extra")
                    handler.post { showMessage("Couldn't play this video.\nPick another one in Live Wallpapers.") }
                    true
                }
                mp.prepareAsync()
                updateSensor()
                if (visible) startAnimating()
            } catch (e: Exception) {
                Log.w(TAG, "video setup failed", e)
                showMessage("Couldn't open this video.\nPick another one in Live Wallpapers.")
            }
        }

        private fun play(mp: MediaPlayer) {
            // Speed is set before start(): on some versions changing it on a paused player starts playback.
            applySpeed(mp, scene?.effects?.videoSpeed ?: 1f)
            mp.start()
        }

        private fun applySpeed(mp: MediaPlayer, speed: Float) {
            runCatching {
                if (mp.playbackParams.speed != speed) mp.playbackParams = mp.playbackParams.setSpeed(speed)
            }.onFailure { Log.w(TAG, "speed $speed not supported", it) }
        }

        private fun showMessage(text: String) {
            teardown()
            if (!renderer.isReady || width == 0) return
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = width / 22f
                textAlign = Paint.Align.CENTER
            }
            Canvas(bitmap).apply {
                drawColor(Color.BLACK)
                text.lines().forEachIndexed { i, line ->
                    drawText(line, width / 2f, height / 2f + i * paint.textSize * 1.4f, paint)
                }
            }
            val still = Effects(zoom = false, tilt = false, scroll = false, touch = false, particles = false)
            showBitmap(bitmap, Scene(still, Scene.DEFAULT_PALETTE))
        }

        private fun teardown() {
            loadJob?.cancel()
            loadJob = null
            stopAnimating()
            player?.release()
            player = null
            playerReady = false
            scene = null
            renderer.clearContent()
        }

        // ---- Animation loop ----

        private fun startAnimating() {
            updateSensor()
            if (framePending) return
            framePending = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stopAnimating() {
            if (framePending) Choreographer.getInstance().removeFrameCallback(this)
            framePending = false
            sensors.unregisterListener(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            framePending = false
            val s = scene ?: return
            if (!visible) return
            if (frameTimeNanos - lastFrameNanos >= frameInterval(s.effects)) {
                lastFrameNanos = frameTimeNanos
                drawFrame(frameTimeNanos)
            }
            framePending = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** Videos get every vsync; photos move slowly, so 30 fps is plenty unless "smooth" is on. */
        private fun frameInterval(e: Effects): Long = when {
            e.batterySaver && power.isPowerSaveMode -> SAVER_FRAME_NANOS
            renderer.hasVideo || e.smooth -> 0L
            else -> PHOTO_FRAME_NANOS
        }

        private fun drawFrame(nanos: Long) {
            val s = scene ?: return
            if (width == 0 || contentW == 0) return
            s.step(nanos, width, height)
            s.contentRect(width, height, contentW, contentH, contentRect)
            renderer.render(width, height, contentRect, s)
        }

        private fun updateSensor() {
            sensors.unregisterListener(this)
            if (visible && scene?.effects?.tilt == true && gravity != null) {
                sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            scene?.onGravity(event.values[0], event.values[1])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    companion object {
        private const val TAG = "LiveWall"
        private const val PHOTO_FRAME_NANOS = 32_000_000L
        private const val SAVER_FRAME_NANOS = 66_000_000L
        private const val DOUBLE_TAP_MS = 350L
        private const val AUTO_CHECK_MS = 30_000L
    }
}
