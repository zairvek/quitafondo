package dev.quitafondo

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.ImageOps
import dev.quitafondo.engine.IntRect
import dev.quitafondo.view.PreviewView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** S05/S06 Fondo, borde, suavizado, formato, guardar en Imágenes y compartir. */
class SaveActivity : AppCompatActivity() {

    private enum class Background { TRANSPARENT, SOLID, BLUR, PHOTO }

    private lateinit var state: EditorState
    private lateinit var preview: PreviewView
    private lateinit var working: View
    private lateinit var empty: View
    private lateinit var saveButton: View
    private lateinit var progress: View
    private lateinit var backgrounds: ChipGroup

    private var smooth = 0
    private var outline = 0
    private var trim = true
    private var jpg = false
    private var bgMode = Background.TRANSPARENT
    private var solidColor = 0
    private var photo: Bitmap? = null
    private var previousChip = 0

    /** Fondo desenfocado de la foto completa; se calcula una vez. */
    private var blurred: IntArray? = null

    private var result: Bitmap? = null
    private var renderJob: Job? = null

    /** Un cálculo de vista previa a la vez, para no multiplicar la memoria al mover rápido el deslizador. */
    private val renderLock = Mutex()

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            backgrounds.check(previousChip)
        } else {
            loadBackgroundPhoto(uri)
        }
    }

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
        backgrounds = findViewById(R.id.backgrounds)
        progress.findViewById<TextView>(R.id.progressText).setText(R.string.saving)
        solidColor = color(R.color.swatch_1)

        smooth = setupSlider(
            findViewById(R.id.rowSmooth), R.string.smooth_edge,
            resources.getInteger(R.integer.smooth_max), resources.getInteger(R.integer.smooth_default),
        ) { smooth = it; render() }
        outline = setupSlider(
            findViewById(R.id.rowOutline), R.string.outline,
            resources.getInteger(R.integer.outline_max), resources.getInteger(R.integer.outline_default),
        ) { outline = it; render() }

        val trimSwitch = findViewById<MaterialSwitch>(R.id.trim)
        trim = trimSwitch.isChecked
        trimSwitch.setOnCheckedChangeListener { _, checked ->
            trim = checked
            render()
        }

        findViewById<MaterialButtonToggleGroup>(R.id.format).addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                jpg = id == R.id.formatJpg
                updatePreviewBackdrop()
            }
        }

        previousChip = R.id.bgTransparent
        backgrounds.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            when (id) {
                R.id.bgTransparent -> chooseBackground(Background.TRANSPARENT, id)
                R.id.bgWhite -> { solidColor = color(R.color.backdrop_white); chooseBackground(Background.SOLID, id) }
                R.id.bgBlack -> { solidColor = color(R.color.backdrop_black); chooseBackground(Background.SOLID, id) }
                R.id.bgBlur -> chooseBackground(Background.BLUR, id)
                R.id.bgColor -> showColorPicker()
                R.id.bgPhoto -> photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        saveButton.setOnClickListener { save() }

        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        photo?.recycle()
        photo = null
    }

    private fun setupSlider(row: View, label: Int, max: Int, initial: Int, onChange: (Int) -> Unit): Int {
        row.findViewById<TextView>(R.id.label).setText(label)
        val slider = row.findViewById<Slider>(R.id.slider)
        slider.valueTo = max.toFloat()
        slider.valueFrom = 0f
        val v = initial.coerceIn(0, max)
        slider.value = v.toFloat()
        slider.addOnChangeListener { _, value, _ -> onChange(value.toInt()) }
        return v
    }

    private fun chooseBackground(bg: Background, chipId: Int) {
        bgMode = bg
        previousChip = chipId
        updatePreviewBackdrop()
        render()
    }

    /** Detrás de la vista previa: blanco si el JPG va a aplanar sobre blanco; si no, el tablero. */
    private fun updatePreviewBackdrop() {
        preview.whiteBackground = jpg && bgMode == Background.TRANSPARENT
    }

    private fun showColorPicker() {
        val colors = resources.getIntArray(R.array.background_colors)
        val grid = GridLayout(this).apply {
            columnCount = 4
            val pad = dp(16f).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_color)
            .setView(grid)
            .setOnCancelListener { backgrounds.check(previousChip) }
            .create()
        val size = dp(56f).toInt()
        val margin = dp(6f).toInt()
        for (c in colors) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1f).toInt(), color(R.color.ink_500))
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    // Con nombre explícito: la vista también tiene una propiedad "solidColor".
                    this@SaveActivity.solidColor = c
                    chooseBackground(Background.SOLID, R.id.bgColor)
                    dialog.dismiss()
                }
            }
            grid.addView(swatch)
        }
        dialog.show()
    }

    private fun loadBackgroundPhoto(uri: Uri) {
        working.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = resultOf {
                withContext(Dispatchers.IO) { Bitmaps.decode(contentResolver, uri, maxSide = max(state.width, state.height)) }
            }
            loaded
                .onSuccess { bmp ->
                    photo?.recycle()
                    photo = bmp
                    chooseBackground(Background.PHOTO, R.id.bgPhoto)
                }
                .onFailure {
                    working.visibility = View.INVISIBLE
                    toast(R.string.error_load)
                    backgrounds.check(previousChip)
                }
        }
    }

    /** Recalcula la vista previa. Espera un instante para no rehacerla en cada paso del deslizador. */
    private fun render() {
        renderJob?.cancel()
        working.visibility = View.VISIBLE
        saveButton.isEnabled = false
        val smoothRadius = levelToRadius(smooth, 1000f)
        val outlineRadius = levelToRadius(outline, 400f)
        val doTrim = trim
        val bg = bgMode
        val solid = solidColor
        val bgPhoto = photo
        renderJob = lifecycleScope.launch {
            delay(120)
            val bmp = try {
                withContext(Dispatchers.Default) {
                    renderLock.withLock { renderResult(smoothRadius, outlineRadius, doTrim, bg, solid, bgPhoto) }
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

    /** Los deslizadores van de 0 a 10; el radio real crece con el tamaño de la imagen. */
    private fun levelToRadius(level: Int, perThousand: Float): Int {
        if (level <= 0) return 0
        val longest = max(state.width, state.height)
        return (level * longest / perThousand).roundToInt().coerceAtLeast(1)
    }

    private suspend fun renderResult(
        smoothRadius: Int,
        outlineRadius: Int,
        doTrim: Boolean,
        bg: Background,
        solid: Int,
        bgPhoto: Bitmap?,
    ): Bitmap? {
        val s = state
        val w = s.width
        val h = s.height
        currentCoroutineContext().ensureActive()
        val mask = ImageOps.smoothMask(s.alpha, w, h, smoothRadius)
        currentCoroutineContext().ensureActive()
        val outlineMask = if (outlineRadius > 0) ImageOps.dilateMask(mask, w, h, outlineRadius) else null
        val bounds = ImageOps.opaqueBounds(outlineMask ?: mask, w, h)
        if (bounds.isEmpty) return null
        val rect = if (doTrim) bounds else s.fullRect
        currentCoroutineContext().ensureActive()
        val backdrop = when (bg) {
            Background.TRANSPARENT -> null
            Background.SOLID -> IntArray(rect.width * rect.height) { solid }
            Background.BLUR -> blurredBackground(rect)
            Background.PHOTO -> bgPhoto?.let { photoBackground(it, rect) }
        }
        currentCoroutineContext().ensureActive()
        val argb = ImageOps.render(s, mask, rect, backdrop, outlineMask, color(R.color.outline_default))
        val bmp = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        bmp.setPixels(argb, 0, rect.width, 0, 0, rect.width, rect.height)
        return bmp
    }

    /** La foto original desenfocada, calculada a un cuarto de resolución para no gastar memoria. */
    private fun blurredBackground(rect: IntRect): IntArray {
        val s = state
        val full = blurred ?: run {
            val w = s.width
            val h = s.height
            val opaque = IntArray(w * h) { s.original[it] or (0xFF shl 24) }
            val src = Bitmaps.fromArgb(opaque, w, h)
            val sw = (w / 4).coerceAtLeast(1)
            val sh = (h / 4).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            src.recycle()
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val radius = (max(sw, sh) / 40).coerceAtLeast(2)
            val blurredSmall = Bitmaps.fromArgb(ImageOps.blurRgb(px, sw, sh, radius), sw, sh)
            small.recycle()
            val back = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
            blurredSmall.recycle()
            val out = IntArray(w * h)
            back.getPixels(out, 0, w, 0, 0, w, h)
            back.recycle()
            out.also { blurred = it }
        }
        if (rect == s.fullRect) return full
        val out = IntArray(rect.width * rect.height)
        for (row in 0 until rect.height) {
            System.arraycopy(full, (rect.top + row) * s.width + rect.left, out, row * rect.width, rect.width)
        }
        return out
    }

    /** Otra foto escalada para cubrir [rect], centrada. */
    private fun photoBackground(src: Bitmap, rect: IntRect): IntArray {
        val rw = rect.width
        val rh = rect.height
        val scale = max(rw.toFloat() / src.width, rh.toFloat() / src.height)
        val sw = (src.width * scale).roundToInt().coerceAtLeast(rw)
        val sh = (src.height * scale).roundToInt().coerceAtLeast(rh)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val out = IntArray(rw * rh)
        scaled.getPixels(out, 0, rw, (sw - rw) / 2, (sh - rh) / 2, rw, rh)
        if (scaled !== src) scaled.recycle()
        for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        return out
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
