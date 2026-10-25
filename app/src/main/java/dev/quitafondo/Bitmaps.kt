package dev.quitafondo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import dev.quitafondo.engine.EditorState
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Bitmaps {

    /** Lado máximo de la foto en la pantalla de recorte. */
    const val LOAD_MAX_SIDE = 3000

    /** Píxeles máximos de la imagen de trabajo (2048 x 2048). Más que esto no cabe con holgura en memoria. */
    const val WORK_MAX_PIXELS = 2048 * 2048

    /**
     * Decodifica la foto reducida a [maxSide] y en sRGB. ImageDecoder ya aplica la rotación EXIF.
     * Debe llamarse fuera del hilo principal.
     */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int = LOAD_MAX_SIDE): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize(
                    (w * s).roundToInt().coerceAtLeast(1),
                    (h * s).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    /** Recorta [crop] de [src], lo reduce al tamaño de trabajo y crea el estado del editor. */
    fun toEditorState(src: Bitmap, crop: Rect): EditorState {
        val safe = Rect(crop)
        if (!safe.intersect(0, 0, src.width, src.height) || safe.isEmpty) {
            safe.set(0, 0, src.width, src.height)
        }
        var bmp = Bitmap.createBitmap(src, safe.left, safe.top, safe.width(), safe.height())
        val pixels = bmp.width.toLong() * bmp.height
        if (pixels > WORK_MAX_PIXELS) {
            val s = sqrt(WORK_MAX_PIXELS.toDouble() / pixels)
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true,
            )
            if (bmp !== src) bmp.recycle()
            bmp = scaled
        }
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()
        return EditorState(w, h, argb)
    }

    /** Bitmap con transparencia a partir de píxeles ARGB sin premultiplicar. */
    fun fromArgb(argb: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        bmp.setPixels(argb, 0, w, 0, 0, w, h)
        return bmp
    }
}
