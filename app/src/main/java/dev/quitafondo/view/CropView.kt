package dev.quitafondo.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.quitafondo.R
import dev.quitafondo.color
import dev.quitafondo.dimen
import kotlin.math.abs
import kotlin.math.roundToInt

/** Muestra la foto con un marco de recorte que se ajusta desde las esquinas, los lados o moviéndolo entero. */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    /** Marco en píxeles de la imagen. */
    private val crop = RectF()

    private val toView = Matrix()
    private var scale = 1f
    private val viewCrop = RectF()
    private val imageInView = RectF()

    private val padding = context.dimen(R.dimen.space_l)
    private val touchSlop = context.dimen(R.dimen.crop_touch_slop)
    private val minSizeView = context.dimen(R.dimen.crop_min_size)
    private val handleLen = context.dimen(R.dimen.crop_handle) * 1.6f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = context.color(R.color.crop_dim) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.crop_line)
        color = context.color(R.color.crop_line)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.crop_grid_line)
        color = context.color(R.color.crop_grid)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dimen(R.dimen.crop_line) * 2f
        strokeCap = Paint.Cap.ROUND
        color = context.color(R.color.crop_line)
    }

    // Qué partes del marco se están arrastrando.
    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var dragMove = false
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(b: Bitmap) {
        bitmap = b
        reset()
    }

    fun reset() {
        val b = bitmap ?: return
        crop.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        layoutImage()
        invalidate()
    }

    /** Marco elegido, en píxeles de la imagen. */
    fun cropRect(): Rect {
        val b = bitmap ?: return Rect()
        val r = Rect(
            crop.left.roundToInt(), crop.top.roundToInt(),
            crop.right.roundToInt(), crop.bottom.roundToInt(),
        )
        r.intersect(0, 0, b.width, b.height)
        return r
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = minOf((width - 2 * padding) / b.width, (height - 2 * padding) / b.height)
        toView.setScale(scale, scale)
        toView.postTranslate((width - b.width * scale) / 2f, (height - b.height * scale) / 2f)
        imageInView.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        toView.mapRect(imageInView)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, toView, bitmapPaint)
        viewCrop.set(crop)
        toView.mapRect(viewCrop)

        // Oscurece lo que queda fuera del marco.
        canvas.drawRect(imageInView.left, imageInView.top, imageInView.right, viewCrop.top, dimPaint)
        canvas.drawRect(imageInView.left, viewCrop.bottom, imageInView.right, imageInView.bottom, dimPaint)
        canvas.drawRect(imageInView.left, viewCrop.top, viewCrop.left, viewCrop.bottom, dimPaint)
        canvas.drawRect(viewCrop.right, viewCrop.top, imageInView.right, viewCrop.bottom, dimPaint)

        // Tercios.
        val tw = viewCrop.width() / 3f
        val th = viewCrop.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(viewCrop.left + tw * i, viewCrop.top, viewCrop.left + tw * i, viewCrop.bottom, gridPaint)
            canvas.drawLine(viewCrop.left, viewCrop.top + th * i, viewCrop.right, viewCrop.top + th * i, gridPaint)
        }
        canvas.drawRect(viewCrop, linePaint)

        // Esquinas en forma de L.
        val l = viewCrop.left; val t = viewCrop.top; val r = viewCrop.right; val bt = viewCrop.bottom
        val hl = minOf(handleLen, viewCrop.width() / 3f, viewCrop.height() / 3f)
        canvas.drawLine(l, t, l + hl, t, handlePaint); canvas.drawLine(l, t, l, t + hl, handlePaint)
        canvas.drawLine(r, t, r - hl, t, handlePaint); canvas.drawLine(r, t, r, t + hl, handlePaint)
        canvas.drawLine(l, bt, l + hl, bt, handlePaint); canvas.drawLine(l, bt, l, bt - hl, handlePaint)
        canvas.drawLine(r, bt, r - hl, bt, handlePaint); canvas.drawLine(r, bt, r, bt - hl, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                viewCrop.set(crop)
                toView.mapRect(viewCrop)
                val x = event.x
                val y = event.y
                val inY = y > viewCrop.top - touchSlop && y < viewCrop.bottom + touchSlop
                val inX = x > viewCrop.left - touchSlop && x < viewCrop.right + touchSlop
                dragLeft = inY && abs(x - viewCrop.left) < touchSlop
                dragRight = inY && abs(x - viewCrop.right) < touchSlop && !dragLeft
                dragTop = inX && abs(y - viewCrop.top) < touchSlop
                dragBottom = inX && abs(y - viewCrop.bottom) < touchSlop && !dragTop
                dragMove = !(dragLeft || dragRight || dragTop || dragBottom) && viewCrop.contains(x, y)
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) / scale
                val dy = (event.y - lastY) / scale
                lastX = event.x
                lastY = event.y
                val w = b.width.toFloat()
                val h = b.height.toFloat()
                // En fotos diminutas el mínimo no puede pasar de la mitad, o los rangos quedarían vacíos.
                val minW = minOf(minSizeView / scale, w / 2f)
                val minH = minOf(minSizeView / scale, h / 2f)
                if (dragMove) {
                    val mx = dx.coerceIn(-crop.left, w - crop.right)
                    val my = dy.coerceIn(-crop.top, h - crop.bottom)
                    crop.offset(mx, my)
                } else {
                    if (dragLeft) crop.left = (crop.left + dx).coerceIn(0f, maxOf(0f, crop.right - minW))
                    if (dragRight) crop.right = (crop.right + dx).coerceIn(minOf(crop.left + minW, w), w)
                    if (dragTop) crop.top = (crop.top + dy).coerceIn(0f, maxOf(0f, crop.bottom - minH))
                    if (dragBottom) crop.bottom = (crop.bottom + dy).coerceIn(minOf(crop.top + minH, h), h)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragLeft = false; dragRight = false; dragTop = false; dragBottom = false; dragMove = false
            }
        }
        return true
    }
}
