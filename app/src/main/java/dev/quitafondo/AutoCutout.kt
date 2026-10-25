package dev.quitafondo

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.ImageOps
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recorte automático de personas con el modelo de ML Kit que va dentro de la app.
 * Devuelve la nueva máscara (sin aplicarla) o null si no encontró a nadie.
 */
object AutoCutout {

    /** Por debajo de esta fracción de píxeles visibles se considera que no hay persona. */
    private const val MIN_VISIBLE_FRACTION = 0.005

    suspend fun personMask(state: EditorState): ByteArray? {
        val w = state.width
        val h = state.height
        val argb = IntArray(w * h)
        // Imagen original completa, sin la máscara: el modelo necesita ver la foto entera.
        for (i in argb.indices) argb[i] = state.original[i] or (0xFF shl 24)
        val bitmap = Bitmaps.fromArgb(argb, w, h)

        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )
        // El segmentador se cierra cuando ML Kit termina, no antes: si la pantalla se cierra a mitad,
        // la tarea sigue usando la imagen. Por lo mismo el bitmap no se recicla a mano.
        val mask = suspendCancellableCoroutine<SegmentationMask> { cont ->
            segmenter.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
                .addOnCompleteListener { segmenter.close() }
        }
        val conf = readConfidence(mask, w, h)
        val person = ImageOps.confidenceToAlpha(conf)
        val visible = person.count { it != EditorState.ZERO }
        if (visible < w.toLong() * h * MIN_VISIBLE_FRACTION) return null
        // No recupera lo ya borrado a mano: se queda con lo más transparente de ambas máscaras.
        return ImageOps.minMask(state.alpha, person)
    }

    /** Lee la confianza por píxel y la lleva al tamaño de la imagen si el modelo devolvió otro. */
    private fun readConfidence(mask: SegmentationMask, w: Int, h: Int): FloatArray {
        val mw = mask.width
        val mh = mask.height
        val raw = FloatArray(mw * mh)
        val buffer = mask.buffer
        buffer.rewind()
        buffer.asFloatBuffer().get(raw)
        if (mw == w && mh == h) return raw
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * mh / h).coerceIn(0, mh - 1)
            for (x in 0 until w) {
                val sx = (x * mw / w).coerceIn(0, mw - 1)
                out[y * w + x] = raw[sy * mw + sx]
            }
        }
        return out
    }
}
