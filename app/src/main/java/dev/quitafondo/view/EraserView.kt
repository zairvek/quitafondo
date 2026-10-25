package dev.quitafondo.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.quitafondo.Backdrop
import dev.quitafondo.R
import dev.quitafondo.checkerPaint
import dev.quitafondo.color
import dev.quitafondo.dimen
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.IntRect
import dev.quitafondo.engine.Stroke
import dev.quitafondo.engine.Tool
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.math.hypot

/**
 * Lienzo de borrado.
 *
 * Un dedo dibuja con la herramienta activa. El punto que borra está [cursorOffsetPx] por encima del dedo
 * para que el dedo no tape lo que se borra. Dos dedos acercan y mueven la imagen; si el segundo dedo
 * llega a mitad de un trazo, ese trazo se deshace.
 *
 * Los cálculos de borrado (sobre todo el relleno de Mágico) corren en un hilo propio, en orden.
 * El hilo principal solo copia a la pantalla la zona que cambió, como mucho una vez por fotograma,
 * así el dibujo no se congela aunque el cálculo vaya por detrás del dedo.
 */
class EraserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Avisa (en el hilo principal) cuando cambia el historial, para Deshacer y Rehacer. */
    var onHistoryChanged: (() -> Unit)? = null

    var tool: Tool = Tool.MANUAL

    /** Radio del pincel en píxeles de pantalla. */
    var brushRadiusPx: Float = context.resources.displayMetrics.density * 22f
        set(value) { field = value; invalidate() }

    var cursorOffsetPx: Float = context.resources.displayMetrics.density * 60f
        set(value) { field = value; invalidate() }

    /** 0..100 */
    var tolerance: Int = 40

    var backdrop: Backdrop = Backdrop.CHECKER
        set(value) { field = value; invalidate() }

    /**
     * Mientras es true se ignoran los toques (por ejemplo, durante un cálculo largo).
     * Un trazo a medias se deshace para que no quede fuera del historial.
     */
    var busy: Boolean = false
        set(value) {
            if (value) cancelStroke()
            field = value
        }

    /** Muestra el pincel en el centro mientras se ajusta su tamaño o su distancia. */
    var previewCursor: Boolean = false
        set(value) { field = value; invalidate() }

    /** Lupa junto al dedo mientras se dibuja. */
    var magnifier: Boolean = true

    private var state: EditorState? = null
    private var display: Bitmap? = null
    private var pixelBuffer = IntArray(0)

    // Zona de la imagen pendiente de copiar a pantalla. Lo escriben el hilo de trazos y el principal.
    private val dirtyLock = Any()
    private var pendingDirty: IntRect = IntRect.EMPTY

    private var worker: ExecutorService = newWorker()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val imageMatrix = Matrix()
    private val inverse = Matrix()
    private val matrixValues = FloatArray(9)
    private var fitScale = 1f
    private val point = FloatArray(2)
    private val imageBounds = RectF()

    private enum class Mode { NONE, DRAW, GESTURE }

    private var mode = Mode.NONE
    private var stroke: Stroke? = null
    private var fingerX = 0f
    private var fingerY = 0f

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f

    private val canvasPadding = context.dimen(R.dimen.canvas_padding)
    private val outsideColor = context.color(R.color.ink_950)
    private val checker = context.checkerPaint()
    private val solidPaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.cursor_stroke)
        color = context.color(R.color.cursor_ring)
    }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.cursor_shadow_stroke)
        color = context.color(R.color.cursor_shadow)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.cursor_target)
    }
    private val dotRadius = context.dimen(R.dimen.cursor_dot)

    private val magRadius = context.dimen(R.dimen.magnifier_radius)
    private val magMargin = context.dimen(R.dimen.space_m)
    private val magPath = Path()
    private val magMatrix = Matrix()
    private val magBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.magnifier_border)
        color = context.color(R.color.cursor_ring)
    }
    private val magBorderShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.magnifier_border) * 2f
        color = context.color(R.color.cursor_shadow)
    }

    fun setState(s: EditorState) {
        state = s
        display?.recycle()
        display = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        pixelBuffer = IntArray(s.width * s.height)
        refresh(s.fullRect)
        fit()
    }

    /** Marca la zona [r] de la imagen para volver a pintarla desde la máscara. Se puede llamar desde cualquier hilo. */
    fun refresh(r: IntRect?) {
        if (r == null || r.isEmpty) return
        synchronized(dirtyLock) { pendingDirty = pendingDirty.union(r) }
        postInvalidateOnAnimation()
    }

    /** Copia a pantalla lo pendiente. Solo en el hilo principal, justo antes de dibujar. */
    private fun flushDirty() {
        val s = state ?: return
        val bmp = display ?: return
        val r = synchronized(dirtyLock) {
            val d = pendingDirty
            pendingDirty = IntRect.EMPTY
            d
        }
        if (r.isEmpty) return
        s.composite(pixelBuffer, r)
        bmp.setPixels(pixelBuffer, r.top * s.width + r.left, s.width, r.left, r.top, r.width, r.height)
    }

    private fun submit(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            // La vista ya se desmontó; no queda nada que dibujar.
        }
    }

    /** Espera a que el hilo de trazos termine todo lo encolado (antes de leer o cambiar la máscara desde fuera). */
    suspend fun awaitIdle() = suspendCancellableCoroutine<Unit> { cont ->
        try {
            worker.execute { cont.resume(Unit) }
        } catch (_: RejectedExecutionException) {
            cont.resume(Unit)
        }
    }

    /** Deshace el trazo en curso, si lo hay (por ejemplo, si otro dedo pulsa Deshacer a la vez). */
    fun cancelStroke() {
        val st = stroke ?: return
        stroke = null
        mode = Mode.NONE
        submit { refresh(st.cancel()) }
        invalidate()
    }

    fun undo() {
        cancelStroke()
        val s = state ?: return
        submit {
            refresh(s.history.undo(s.alpha, s.width))
            mainHandler.post { onHistoryChanged?.invoke() }
        }
    }

    fun redo() {
        cancelStroke()
        val s = state ?: return
        submit {
            refresh(s.history.redo(s.alpha, s.width))
            mainHandler.post { onHistoryChanged?.invoke() }
        }
    }

    /** Ajusta la imagen completa a la vista. */
    fun fit() {
        val s = state ?: return
        if (width == 0 || height == 0) return
        val availW = width - 2 * canvasPadding
        val availH = height - 2 * canvasPadding
        fitScale = minOf(availW / s.width, availH / s.height).coerceAtLeast(0.01f)
        imageMatrix.setScale(fitScale, fitScale)
        imageMatrix.postTranslate((width - s.width * fitScale) / 2f, (height - s.height * fitScale) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (worker.isShutdown) worker = newWorker()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val st = stroke
        stroke = null
        mode = Mode.NONE
        if (st != null) submit { st.cancel() }
        worker.shutdown()
        display?.recycle()
        display = null
    }

    private fun newWorker(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trazos").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private fun currentScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        imageMatrix.invert(inverse)
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)
        return point
    }

    /**
     * Radio del pincel en píxeles de imagen. En Mágico se limita: un relleno enorme en cada toque
     * haría lento el dibujo. El cursor dibuja el mismo límite para que lo que ves sea lo que borra.
     */
    private fun brushRadiusInImage(): Float {
        val r = (brushRadiusPx / currentScale()).coerceAtLeast(0.5f)
        return if (tool == Tool.MAGIC) r.coerceAtMost(MAGIC_MAX_RADIUS) else r
    }

    private fun cursorRadiusOnScreen(): Float = brushRadiusInImage() * currentScale()

    // ---------- Dibujo ----------

    override fun onDraw(canvas: Canvas) {
        flushDirty()
        canvas.drawColor(outsideColor)
        val s = state ?: return
        val bmp = display ?: return

        imageBounds.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        imageMatrix.mapRect(imageBounds)
        canvas.drawRect(imageBounds, backdropPaint())
        // De cerca se ven los píxeles tal cual, para poder afinar.
        bitmapPaint.isFilterBitmap = currentScale() < 3f
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        when {
            mode == Mode.DRAW -> {
                drawCursor(canvas, fingerX, fingerY)
                if (magnifier) drawMagnifier(canvas, bmp, fingerX, fingerY - cursorOffsetPx)
            }
            previewCursor -> drawCursor(canvas, width / 2f, height / 2f + cursorOffsetPx / 2f)
        }
    }

    private fun backdropPaint(): Paint = when (backdrop) {
        Backdrop.CHECKER -> checker
        Backdrop.WHITE -> solidPaint.apply { color = context.color(R.color.backdrop_white) }
        Backdrop.GRAY -> solidPaint.apply { color = context.color(R.color.backdrop_gray) }
        Backdrop.BLACK -> solidPaint.apply { color = context.color(R.color.backdrop_black) }
    }

    private fun drawCursor(canvas: Canvas, fx: Float, fy: Float) {
        val r = cursorRadiusOnScreen()
        val tx = fx
        val ty = fy - cursorOffsetPx
        if (cursorOffsetPx > r) {
            canvas.drawLine(fx, fy, tx, ty + r, ringShadow)
            canvas.drawLine(fx, fy, tx, ty + r, ringPaint)
            canvas.drawCircle(fx, fy, dotRadius, ringPaint)
        }
        canvas.drawCircle(tx, ty, r, ringShadow)
        canvas.drawCircle(tx, ty, r, ringPaint)
        canvas.drawCircle(tx, ty, dotRadius, targetPaint)
    }

    /** Círculo ampliado en la esquina superior opuesta al dedo, centrado en el punto que borra. */
    private fun drawMagnifier(canvas: Canvas, bmp: Bitmap, tx: Float, ty: Float) {
        val r = magRadius
        val cx = if (tx < width / 2f) width - r - magMargin else r + magMargin
        val cy = r + magMargin
        canvas.save()
        magPath.reset()
        magPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(magPath)
        canvas.drawRect(cx - r, cy - r, cx + r, cy + r, backdropPaint())
        magMatrix.set(imageMatrix)
        magMatrix.postScale(MAGNIFICATION, MAGNIFICATION, tx, ty)
        magMatrix.postTranslate(cx - tx, cy - ty)
        canvas.drawBitmap(bmp, magMatrix, bitmapPaint)
        val ring = cursorRadiusOnScreen() * MAGNIFICATION
        canvas.drawCircle(cx, cy, ring, ringShadow)
        canvas.drawCircle(cx, cy, ring, ringPaint)
        canvas.drawCircle(cx, cy, dotRadius, targetPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, r, magBorderShadow)
        canvas.drawCircle(cx, cy, r, magBorder)
    }

    // ---------- Toques ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = state ?: return false
        if (busy) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.DRAW
                fingerX = event.x
                fingerY = event.y
                val p = toImage(fingerX, fingerY - cursorOffsetPx)
                val x = p[0]
                val y = p[1]
                val st = Stroke(s, tool, brushRadiusInImage(), tolerance)
                stroke = st
                submit { refresh(st.begin(x, y)) }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) cancelStroke()
                mode = Mode.GESTURE
                startGesture(event, skipIndex = -1)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val st = stroke ?: return true
                    // Incluye los puntos intermedios que el sistema agrupa en un mismo evento.
                    val n = event.historySize
                    val pts = FloatArray((n + 1) * 2)
                    for (h in 0 until n) {
                        val p = toImage(event.getHistoricalX(h), event.getHistoricalY(h) - cursorOffsetPx)
                        pts[h * 2] = p[0]
                        pts[h * 2 + 1] = p[1]
                    }
                    fingerX = event.x
                    fingerY = event.y
                    val p = toImage(fingerX, fingerY - cursorOffsetPx)
                    pts[n * 2] = p[0]
                    pts[n * 2 + 1] = p[1]
                    submit {
                        var dirty = IntRect.EMPTY
                        var i = 0
                        while (i < pts.size) {
                            dirty = dirty.union(st.moveTo(pts[i], pts[i + 1]))
                            i += 2
                        }
                        refresh(dirty)
                    }
                    invalidate()
                }
                Mode.GESTURE -> updateGesture(event)
                Mode.NONE -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.GESTURE) startGesture(event, skipIndex = event.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) {
                    val st = stroke
                    stroke = null
                    if (st != null) {
                        submit {
                            st.end()
                            mainHandler.post { onHistoryChanged?.invoke() }
                        }
                    }
                } else if (mode == Mode.GESTURE && currentScale() < fitScale) {
                    fit()
                }
                mode = Mode.NONE
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelStroke()
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    /** Toma como referencia el centro y la separación de los dedos que quedan en pantalla. */
    private fun startGesture(e: MotionEvent, skipIndex: Int) {
        val f = focusAndSpan(e, skipIndex)
        lastFocusX = f[0]
        lastFocusY = f[1]
        lastSpan = f[2]
    }

    private fun updateGesture(e: MotionEvent) {
        val f = focusAndSpan(e, -1)
        val fx = f[0]
        val fy = f[1]
        val span = f[2]
        if (lastSpan > 0f && span > 0f) {
            val sc = currentScale()
            val target = (sc * span / lastSpan).coerceIn(fitScale * 0.8f, fitScale * 20f)
            val factor = target / sc
            imageMatrix.postScale(factor, factor, fx, fy)
        }
        imageMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY)
        keepImageReachable()
        lastFocusX = fx
        lastFocusY = fy
        lastSpan = span
        invalidate()
    }

    private val gesture = FloatArray(3)

    private fun focusAndSpan(e: MotionEvent, skipIndex: Int): FloatArray {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n == 0) {
            gesture[0] = 0f; gesture[1] = 0f; gesture[2] = 0f
            return gesture
        }
        val cx = sx / n
        val cy = sy / n
        var dist = 0f
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            dist += hypot(e.getX(i) - cx, e.getY(i) - cy)
        }
        gesture[0] = cx
        gesture[1] = cy
        gesture[2] = if (n > 1) dist / n * 2f else 0f
        return gesture
    }

    /** Impide que la imagen se pierda fuera de la pantalla al moverla. */
    private fun keepImageReachable() {
        val s = state ?: return
        imageBounds.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        imageMatrix.mapRect(imageBounds)
        val minVisibleX = width * 0.25f
        val minVisibleY = height * 0.25f
        var dx = 0f
        var dy = 0f
        if (imageBounds.right < minVisibleX) dx = minVisibleX - imageBounds.right
        if (imageBounds.left > width - minVisibleX) dx = width - minVisibleX - imageBounds.left
        if (imageBounds.bottom < minVisibleY) dy = minVisibleY - imageBounds.bottom
        if (imageBounds.top > height - minVisibleY) dy = height - minVisibleY - imageBounds.top
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    private companion object {
        /** Radio máximo del modo Mágico en píxeles de imagen. */
        const val MAGIC_MAX_RADIUS = 256f
        const val MAGNIFICATION = 2.5f
    }
}
