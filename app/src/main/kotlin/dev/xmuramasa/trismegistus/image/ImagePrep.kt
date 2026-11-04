package dev.xmuramasa.trismegistus.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A JPEG ready to upload: upright, at most 2048 px on its long side, no EXIF (so no location). */
class PreparedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Reads a picked, captured or shared image and re-encodes it ([ImageSizing] does the maths). */
class ImagePrep(private val resolver: ContentResolver) {
    suspend fun prepare(uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(uri) { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        val exif = read(uri) { ExifInterface(it) }
        val options = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = read(uri) { BitmapFactory.decodeStream(it, null, options) } ?: error("not an image")
        val (w, h) = ImageSizing.fit(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postScale(w.toFloat() / decoded.width * (if (exif.isFlipped) -1 else 1), h.toFloat() / decoded.height)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        PreparedImage(out.toByteArray(), upright.width, upright.height).also {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    private fun <T> read(uri: Uri, block: (InputStream) -> T): T =
        (resolver.openInputStream(uri) ?: error("can't open")).use(block)

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
