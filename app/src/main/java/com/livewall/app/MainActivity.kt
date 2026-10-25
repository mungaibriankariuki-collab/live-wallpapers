package com.livewall.app

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color.Black, surface = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { Home() }
            }
        }
    }
}

private val Accent = Color(0xFF8AB4FF)
private val CardColor = Color(0xFF15171C)
private val Outline = Color(0xFF2A2D35)
private val VideoSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun Home() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var selection by remember { mutableStateOf(prefs.selection) }
    var effects by remember { mutableStateOf(prefs.effects) }
    var collection by remember { mutableStateOf(prefs.collection) }
    var autoChange by remember { mutableStateOf(prefs.autoChange) }
    var shuffle by remember { mutableStateOf(prefs.shuffle) }
    var doubleTap by remember { mutableStateOf(prefs.doubleTapNext) }
    var isActive by remember { mutableStateOf(false) }

    // The wallpaper itself can change the selection (auto-change, double-tap), so re-read on return.
    LifecycleResumeEffect(Unit) {
        isActive = WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
        selection = prefs.selection
        onPauseOrDispose { }
    }

    fun choose(new: Selection) {
        prefs.selection = new
        selection = new
    }

    fun edit(transform: (Effects) -> Effects) {
        effects = transform(effects)
        prefs.effects = effects
    }

    fun picked(uri: Uri?, kind: Kind) {
        if (uri == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val s = Selection(kind, uri.toString())
        collection = listOf(s) + collection.filter { it != s }
        prefs.collection = collection
        choose(s)
    }

    fun remove(s: Selection) {
        collection = collection.filter { it != s }
        prefs.collection = collection
        // Gallery picks hold a long-lived read grant; give it back once nothing uses it.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(s.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (selection == s) choose(Builtins.all.first().selection)
        Toast.makeText(context, "Removed from your collection", Toast.LENGTH_SHORT).show()
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked(it, Kind.PHOTO) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked(it, Kind.VIDEO) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Live Wallpapers", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Preview(selection, effects, Modifier.align(Alignment.CenterHorizontally))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { setWallpaper(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Wallpaper, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isActive) "Open live preview" else "Set as live wallpaper", fontSize = 16.sp)
            }
            if (isActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Active. Changes show on your home screen straight away.", fontSize = 13.sp, color = Color.LightGray)
                }
            }
        }

        SectionTitle("Built-in")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(Builtins.all) { b ->
                Tile(b.selection, b.name, selected = selection == b.selection, onClick = { choose(b.selection) })
            }
        }

        SectionTitle("My collection")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Photo, null)
                Spacer(Modifier.width(6.dp))
                Text("Add photo")
            }
            OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Movie, null)
                Spacer(Modifier.width(6.dp))
                Text("Add video")
            }
        }
        if (collection.isEmpty()) {
            Hint("Photos and videos you add are kept here. Long-press one to remove it.")
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(collection, key = { it.value }) { s ->
                    Tile(
                        s, if (s.kind == Kind.VIDEO) "Video" else "Photo",
                        selected = selection == s, onClick = { choose(s) }, onLongClick = { remove(s) },
                    )
                }
            }
            Hint("Long-press to remove.")
        }

        SectionTitle("Particles")
        Panel {
            SwitchRow("Particles", "Floating light over the picture", effects.particles) { v -> edit { it.copy(particles = v) } }
            if (effects.particles) {
                Chips(ParticleStyle.entries, effects.style, { it.label }) { v -> edit { it.copy(style = v) } }
                SliderRow("Amount", effects.amount) { v -> edit { it.copy(amount = v) } }
                SliderRow("Speed", effects.speed) { v -> edit { it.copy(speed = v) } }
            }
            SwitchRow("Touch effects", "Tap for a burst of light; swipe to scatter particles", effects.touch) { v ->
                edit { it.copy(touch = v) }
            }
        }

        SectionTitle("Motion")
        Panel {
            SwitchRow("Slow zoom", "The picture gently breathes in and out", effects.zoom) { v -> edit { it.copy(zoom = v) } }
            if (effects.zoom) SliderRow("Zoom depth", effects.zoomAmount) { v -> edit { it.copy(zoomAmount = v) } }
            SwitchRow("Tilt with phone", "3D parallax as you move your phone", effects.tilt) { v -> edit { it.copy(tilt = v) } }
            if (effects.tilt) SliderRow("Tilt strength", effects.tiltAmount) { v -> edit { it.copy(tiltAmount = v) } }
            SwitchRow("Move with home screen", "Slides as you swipe between home screen pages", effects.scroll) { v ->
                edit { it.copy(scroll = v) }
            }
        }

        SectionTitle("Look")
        Panel {
            Chips(Filter.entries, effects.filter, { it.label }) { v -> edit { it.copy(filter = v) } }
            SliderRow("Brightness", (effects.brightness - 0.3f) / 0.7f) { v -> edit { it.copy(brightness = 0.3f + v * 0.7f) } }
            SwitchRow("Vignette", "Darker edges so icons stand out", effects.vignette) { v -> edit { it.copy(vignette = v) } }
        }

        SectionTitle("Auto change")
        Panel {
            Chips(AutoChange.entries, autoChange, { it.label }) { v ->
                autoChange = v
                prefs.autoChange = v
            }
            Hint(
                when (autoChange) {
                    AutoChange.OFF -> "The wallpaper stays as you set it."
                    AutoChange.SCREEN_ON -> "A new wallpaper each time you turn the screen on."
                    else -> "A new wallpaper every ${autoChange.label}, from your built-ins and collection."
                },
                Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow("Shuffle", "Random order instead of one after another", shuffle) { v ->
                shuffle = v
                prefs.shuffle = v
            }
            SwitchRow("Double-tap for next", "Double-tap an empty spot on the home screen", doubleTap) { v ->
                doubleTap = v
                prefs.doubleTapNext = v
            }
            TextButton(onClick = { prefs.next(); selection = prefs.selection }, modifier = Modifier.padding(start = 6.dp)) {
                Icon(Icons.Filled.SkipNext, null)
                Spacer(Modifier.width(6.dp))
                Text("Next wallpaper now")
            }
        }

        SectionTitle("Video")
        Panel {
            Text("Playback speed", fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            Chips(VideoSpeeds, effects.videoSpeed, { if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x" }) { v ->
                edit { it.copy(videoSpeed = v) }
            }
        }

        SectionTitle("Performance")
        Panel {
            SwitchRow("Smooth 60 fps", "Silkier photo motion; uses more battery", effects.smooth) { v -> edit { it.copy(smooth = v) } }
            SwitchRow("Save battery", "Slow the animation down while Battery Saver is on", effects.batterySaver) { v ->
                edit { it.copy(batterySaver = v) }
            }
        }

        TextButton(onClick = { edit { Effects() } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Reset all effects")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Preview(selection: Selection, effects: Effects, modifier: Modifier = Modifier) {
    val image = rememberThumbnail(selection, 540, 960)
    val filter = remember(effects.filter, effects.brightness) { previewFilter(effects.filter, effects.brightness) }
    Box(
        modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(24.dp))
            .background(CardColor)
            .border(BorderStroke(1.dp, Outline), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, colorFilter = filter, modifier = Modifier.fillMaxSize())
        } else {
            Text("Loading…", color = Color.Gray)
        }
        if (effects.vignette) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
        }
        if (selection.kind == Kind.VIDEO) Badge("VIDEO", Modifier.align(Alignment.TopEnd))
    }
}

/** Approximates the shader's colour grading so the preview matches the wallpaper. */
private fun previewFilter(f: Filter, brightness: Float): ColorFilter? {
    if (f == Filter.NONE && brightness >= 0.999f) return null
    val k = f.contrast
    val off = (1f - k) * 128f + f.lift * 255f
    val m = ColorMatrix().apply { setToSaturation(f.saturation) }
    m.timesAssign(
        ColorMatrix(
            floatArrayOf(
                k * f.r * brightness, 0f, 0f, 0f, off * f.r * brightness,
                0f, k * f.g * brightness, 0f, 0f, off * f.g * brightness,
                0f, 0f, k * f.b * brightness, 0f, off * f.b * brightness,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    return ColorFilter.colorMatrix(m)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(s: Selection, name: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val image = rememberThumbnail(s, 200, 356)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(92.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(14.dp))
                .background(CardColor)
                .border(
                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Accent else Outline),
                    RoundedCornerShape(14.dp),
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (image != null) Image(image, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (s.kind == Kind.VIDEO) Badge("VIDEO", Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name, fontSize = 12.sp, color = if (selected) Accent else Color.LightGray,
            textAlign = TextAlign.Center, modifier = Modifier.width(92.dp), maxLines = 1,
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black,
        modifier = modifier
            .padding(6.dp)
            .background(Accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardColor)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Color.LightGray, modifier = Modifier.width(104.dp))
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(options) { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.2f),
                    selectedLabelColor = Accent,
                ),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 1.2.sp)
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, color = Color.Gray, modifier = modifier)
}

@Composable
private fun rememberThumbnail(selection: Selection, w: Int, h: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, selection) {
        value = withContext(Dispatchers.IO) { Images.load(context, selection, w, h)?.asImageBitmap() }
    }
    return image
}

private fun setWallpaper(context: Context) {
    val component = ComponentName(context, LiveWallpaperService::class.java)
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    try {
        context.startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(context, "Choose \"Live Wallpapers\" from the list", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "This phone doesn't support live wallpapers", Toast.LENGTH_LONG).show()
        }
    }
}
