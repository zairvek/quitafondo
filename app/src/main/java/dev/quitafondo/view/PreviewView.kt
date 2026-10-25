package dev.quitafondo.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.quitafondo.R
import dev.quitafondo.checkerPaint
import dev.quitafondo.color
import dev.quitafondo.dimen

/** Vista previa del resultado final sobre un tablero (PNG) o sobre blanco (JPG). */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    var whiteBackground: Boolean = false
        set(value) { field = value; invalidate() }

    private val padding = context.dimen(R.dimen.space_m)
    private val checker = context.checkerPaint()
    private val white = Paint().apply { color = context.color(R.color.backdrop_white) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        val s = minOf((width - 2 * padding) / b.width, (height - 2 * padding) / b.height)
        val w = b.width * s
        val h = b.height * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawRect(dst, if (whiteBackground) white else checker)
        canvas.drawBitmap(b, null, dst, bitmapPaint)
    }
}
