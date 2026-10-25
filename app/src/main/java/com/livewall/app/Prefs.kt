package com.livewall.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

enum class Kind { ASSET, PHOTO, VIDEO }

/** What the wallpaper shows: a built-in asset path, or a photo/video content URI picked from the gallery. */
data class Selection(val kind: Kind, val value: String) {
    val uri: Uri get() = Uri.parse(value)
}

data class Effects(val zoom: Boolean, val tilt: Boolean, val particles: Boolean)

/** Shared between the settings screen and the wallpaper engine, which listens for changes. */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("livewall", Context.MODE_PRIVATE)

    var selection: Selection
        get() {
            val kind = raw.getString(KEY_KIND, null)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
            val value = raw.getString(KEY_VALUE, null)
            return if (kind != null && value != null) Selection(kind, value) else Selection(Kind.ASSET, Builtins.all.first().path)
        }
        set(s) = raw.edit().putString(KEY_KIND, s.kind.name).putString(KEY_VALUE, s.value).apply()

    var effects: Effects
        get() = Effects(
            zoom = raw.getBoolean(KEY_ZOOM, true),
            tilt = raw.getBoolean(KEY_TILT, true),
            particles = raw.getBoolean(KEY_PARTICLES, true),
        )
        set(e) = raw.edit()
            .putBoolean(KEY_ZOOM, e.zoom)
            .putBoolean(KEY_TILT, e.tilt)
            .putBoolean(KEY_PARTICLES, e.particles)
            .apply()

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_VALUE = "value"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_TILT = "tilt"
        private const val KEY_PARTICLES = "particles"
    }
}

data class Builtin(val name: String, val path: String)

object Builtins {
    val all = listOf(
        Builtin("Blue flowers", "wallpapers/kingdom-hearts-blue-flowers.jpg"),
        Builtin("Scales", "wallpapers/lizard-scales.jpg"),
        Builtin("Te Fiti", "wallpapers/moana-te-fiti.jpg"),
        Builtin("Fire fox", "wallpapers/glowing-fox.jpg"),
        Builtin("Land of the Dead", "wallpapers/coco-land-of-the-dead.jpg"),
    )
}
