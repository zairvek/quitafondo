package dev.quitafondo

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.ImageOps
import dev.quitafondo.view.PreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** S05/S06 Suavizar el borde, elegir formato, guardar en Imágenes y compartir. */
class SaveActivity : AppCompatActivity() {

    private lateinit var state: EditorState
    private lateinit var preview: PreviewView
    private lateinit var working: View
    private lateinit var empty: View
    private lateinit var saveButton: View
    private lateinit var progress: View

    private var smooth = 0
    private var trim = true
    private var jpg = false
    private var result: Bitmap? = null
    private var renderJob: Job? = null

    /** Un cálculo de vista previa a la vez, para no multiplicar la memoria al mover rápido el deslizador. */
    private val renderLock = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        state = Session.editor ?: run { restartFromHome(); return }

        setContentView(R.layout.activity_save)
        findViewById<View>(R.id.root).padForSystemBars()

        preview = findViewById(R.id.preview)
        working = findViewById(R.id.working)
        empty = findViewById(R.id.empty)
        saveButton = findViewById(R.id.save)
        progress = findViewById(R.id.progress)
        progress.findViewById<TextView>(R.id.progressText).setText(R.string.saving)

        val row = findViewById<View>(R.id.rowSmooth)
        row.findViewById<TextView>(R.id.label).setText(R.string.smooth_edge)
        val slider = row.findViewById<Slider>(R.id.slider)
        val maxSmooth = resources.getInteger(R.integer.smooth_max)
        slider.valueTo = maxSmooth.toFloat()
        slider.valueFrom = 0f
        smooth = resources.getInteger(R.integer.smooth_default).coerceIn(0, maxSmooth)
        slider.value = smooth.toFloat()
        slider.addOnChangeListener { _, v, _ ->
            smooth = v.toInt()
            render()
        }

        val trimSwitch = findViewById<MaterialSwitch>(R.id.trim)
        trim = trimSwitch.isChecked
        trimSwitch.setOnCheckedChangeListener { _, checked ->
            trim = checked
            render()
        }

        findViewById<MaterialButtonToggleGroup>(R.id.format).addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                jpg = id == R.id.formatJpg
                preview.whiteBackground = jpg
            }
        }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        saveButton.setOnClickListener { save() }

        render()
    }

    /** Recalcula la vista previa. Espera un instante para no rehacerla en cada paso del deslizador. */
    private fun render() {
        renderJob?.cancel()
        working.visibility = View.VISIBLE
        saveButton.isEnabled = false
        val radius = smoothRadius(smooth)
        val doTrim = trim
        renderJob = lifecycleScope.launch {
            delay(120)
            val bmp = try {
                withContext(Dispatchers.Default) {
                    renderLock.withLock { renderResult(state, radius, doTrim) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Casi siempre falta de memoria en teléfonos modestos: se avisa en vez de cerrar la app.
                working.visibility = View.INVISIBLE
                toast(R.string.error_memory)
                return@launch
            }
            val old = result
            result = bmp
            preview.bitmap = bmp
            if (old != null && old !== bmp) old.recycle()
            empty.isVisible = bmp == null
            saveButton.isEnabled = bmp != null
            working.visibility = View.INVISIBLE
        }
    }

    /** El deslizador va de 0 a 10; el radio real crece con el tamaño de la imagen. */
    private fun smoothRadius(level: Int): Int {
        if (level <= 0) return 0
        val longest = max(state.width, state.height)
        return (level * longest / 1000f).roundToInt().coerceAtLeast(1)
    }

    private suspend fun renderResult(s: EditorState, radius: Int, doTrim: Boolean): Bitmap? {
        val w = s.width
        val h = s.height
        currentCoroutineContext().ensureActive()
        val mask = ImageOps.smoothMask(s.alpha, w, h, radius)
        currentCoroutineContext().ensureActive()
        val bounds = ImageOps.opaqueBounds(mask, w, h)
        if (bounds.isEmpty) return null
        val rect = if (doTrim) bounds else s.fullRect
        currentCoroutineContext().ensureActive()
        val argb = IntArray(w * h)
        s.composite(argb, rect, mask)
        val bmp = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        bmp.setPixels(argb, rect.top * w + rect.left, w, 0, 0, rect.width, rect.height)
        return bmp
    }

    private fun save() {
        val bmp = result ?: return
        val asJpg = jpg
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            // NonCancellable: si se gira la pantalla a mitad, el archivo se termina de escribir igual.
            val saved = resultOf { withContext(NonCancellable + Dispatchers.IO) { writeToGallery(bmp, asJpg) } }
            progress.visibility = View.GONE
            saved
                .onSuccess { uri -> showSaved(uri, if (asJpg) MIME_JPG else MIME_PNG) }
                .onFailure { toast(R.string.error_save) }
        }
    }

    private fun writeToGallery(bmp: Bitmap, asJpg: Boolean): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "quitafondo_$stamp" + if (asJpg) ".jpg" else ".png")
            put(MediaStore.Images.Media.MIME_TYPE, if (asJpg) MIME_JPG else MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Quitafondo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el archivo")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
            stream.use { out ->
                val ok = if (asJpg) {
                    val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    Canvas(flat).apply {
                        drawColor(color(R.color.backdrop_white))
                        drawBitmap(bmp, 0f, 0f, null)
                    }
                    flat.compress(Bitmap.CompressFormat.JPEG, 95, out).also { flat.recycle() }
                } else {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!ok) throw IOException("No se pudo comprimir la imagen")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun showSaved(uri: Uri, mime: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_title)
            .setMessage(R.string.saved_message)
            .setPositiveButton(R.string.share) { _, _ -> share(uri, mime) }
            .setNegativeButton(R.string.new_photo) { _, _ ->
                Session.clear()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
            .setNeutralButton(R.string.keep_editing, null)
            .show()
    }

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, getString(R.string.share_chooser))
            // Quitafondo también acepta imágenes compartidas; no tiene sentido ofrecerse a sí misma.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startActivity(chooser)
    }

    companion object {
        private const val MIME_PNG = "image/png"
        private const val MIME_JPG = "image/jpeg"
    }
}
