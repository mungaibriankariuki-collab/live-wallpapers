package com.livewall.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

enum class Kind { ASSET, PHOTO, VIDEO }

/** What the wallpaper shows: the bundled starter asset, or a photo/video URI (gallery download or the user's own). */
data class Selection(val kind: Kind, val value: String) {
    val uri: Uri get() = Uri.parse(value)

    fun encode() = "${kind.name}\t$value"

    companion object {
        fun decode(s: String): Selection? {
            val parts = s.split('\t', limit = 2)
            if (parts.size != 2) return null
            val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
            return Selection(kind, parts[1])
        }
    }
}

enum class ParticleStyle(val label: String) {
    SPARKLE("Sparkles"), SNOW("Snow"), RAIN("Rain"), FIREFLY("Fireflies"), BUBBLE("Bubbles"), STARS("Stars")
}

/** Colour grading applied in the shader (and approximated in the app's preview). */
enum class Filter(
    val label: String,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val lift: Float = 0f,
    val r: Float = 1f,
    val g: Float = 1f,
    val b: Float = 1f,
) {
    NONE("Original"),
    VIVID("Vivid", saturation = 1.35f, contrast = 1.1f),
    MONO("B&W", saturation = 0f, contrast = 1.1f),
    WARM("Warm", r = 1.08f, b = 0.86f),
    COOL("Cool", r = 0.88f, g = 0.98f, b = 1.1f),
    DREAMY("Dreamy", saturation = 0.8f, lift = 0.05f, r = 1.04f, g = 0.96f, b = 1.06f),
    NOIR("Noir", saturation = 0f, contrast = 1.4f, lift = -0.04f),
    SUNSET("Sunset", saturation = 1.1f, r = 1.12f, g = 0.92f, b = 0.8f),
}

enum class AutoChange(val label: String, val millis: Long) {
    OFF("Off", 0),
    SCREEN_ON("Each unlock", -1),
    MIN_5("5 min", 5 * 60_000L),
    MIN_15("15 min", 15 * 60_000L),
    HOUR("1 hour", 60 * 60_000L),
    DAY("1 day", 24 * 60 * 60_000L),
}

/** Everything that changes how the current wallpaper is drawn. Amounts are 0..1. */
data class Effects(
    val zoom: Boolean = true,
    val zoomAmount: Float = 0.4f,
    val tilt: Boolean = true,
    val tiltAmount: Float = 0.5f,
    val scroll: Boolean = true,
    val touch: Boolean = true,
    val particles: Boolean = true,
    val style: ParticleStyle = ParticleStyle.SPARKLE,
    val amount: Float = 0.45f,
    val speed: Float = 0.4f,
    val filter: Filter = Filter.NONE,
    val brightness: Float = 1f,
    val vignette: Boolean = false,
    val smooth: Boolean = false,
    val batterySaver: Boolean = true,
    val videoSpeed: Float = 1f,
)

