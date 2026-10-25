package dev.quitafondo

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.ImageOps
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recorte automático con ML Kit. Dos modelos:
 *
 * - [Model.SUBJECT] separa el objeto principal, sea lo que sea. Lo descarga Google Play Services
 *   la primera vez, así que puede no estar listo en el primer uso.
 * - [Model.PERSON] solo reconoce personas, pero viene dentro de la app y siempre está.
 *
 * Se intenta primero el de objetos y, si no está disponible, el de personas.
 */
object AutoCutout {

    enum class Model { SUBJECT, PERSON }

    /** [mask] es null cuando el modelo no encontró nada que recortar. */
    class Outcome(val mask: ByteArray?, val model: Model)

    /** Por debajo de esta fracción de píxeles visibles se considera que no se encontró nada. */
    private const val MIN_VISIBLE_FRACTION = 0.005

    suspend fun cutout(state: EditorState): Outcome {
        val w = state.width
        val h = state.height
        val argb = IntArray(w * h)
        // Foto original completa, sin la máscara: el modelo necesita ver la imagen entera.
        for (i in argb.indices) argb[i] = state.original[i] or (0xFF shl 24)
        val bitmap = Bitmaps.fromArgb(argb, w, h)
        val image = InputImage.fromBitmap(bitmap, 0)

        val subject = try {
            subjectConfidence(image)
        } catch (_: Exception) {
            null
        }
        if (subject != null) return Outcome(toMask(state, subject, w, h), Model.SUBJECT)

        val person = personConfidence(image, w, h)
        return Outcome(toMask(state, person, w, h), Model.PERSON)
    }

    private fun toMask(state: EditorState, conf: FloatArray, w: Int, h: Int): ByteArray? {
        val found = ImageOps.confidenceToAlpha(conf)
        val visible = found.count { it != EditorState.ZERO }
        if (visible < w.toLong() * h * MIN_VISIBLE_FRACTION) return null
        // No recupera lo ya borrado a mano: se queda con lo más transparente de ambas máscaras.
        return ImageOps.minMask(state.alpha, found)
    }

    /** Confianza por píxel del objeto principal, del mismo tamaño que la imagen; null si el modelo no da máscara. */
    private suspend fun subjectConfidence(image: InputImage): FloatArray? {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build(),
        )
        val result = suspendCancellableCoroutine<SubjectSegmentationResult> { cont ->
            segmenter.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
                .addOnCompleteListener { segmenter.close() }
        }
        val buffer: FloatBuffer = result.foregroundConfidenceMask ?: return null
        val out = FloatArray(image.width * image.height)
        buffer.rewind()
        buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        return out
    }

    private suspend fun personConfidence(image: InputImage, w: Int, h: Int): FloatArray {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )
        // El segmentador se cierra cuando ML Kit termina, no antes: si la pantalla se cierra a mitad,
        // la tarea sigue usando la imagen.
        val mask = suspendCancellableCoroutine<SegmentationMask> { cont ->
            segmenter.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
                .addOnCompleteListener { segmenter.close() }
        }
        return readConfidence(mask, w, h)
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
