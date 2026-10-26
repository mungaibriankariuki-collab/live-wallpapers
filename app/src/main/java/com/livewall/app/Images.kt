package com.livewall.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.InputStream

/** Bitmap loading for both the engine (screen-sized) and the settings screen (thumbnails). Call off the main thread. */
object Images {
    private const val MAX_SIDE = 4096

    fun load(context: Context, selection: Selection, targetW: Int, targetH: Int): Bitmap? = runCatching {
        when (selection.kind) {
            Kind.ASSET -> decode(targetW, targetH) { context.assets.open(selection.value) }
            Kind.PHOTO -> decode(targetW, targetH) { context.contentResolver.openInputStream(selection.uri)!! }
            Kind.VIDEO -> videoFrame(context, selection, targetW)
        }
    }.getOrNull()

    private fun decode(targetW: Int, targetH: Int, open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        // Never exceed what every GPU can hold as one texture.
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun videoFrame(context: Context, selection: Selection, targetW: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, selection.uri)
            val frame = r.getFrameAtTime(0) ?: return null
            if (frame.width <= targetW) frame
            else Bitmap.createScaledBitmap(frame, targetW, frame.height * targetW / frame.width, true)
        } finally {
            r.release()
        }
    }
}
