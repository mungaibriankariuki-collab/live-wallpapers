package com.livewall.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Browse the online 4K gallery; [onUse] gets the downloaded photo as a selection. */
@Composable
fun GalleryScreen(onBack: () -> Unit, onUse: (Selection) -> Unit) {
    val context = LocalContext.current
    var categories by remember { mutableStateOf<List<GalleryCategory>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<GalleryItem?>(null) }
    // Bumped after a download so the grid's "downloaded" ticks refresh.
    var downloads by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var bulk by remember { mutableStateOf<Job?>(null) }
    var bulkDone by remember { mutableIntStateOf(0) }
    var bulkTotal by remember { mutableIntStateOf(0) }
    var bulkFailed by remember { mutableIntStateOf(0) }
    var bulkResult by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var shuffleOn by remember { mutableStateOf(false) }

    fun downloadAll(items: List<GalleryItem>) {
        bulkDone = 0
        bulkFailed = 0
        bulkTotal = items.size
        bulkResult = null
        bulk = scope.launch {
            val ok = mutableListOf<GalleryItem>()
            try {
                for (item in items) {
                    // One at a time: gentle on the connection, and progress is easy to follow.
                    runCatching { Gallery.download(context, item) {} }
                        .onSuccess { ok += item }
                        .onFailure { if (it is CancellationException) throw it else bulkFailed++ }
                    bulkDone++
                    downloads++
                }
            } finally {
                Gallery.addToCollection(context, ok)
                bulkResult = buildString {
                    append("${ok.size} photos added to My collection.")
                    if (bulkFailed > 0) append(" $bulkFailed failed; tap Download all again to retry.")
                }
                bulk = null
            }
        }
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(attempt) {
        error = null
        runCatching { Gallery.catalog(context) }
            .onSuccess { categories = it }
            .onFailure { error = "Couldn't load the gallery. Check your internet connection." }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text("4K Gallery", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val count = categories?.sumOf { it.items.size }
                if (count != null) Text("$count photos · download only the ones you want", fontSize = 12.sp, color = Color.Gray)
            }
        }

        val cats = categories
        when {
            cats == null && error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error!!, color = Color.LightGray, textAlign = TextAlign.Center)
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
            cats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    item { CategoryChip("All", category == null) { category = null } }
                    items(cats) { c -> CategoryChip(c.name, category == c.name) { category = c.name } }
                }
                val shown = cats.filter { category == null || it.name == category }.flatMap { it.items }
                val missing = remember(shown, downloads) { shown.filterNot { Gallery.isDownloaded(context, it) } }
                BulkBar(
                    running = bulk != null,
                    done = bulkDone,
                    total = bulkTotal,
                    missing = missing,
                    result = bulkResult,
                    shuffleOn = shuffleOn,
                    onDownloadAll = { confirmAll = missing },
                    onStop = { bulk?.cancel() },
                    onShuffle = {
                        val prefs = Prefs(context)
                        prefs.autoChange = AutoChange.SCREEN_ON
                        prefs.shuffle = true
                        shuffleOn = true
                    },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.id }) { item ->
                        val downloaded = remember(item.id, downloads) { Gallery.isDownloaded(context, item) }
                        Box(
                            Modifier
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF15171C))
                                .clickable { open = item },
                        ) {
                            AsyncImage(item.thumbUrl, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (downloaded) {
                                Icon(
                                    Icons.Filled.CheckCircle, "Downloaded", tint = Color(0xFF8AB4FF),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmAll?.let { items ->
        val mb = items.sumOf { it.bytes } / 1_048_576f
        AlertDialog(
            onDismissRequest = { confirmAll = null },
            title = { Text("Download ${items.size} photos?") },
            text = { Text("About ${"%.0f".format(mb)} MB. Wi-Fi is best. Keep this screen open until it finishes.") },
            confirmButton = {
                TextButton(onClick = { confirmAll = null; downloadAll(items) }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = null }) { Text("Cancel") } },
        )
    }

    open?.let { item ->
        DetailDialog(
            item,
            onDismiss = { open = null },
            onDownloaded = { downloads++ },
            onUse = { open = null; onUse(it) },
        )
    }
}

@Composable
private fun BulkBar(
    running: Boolean,
    done: Int,
    total: Int,
    missing: List<GalleryItem>,
    result: String?,
    shuffleOn: Boolean,
    onDownloadAll: () -> Unit,
    onStop: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        when {
            running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading $done of $total…", fontSize = 13.sp, color = Color.LightGray, modifier = Modifier.weight(1f))
                    TextButton(onClick = onStop) { Text("Stop") }
                }
                LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            missing.isNotEmpty() -> {
                val mb = missing.sumOf { it.bytes } / 1_048_576f
                OutlinedButton(onClick = onDownloadAll, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download all ${missing.size} (${"%.0f".format(mb)} MB)")
                }
            }
            else -> Text("All of these are downloaded.", fontSize = 13.sp, color = Color.Gray)
        }
        if (!running && result != null) {
            Text(result, fontSize = 13.sp, color = Color.LightGray, modifier = Modifier.padding(top = 6.dp))
            if (shuffleOn) {
                Text("✓ Shuffling on every unlock", fontSize = 13.sp, color = Color(0xFF8AB4FF), modifier = Modifier.padding(top = 4.dp))
            } else {
                Button(onClick = onShuffle, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Icon(Icons.Filled.Shuffle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle them on every unlock")
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF8AB4FF).copy(alpha = 0.2f),
            selectedLabelColor = Color(0xFF8AB4FF),
        ),
    )
}

@Composable
private fun DetailDialog(item: GalleryItem, onDismiss: () -> Unit, onDownloaded: () -> Unit, onUse: (Selection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (progress == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF15171C))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                item.thumbUrl, item.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text(
                "${item.width} × ${item.height} · ${"%.1f".format(item.bytes / 1_048_576f)} MB",
                fontSize = 13.sp, color = Color.LightGray,
            )
            Spacer(Modifier.height(6.dp))
            // Most of these licences require crediting the photographer.
            Text("Photo: ${item.author}", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
            Text(
                "${item.license} · Wikimedia Commons",
                fontSize = 12.sp, color = Color(0xFF8AB4FF), textAlign = TextAlign.Center,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.source))) }
                },
            )
            Spacer(Modifier.height(14.dp))

            val p = progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(p * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
            } else {
                if (failed) Text("Download failed. Check your connection and try again.", fontSize = 12.sp, color = Color(0xFFFF8A80))
                Button(
                    onClick = {
                        if (Gallery.isDownloaded(context, item)) {
                            onUse(Gallery.selectionFor(context, item))
                            return@Button
                        }
                        failed = false
                        progress = 0f
                        scope.launch {
                            runCatching { Gallery.download(context, item) { progress = it } }
                                .onSuccess {
                                    progress = null
                                    onDownloaded()
                                    onUse(Gallery.selectionFor(context, item))
                                }
                                .onFailure {
                                    progress = null
                                    failed = true
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (Gallery.isDownloaded(context, item)) "Use as wallpaper" else "Download & use")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