/** Shared between the settings screen and the wallpaper engines, which listen for changes. */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("livewall", Context.MODE_PRIVATE)

    var selection: Selection
        get() {
            val kind = raw.getString(KEY_KIND, null)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
            val value = raw.getString(KEY_VALUE, null)
            // Older versions bundled other assets; anything but the starter is gone now.
            if (kind == null || value == null || (kind == Kind.ASSET && value != Starter.selection.value)) return Starter.selection
            return Selection(kind, value)
        }
        set(s) = raw.edit()
            .putString(KEY_KIND, s.kind.name)
            .putString(KEY_VALUE, s.value)
            .putLong(KEY_LAST_CHANGE, System.currentTimeMillis())
            .apply()

    var effects: Effects
        get() {
            val d = Effects()
            return Effects(
                zoom = raw.getBoolean("fx_zoom", d.zoom),
                zoomAmount = raw.getFloat("fx_zoom_amt", d.zoomAmount),
                tilt = raw.getBoolean("fx_tilt", d.tilt),
                tiltAmount = raw.getFloat("fx_tilt_amt", d.tiltAmount),
                scroll = raw.getBoolean("fx_scroll", d.scroll),
                touch = raw.getBoolean("fx_touch", d.touch),
                particles = raw.getBoolean("fx_particles", d.particles),
                style = enumOr("fx_style", d.style),
                amount = raw.getFloat("fx_amount", d.amount),
                speed = raw.getFloat("fx_speed", d.speed),
                filter = enumOr("fx_filter", d.filter),
                brightness = raw.getFloat("fx_bright", d.brightness),
                vignette = raw.getBoolean("fx_vignette", d.vignette),
                smooth = raw.getBoolean("fx_smooth", d.smooth),
                batterySaver = raw.getBoolean("fx_saver", d.batterySaver),
                videoSpeed = raw.getFloat("fx_video_speed", d.videoSpeed),
            )
        }
        set(e) = raw.edit()
            .putBoolean("fx_zoom", e.zoom)
            .putFloat("fx_zoom_amt", e.zoomAmount)
            .putBoolean("fx_tilt", e.tilt)
            .putFloat("fx_tilt_amt", e.tiltAmount)
            .putBoolean("fx_scroll", e.scroll)
            .putBoolean("fx_touch", e.touch)
            .putBoolean("fx_particles", e.particles)
            .putString("fx_style", e.style.name)
            .putFloat("fx_amount", e.amount)
            .putFloat("fx_speed", e.speed)
            .putString("fx_filter", e.filter.name)
            .putFloat("fx_bright", e.brightness)
            .putBoolean("fx_vignette", e.vignette)
            .putBoolean("fx_smooth", e.smooth)
            .putBoolean("fx_saver", e.batterySaver)
            .putFloat("fx_video_speed", e.videoSpeed)
            .apply()

    /** Photos and videos the user picked, newest first. */
    var collection: List<Selection>
        get() = raw.getString(KEY_COLLECTION, "")!!.lines().mapNotNull { Selection.decode(it) }
        set(list) = raw.edit().putString(KEY_COLLECTION, list.joinToString("\n") { it.encode() }).apply()

    var autoChange: AutoChange
        get() = enumOr(KEY_AUTO, AutoChange.OFF)
        set(v) = raw.edit().putString(KEY_AUTO, v.name).putLong(KEY_LAST_CHANGE, System.currentTimeMillis()).apply()

    var shuffle: Boolean
        get() = raw.getBoolean(KEY_SHUFFLE, true)
        set(v) = raw.edit().putBoolean(KEY_SHUFFLE, v).apply()

    var doubleTapNext: Boolean
        get() = raw.getBoolean(KEY_DOUBLE_TAP, true)
        set(v) = raw.edit().putBoolean(KEY_DOUBLE_TAP, v).apply()

    val lastChange: Long get() = raw.getLong(KEY_LAST_CHANGE, 0L)

    /** What auto change and double-tap cycle through: the collection, in the order it's shown. */
    val playlist: List<Selection> get() = collection

    /** Moves to the next wallpaper in the playlist (or a random different one when shuffling). */
    fun next() {
        val list = playlist
        if (list.size < 2) return
        val current = selection
        selection = if (shuffle) {
            list.filter { it != current }.random()
        } else {
            list[(list.indexOf(current) + 1) % list.size]
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(key: String, default: T): T =
        raw.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_VALUE = "value"
        private const val KEY_COLLECTION = "collection"
        private const val KEY_AUTO = "auto_change"
        private const val KEY_SHUFFLE = "auto_shuffle"
        private const val KEY_DOUBLE_TAP = "double_tap"
        private const val KEY_LAST_CHANGE = "last_change"

        fun isEffectKey(key: String?) = key?.startsWith("fx_") == true
        fun isSelectionKey(key: String?) = key == KEY_KIND || key == KEY_VALUE
    }
}

/**
 * Shown until the user picks something: "Milky Way over Hochkalter & Hintersee" by Anil Öztas,
 * CC BY 4.0, from Wikimedia Commons (also in the 4K Gallery).
 */
object Starter {
    val selection = Selection(Kind.ASSET, "starter.jpg")
}
