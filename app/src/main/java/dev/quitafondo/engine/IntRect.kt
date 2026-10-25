package dev.quitafondo.engine

/**
 * Rectángulo en píxeles de imagen. [right] y [bottom] son exclusivos.
 * Inmutable para poder guardarlo en el historial sin copias defensivas.
 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun union(other: IntRect): IntRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> IntRect(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom),
        )
    }

    fun clampTo(w: Int, h: Int): IntRect = IntRect(
        left.coerceIn(0, w), top.coerceIn(0, h),
        right.coerceIn(0, w), bottom.coerceIn(0, h),
    )

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}
