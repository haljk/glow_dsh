package dev.haljk.glow.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render a pairing payload as a square QR bitmap.
 *
 * Level M keeps the code readable from a laptop screen at arm's length without inflating
 * the module count; the quiet zone is drawn as part of the bitmap so a saved file still
 * scans after it has been moved into a gallery app.
 */
fun pairingQrBitmap(text: String, sizePx: Int = 720): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        ),
    )
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()

/**
 * Save the code into the gallery so it can be shown on another phone.
 *
 * Written through MediaStore rather than a raw path: on Android 10+ the app cannot create
 * files in Pictures directly, and the same code path already backs 存到相册 for results.
 */
suspend fun saveQrToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Glow")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }