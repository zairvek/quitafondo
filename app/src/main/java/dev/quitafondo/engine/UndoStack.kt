package dev.quitafondo.engine

/**
 * Historial de la máscara. Cada paso guarda solo el rectángulo que cambió
 * (antes y después), así cien trazos pequeños ocupan poco.
 * Cuando se pasa de [maxBytes] se descartan los pasos más viejos.
 */
class UndoStack(private val maxBytes: Long) {

    private class Step(val rect: IntRect, val before: ByteArray, val after: ByteArray) {
        val bytes: Long get() = before.size.toLong() + after.size
    }

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    private var usedBytes = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoCount: Int get() = undo.size

    /** Memoria que ocupa la pila de deshacer (la de rehacer no cuenta: se descarta con el próximo cambio). */
    val bytesInUse: Long get() = usedBytes

    /** [before] y [after] son copias completas de la máscara; aquí se recorta a [rect]. */
    fun push(rect: IntRect, before: ByteArray, after: ByteArray, maskWidth: Int) {
        if (rect.isEmpty) return
        val b = extract(before, maskWidth, rect)
        val a = extract(after, maskWidth, rect)
        if (b.contentEquals(a)) return
        add(Step(rect, b, a))
    }

    /** Variante para cambios que ya vienen recortados a la máscara completa. */
    fun push(rect: IntRect, before: ByteArray, after: ByteArray) {
        if (rect.isEmpty) return
        require(before.size == rect.width * rect.height && after.size == before.size)
        if (before.contentEquals(after)) return
        add(Step(rect, before, after))
    }

    private fun add(step: Step) {
        clearRedo()
        undo.addLast(step)
        usedBytes += step.bytes
        while (usedBytes > maxBytes && undo.size > 1) {
            usedBytes -= undo.removeFirst().bytes
        }
    }

    /** Devuelve el rectángulo que cambió, o null si no había nada. */
    fun undo(mask: ByteArray, maskWidth: Int): IntRect? {
        val step = undo.removeLastOrNull() ?: return null
        write(mask, maskWidth, step.rect, step.before)
        redo.addLast(step)
        usedBytes -= step.bytes
        return step.rect
    }

    fun redo(mask: ByteArray, maskWidth: Int): IntRect? {
        val step = redo.removeLastOrNull() ?: return null
        write(mask, maskWidth, step.rect, step.after)
        undo.addLast(step)
        usedBytes += step.bytes
        return step.rect
    }

    fun clear() {
        undo.clear()
        clearRedo()
        usedBytes = 0
    }

    private fun clearRedo() {
        // Los pasos de rehacer no cuentan en usedBytes: solo viven hasta el próximo cambio.
        redo.clear()
    }

    companion object {
        fun extract(src: ByteArray, w: Int, r: IntRect): ByteArray {
            val out = ByteArray(r.width * r.height)
            for (row in 0 until r.height) {
                System.arraycopy(src, (r.top + row) * w + r.left, out, row * r.width, r.width)
            }
            return out
        }

        fun write(dst: ByteArray, w: Int, r: IntRect, data: ByteArray) {
            for (row in 0 until r.height) {
                System.arraycopy(data, row * r.width, dst, (r.top + row) * w + r.left, r.width)
            }
        }
    }
}
