package dev.quitafondo.engine

/**
 * Todo lo que el editor necesita, sin dependencias de Android.
 *
 * [original] guarda los píxeles ARGB de la foto recortada y nunca cambia.
 * [alpha] es la máscara editable: 0 = borrado, 255 = visible.
 */
class EditorState(
    val width: Int,
    val height: Int,
    val original: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Tamaño inválido: ${width}x$height" }
        require(original.size == width * height) { "original no coincide con el tamaño" }
    }

    /** Empieza con el alfa propio de la foto, así un PNG ya recortado conserva su transparencia. */
    val alpha: ByteArray = ByteArray(width * height) { (original[it] ushr 24).toByte() }

    /** Copia de [alpha] tomada al empezar cada trazo. Se reutiliza para no reservar memoria en cada toque. */
    internal val snapshot: ByteArray = ByteArray(width * height)

    /** Mapa de bordes 0..255, calculado bajo demanda para el modo Mágico. */
    @Volatile
    var edges: ByteArray? = null

    val history = UndoStack(maxBytes = 48L * 1024 * 1024)

    val fullRect: IntRect get() = IntRect(0, 0, width, height)

    fun alphaAt(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    fun ensureEdges(): ByteArray = edges ?: ImageOps.sobelEdges(original, width, height).also { edges = it }

    /** Reemplaza la máscara entera (por ejemplo, tras el recorte automático) como un solo paso deshacible. */
    fun replaceMask(newAlpha: ByteArray) {
        require(newAlpha.size == alpha.size)
        val before = alpha.copyOf()
        newAlpha.copyInto(alpha)
        history.push(fullRect, before, alpha.copyOf())
    }

    /** Píxeles ARGB con la máscara aplicada, listos para dibujar o guardar. */
    fun composite(out: IntArray, rect: IntRect = fullRect, mask: ByteArray = alpha) {
        for (y in rect.top until rect.bottom) {
            var i = y * width + rect.left
            for (x in rect.left until rect.right) {
                val a = mask[i].toInt() and 0xFF
                out[i] = (a shl 24) or (original[i] and 0x00FFFFFF)
                i++
            }
        }
    }

    fun visiblePixelCount(): Int = alpha.count { it != ZERO }

    companion object {
        const val FULL: Byte = -1 // 255 sin signo
        const val ZERO: Byte = 0
    }
}
