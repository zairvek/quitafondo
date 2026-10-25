package dev.quitafondo.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

enum class Tool {
    /** Borra todo lo que toca el pincel. */
    MANUAL,

    /** Borra la zona parecida al centro del pincel sin cruzar los bordes de la imagen. */
    MAGIC,

    /** Borra, dentro del pincel, los píxeles de color parecido al del primer toque. */
    COLOR,

    /** Devuelve la foto original donde pasa el pincel. */
    REPAIR,
}

/**
 * Un trazo con el dedo: empieza con [begin], sigue con [moveTo] y termina con [end] o [cancel].
 * Las coordenadas están en píxeles de imagen.
 *
 * [tolerance] va de 0 a 100: qué tan distinto puede ser un color y seguir contando como "parecido".
 */
class Stroke(
    private val state: EditorState,
    val tool: Tool,
    private val radius: Float,
    tolerance: Int,
) {
    private val w = state.width
    private val h = state.height
    private val tolSq: Int
    private val edgeLimit: Int

    private var lastX = 0f
    private var lastY = 0f
    private var seedColor = 0
    private var bounds = IntRect.EMPTY
    private var started = false
    private var finished = false

    // Búfer para el relleno del modo Mágico, reutilizado entre toques.
    private var queue = IntArray(0)
    private var visited = BooleanArray(0)

    init {
        val t = tolerance.coerceIn(0, 100)
        // 0 -> distancia 4, 100 -> distancia ~140 en la escala de colorDistSq.
        val d = 4 + t * 136 / 100
        tolSq = d * d
        // Cuanto más tolerante, más fuerte tiene que ser un borde para detener el relleno.
        edgeLimit = 24 + t * 96 / 100
        if (tool == Tool.MAGIC) state.ensureEdges()
    }

    /** Rectángulo total tocado por este trazo hasta ahora. */
    val touched: IntRect get() = bounds

    fun begin(x: Float, y: Float): IntRect {
        check(!started) { "begin() ya se llamó" }
        started = true
        state.alpha.copyInto(state.snapshot)
        val ix = x.toInt().coerceIn(0, w - 1)
        val iy = y.toInt().coerceIn(0, h - 1)
        seedColor = state.original[iy * w + ix]
        lastX = x
        lastY = y
        return dab(x, y)
    }

    /** Avanza el trazo y devuelve el rectángulo que cambió (para redibujar solo eso). */
    fun moveTo(x: Float, y: Float): IntRect {
        check(started && !finished)
        val dist = hypot(x - lastX, y - lastY)
        val step = max(1f, radius * 0.25f)
        val n = ceil(dist / step).toInt()
        var dirty = IntRect.EMPTY
        for (i in 1..n) {
            val t = i.toFloat() / n
            dirty = dirty.union(dab(lastX + (x - lastX) * t, lastY + (y - lastY) * t))
        }
        lastX = x
        lastY = y
        return dirty
    }

    /** Cierra el trazo y lo guarda en el historial. */
    fun end() {
        if (!started || finished) return
        finished = true
        state.history.push(bounds, state.snapshot, state.alpha, w)
    }

    /** Deshace el trazo en curso sin dejar rastro en el historial. Devuelve la zona a redibujar. */
    fun cancel(): IntRect {
        if (!started || finished) return IntRect.EMPTY
        finished = true
        if (!bounds.isEmpty) {
            val saved = UndoStack.extract(state.snapshot, w, bounds)
            UndoStack.write(state.alpha, w, bounds, saved)
        }
        return bounds
    }

    private fun dab(cx: Float, cy: Float): IntRect {
        val r = radius
        val rect = IntRect(
            floor(cx - r).toInt(), floor(cy - r).toInt(),
            ceil(cx + r).toInt() + 1, ceil(cy + r).toInt() + 1,
        ).clampTo(w, h)
        if (rect.isEmpty) return IntRect.EMPTY
        when (tool) {
            Tool.MANUAL -> paintCircle(cx, cy, rect, restore = false, colorTest = false)
            Tool.REPAIR -> paintCircle(cx, cy, rect, restore = true, colorTest = false)
            Tool.COLOR -> paintCircle(cx, cy, rect, restore = false, colorTest = true)
            Tool.MAGIC -> magicFill(cx, cy, rect)
        }
        bounds = bounds.union(rect)
        return rect
    }

    /** [restore] = true devuelve el alfa original de la foto; false lo pone a 0. */
    private fun paintCircle(cx: Float, cy: Float, rect: IntRect, restore: Boolean, colorTest: Boolean) {
        val r2 = radius * radius
        val alpha = state.alpha
        val orig = state.original
        for (y in rect.top until rect.bottom) {
            val dy = y + 0.5f - cy
            val row = y * w
            for (x in rect.left until rect.right) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy > r2) continue
                val i = row + x
                if (colorTest && ImageOps.colorDistSq(orig[i], seedColor) > tolSq) continue
                alpha[i] = if (restore) (orig[i] ushr 24).toByte() else EditorState.ZERO
            }
        }
    }

    /**
     * Relleno por inundación desde el centro del pincel, limitado al círculo.
     * Avanza mientras el color se parezca al del centro y no haya un borde fuerte.
     */
    private fun magicFill(cx: Float, cy: Float, rect: IntRect) {
        val edges = state.ensureEdges()
        val sx = cx.toInt().coerceIn(rect.left, rect.right - 1)
        val sy = cy.toInt().coerceIn(rect.top, rect.bottom - 1)
        val rw = rect.width
        val rh = rect.height
        val area = rw * rh
        if (queue.size < area) queue = IntArray(area)
        if (visited.size < area) visited = BooleanArray(area) else visited.fill(false, 0, area)

        val r2 = radius * radius
        val orig = state.original
        val alpha = state.alpha
        val center = orig[sy * w + sx]

        var head = 0
        var tail = 0
        val startLocal = (sy - rect.top) * rw + (sx - rect.left)
        queue[tail++] = startLocal
        visited[startLocal] = true

        while (head < tail) {
            val local = queue[head++]
            val lx = local % rw
            val ly = local / rw
            val x = lx + rect.left
            val y = ly + rect.top
            alpha[y * w + x] = EditorState.ZERO

            // Cuatro vecinos.
            for (dir in 0 until 4) {
                val nx = when (dir) { 0 -> lx - 1; 1 -> lx + 1; else -> lx }
                val ny = when (dir) { 2 -> ly - 1; 3 -> ly + 1; else -> ly }
                if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                val nLocal = ny * rw + nx
                if (visited[nLocal]) continue
                visited[nLocal] = true
                val gx = nx + rect.left
                val gy = ny + rect.top
                val dx = gx + 0.5f - cx
                val dy = gy + 0.5f - cy
                if (dx * dx + dy * dy > r2) continue
                val gi = gy * w + gx
                if ((edges[gi].toInt() and 0xFF) > edgeLimit) continue
                if (ImageOps.colorDistSq(orig[gi], center) > tolSq) continue
                queue[tail++] = nLocal
            }
        }
    }
}
