package dev.haljk.glow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal image loading.
 *
 * Every image this app shows is a local file or a local URI, so a full image
 * pipeline would be dead weight. Decoding is downsampled to the requested size
 * and the result is cached per (source, size).
 */
private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberUriBitmap(uri: String?, targetPx: Int = 320): ImageBitmap? {
    if (uri.isNullOrBlank()) return null
    val context = LocalContext.current
    val key = "uri:$uri@$targetPx"
    bitmapCache[key]?.let { return it }
    val state by produceState<ImageBitmap?>(initialValue = null, uri, targetPx) {
        val loaded = withContext(Dispatchers.IO) { decodeUri(context, Uri.parse(uri), targetPx) }
        if (loaded != null) bitmapCache[key] = loaded
        value = loaded
    }
    return state
}

@Composable
fun rememberFileBitmap(file: File?, targetPx: Int = 480): ImageBitmap? {
    if (file == null || !file.isFile) return null
    val key = "file:${file.absolutePath}@$targetPx@${file.lastModified()}"
    bitmapCache[key]?.let { return it }
    val state by produceState<ImageBitmap?>(initialValue = null, file.absolutePath, targetPx) {
        val loaded = withContext(Dispatchers.IO) { decodeFile(file, targetPx) }
        if (loaded != null) bitmapCache[key] = loaded
        value = loaded
    }
    return state
}

private fun decodeUri(context: Context, uri: Uri, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = sampleFor(bounds.outWidth, bounds.outHeight, targetPx)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?.asImageBitmap()
}.getOrNull()

private fun decodeFile(file: File, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val sample = sampleFor(bounds.outWidth, bounds.outHeight, targetPx)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

private fun sampleFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** Full-size decode for the viewer; callers cap it to avoid decoding 2048² twice. */
fun decodeFull(bitmapFile: File, maxEdge: Int = 2048): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(bitmapFile.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
    BitmapFactory.decodeFile(bitmapFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
