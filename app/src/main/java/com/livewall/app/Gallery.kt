package com.livewall.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class GalleryItem(
    val id: String,
    val title: String,
    val author: String,
    val license: String,
    val source: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
) {
    val thumbUrl get() = "${Gallery.BASE}thumb_$id.jpg"
    val fullUrl get() = "${Gallery.BASE}full_$id.jpg"
}

data class GalleryCategory(val name: String, val items: List<GalleryItem>)

/**
 * The online 4K gallery: free-licence photos from Wikimedia Commons, hosted as assets of the
 * `gallery-v1` GitHub release. Only the photos the user picks are downloaded.
 */
object Gallery {
    const val BASE = "https://github.com/mungaibriankariuki-collab/live-wallpapers/releases/download/gallery-v1/"

    private val http = OkHttpClient()

    private fun dir(context: Context) = File(context.filesDir, "gallery").apply { mkdirs() }

    fun file(context: Context, item: GalleryItem) = File(dir(context), "${item.id}.jpg")

    fun isDownloaded(context: Context, item: GalleryItem) = file(context, item).length() > 0

    /** A downloaded gallery photo lives in app storage; this tells collection entries apart from gallery picks. */
    fun ownsFile(context: Context, s: Selection): File? {
        val uri = s.uri
        if (uri.scheme != "file") return null
        val f = File(uri.path ?: return null)
        return if (f.parentFile == dir(context)) f else null
    }

    fun selectionFor(context: Context, item: GalleryItem) =
        Selection(Kind.PHOTO, Uri.fromFile(file(context, item)).toString())

    /** Adds downloaded photos to the end of the collection, so shuffle and auto change include them. */
    fun addToCollection(context: Context, items: List<GalleryItem>) {
        val prefs = Prefs(context)
        val current = prefs.collection
        val added = items.map { selectionFor(context, it) }.filter { it !in current }
        if (added.isNotEmpty()) prefs.collection = current + added
    }

    /** Fetches the catalogue, falling back to the last copy when offline. */
    suspend fun catalog(context: Context): List<GalleryCategory> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "catalog.json")
        val text = try {
            http.newCall(Request.Builder().url(BASE + "catalog.json").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.string().also { cache.writeText(it) }
            }
        } catch (e: IOException) {
            if (cache.exists()) cache.readText() else throw e
        }
        parse(text)
    }

    /** Downloads the full-size photo, reporting progress 0..1. Writes to a temp file so a cut-off download never looks finished. */
    suspend fun download(context: Context, item: GalleryItem, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(context, item)
        val tmp = File(target.path + ".part")
        http.newCall(Request.Builder().url(item.fullUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: item.bytes
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) throw IOException("Couldn't save the photo")
        target
    }

    private fun parse(text: String): List<GalleryCategory> {
        val cats = JSONObject(text).getJSONArray("categories")
        return (0 until cats.length()).map { i ->
            val c = cats.getJSONObject(i)
            val items = c.getJSONArray("items")
            GalleryCategory(
                c.getString("name"),
                (0 until items.length()).map { j ->
                    val o = items.getJSONObject(j)
                    GalleryItem(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        author = o.getString("author"),
                        license = o.getString("license"),
                        source = o.optString("source"),
                        width = o.getInt("width"),
                        height = o.getInt("height"),
                        bytes = o.optLong("bytes"),
                    )
                },
            )
        }.filter { it.items.isNotEmpty() }
    }
}
