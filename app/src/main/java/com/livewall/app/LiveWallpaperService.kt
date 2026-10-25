package com.livewall.app

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
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private var player: MediaPlayer? = null
        private var playerReady = false
        private var framePending = false
        private var lastFrameNanos = 0L

        // Picking a new wallpaper writes two keys at once; reload once for both.
        private val reloadRunnable = Runnable { reload() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.raw.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(reloadRunnable)
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
            player?.let { if (isVisible && playerReady) it.start() else if (playerReady && it.isPlaying) it.pause() }
            if (isVisible && scene != null) startAnimating() else stopAnimating()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key in EFFECT_KEYS) {
                scene?.effects = prefs.effects
                updateSensor()
                if (!visible) drawFrame(System.nanoTime())
            } else {
                handler.removeCallbacks(reloadRunnable)
                handler.post(reloadRunnable)
            }
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
                    scene = Scene(effects, palette)
                    startVideo(selection)
                } else if (bitmap != null) {
                    showBitmap(bitmap, Scene(effects, palette))
                } else {
                    showMessage("Couldn't open this photo.\nPick another one in Live Wallpapers.")
                }
            }
        }

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
                    if (visible) it.start()
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
            showBitmap(bitmap, Scene(Effects(zoom = false, tilt = false, particles = false), Scene.DEFAULT_PALETTE))
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
            if (!visible || scene == null) return
            // Photos move slowly, so ~30 fps is plenty and halves the battery cost; videos get every vsync.
            val interval = if (renderer.hasVideo) 0L else PHOTO_FRAME_NANOS
            if (frameTimeNanos - lastFrameNanos >= interval) {
                lastFrameNanos = frameTimeNanos
                drawFrame(frameTimeNanos)
            }
            framePending = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun drawFrame(nanos: Long) {
            val s = scene ?: return
            if (width == 0 || contentW == 0) return
            s.step(nanos)
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
        private val EFFECT_KEYS = setOf("zoom", "tilt", "particles")
    }
}
