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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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

@Composable
private fun Home() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var selection by remember { mutableStateOf(prefs.selection) }
    var effects by remember { mutableStateOf(prefs.effects) }
    var isActive by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        isActive = WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
        onPauseOrDispose { }
    }

    fun choose(new: Selection) {
        val old = selection
        // Gallery picks hold a long-lived read grant; give the previous one back so they don't pile up.
        if (old.kind != Kind.ASSET && old.value != new.value) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.selection = new
        selection = new
    }

    fun picked(uri: Uri?, kind: Kind) {
        if (uri == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        choose(Selection(kind, uri.toString()))
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

        Preview(selection, Modifier.align(Alignment.CenterHorizontally))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { setWallpaper(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Wallpaper, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isActive) "Open wallpaper preview" else "Set as live wallpaper", fontSize = 16.sp)
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
                val s = Selection(Kind.ASSET, b.path)
                BuiltinTile(b, selected = selection == s) { choose(s) }
            }
        }

        SectionTitle("From your phone")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Photo, null)
                Spacer(Modifier.width(6.dp))
                Text("Pick photo")
            }
            OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Movie, null)
                Spacer(Modifier.width(6.dp))
                Text("Pick video")
            }
        }
        Text(
            "Videos loop silently. Photos come alive with the effects below; they work on videos too.",
            fontSize = 13.sp, color = Color.Gray,
        )

        SectionTitle("Effects")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15171C))) {
            EffectRow("Slow zoom", "The picture gently breathes in and out", effects.zoom) {
                effects = effects.copy(zoom = it); prefs.effects = effects
            }
            EffectRow("Tilt with phone", "3D parallax as you move your phone", effects.tilt) {
                effects = effects.copy(tilt = it); prefs.effects = effects
            }
            EffectRow("Glowing particles", "Floating light in the picture's own colours", effects.particles) {
                effects = effects.copy(particles = it); prefs.effects = effects
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Preview(selection: Selection, modifier: Modifier = Modifier) {
    val image = rememberThumbnail(selection, 540, 960)
    Box(
        modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF15171C))
            .border(BorderStroke(1.dp, Color(0xFF2A2D35)), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("Loading…", color = Color.Gray)
        }
        if (selection.kind == Kind.VIDEO) {
            Text(
                "VIDEO",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun BuiltinTile(b: Builtin, selected: Boolean, onClick: () -> Unit) {
    val image = rememberThumbnail(Selection(Kind.ASSET, b.path), 200, 356)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(92.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF15171C))
                .border(
                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Accent else Color(0xFF2A2D35)),
                    RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
        ) {
            if (image != null) Image(image, b.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(4.dp))
        Text(b.name, fontSize = 12.sp, color = if (selected) Accent else Color.LightGray,
            textAlign = TextAlign.Center, modifier = Modifier.width(92.dp), maxLines = 1)
    }
}

@Composable
private fun EffectRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 1.2.sp)
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
