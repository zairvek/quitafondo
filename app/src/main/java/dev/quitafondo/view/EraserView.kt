package dev.quitafondo.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
import kotlin.math.hypot

/**
 * Lienzo de borrado.
 *
 * Un dedo dibuja con la herramienta activa. El punto que borra está [cursorOffsetPx] por encima del dedo
 * para que el dedo no tape lo que se borra. Dos dedos acercan y mueven la imagen; si el segundo dedo
 * llega a mitad de un trazo, ese trazo se deshace.
 */
class EraserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Avisa cuando cambia el historial (para activar o desactivar Deshacer y Rehacer). */
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

    private var state: EditorState? = null
    private var display: Bitmap? = null
    private var pixelBuffer = IntArray(0)

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

    fun setState(s: EditorState) {
        state = s
        display?.recycle()
        display = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        pixelBuffer = IntArray(s.width * s.height)
        refresh(s.fullRect)
        fit()
    }

    /** Vuelve a pintar la zona [r] de la imagen desde la máscara. */
    fun refresh(r: IntRect?) {
        val s = state ?: return
        val bmp = display ?: return
        if (r != null && !r.isEmpty) {
            s.composite(pixelBuffer, r)
            bmp.setPixels(pixelBuffer, r.top * s.width + r.left, s.width, r.left, r.top, r.width, r.height)
        }
        invalidate()
    }

    /** Deshace el trazo en curso, si lo hay (por ejemplo, si otro dedo pulsa Deshacer a la vez). */
    fun cancelStroke() {
        val st = stroke ?: return
        stroke = null
        mode = Mode.NONE
        refresh(st.cancel())
    }

    fun undo() {
        cancelStroke()
        val s = state ?: return
        refresh(s.history.undo(s.alpha, s.width))
        onHistoryChanged?.invoke()
    }

    fun redo() {
        cancelStroke()
        val s = state ?: return
        refresh(s.history.redo(s.alpha, s.width))
        onHistoryChanged?.invoke()
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stroke?.cancel()
        stroke = null
        display?.recycle()
        display = null
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
        canvas.drawColor(outsideColor)
        val s = state ?: return
        val bmp = display ?: return

        imageBounds.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        imageMatrix.mapRect(imageBounds)
        when (backdrop) {
            Backdrop.CHECKER -> canvas.drawRect(imageBounds, checker)
            Backdrop.WHITE -> canvas.drawRect(imageBounds, solidPaint.apply { color = context.color(R.color.backdrop_white) })
            Backdrop.GRAY -> canvas.drawRect(imageBounds, solidPaint.apply { color = context.color(R.color.backdrop_gray) })
            Backdrop.BLACK -> canvas.drawRect(imageBounds, solidPaint.apply { color = context.color(R.color.backdrop_black) })
        }
        // De cerca se ven los píxeles tal cual, para poder afinar.
        bitmapPaint.isFilterBitmap = currentScale() < 3f
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        when {
            mode == Mode.DRAW -> drawCursor(canvas, fingerX, fingerY)
            previewCursor -> drawCursor(canvas, width / 2f, height / 2f + cursorOffsetPx / 2f)
        }
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
                val st = Stroke(s, tool, brushRadiusInImage(), tolerance)
                stroke = st
                refresh(st.begin(p[0], p[1]))
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) {
                    refresh(stroke?.cancel())
                    stroke = null
                }
                mode = Mode.GESTURE
                startGesture(event, skipIndex = -1)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val st = stroke ?: return true
                    // Usa también los puntos intermedios que el sistema agrupa en un mismo evento.
                    for (h in 0 until event.historySize) {
                        val p = toImage(event.getHistoricalX(h), event.getHistoricalY(h) - cursorOffsetPx)
                        refresh(st.moveTo(p[0], p[1]))
                    }
                    fingerX = event.x
                    fingerY = event.y
                    val p = toImage(fingerX, fingerY - cursorOffsetPx)
                    refresh(st.moveTo(p[0], p[1]))
                }
                Mode.GESTURE -> updateGesture(event)
                Mode.NONE -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.GESTURE) startGesture(event, skipIndex = event.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) {
                    stroke?.end()
                    stroke = null
                    onHistoryChanged?.invoke()
                } else if (mode == Mode.GESTURE && currentScale() < fitScale) {
                    fit()
                }
                mode = Mode.NONE
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                refresh(stroke?.cancel())
                stroke = null
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
        const val MAGIC_MAX_RADIUS = 320f
    }
}
