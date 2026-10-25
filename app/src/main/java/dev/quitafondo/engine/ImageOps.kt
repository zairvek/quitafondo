package dev.quitafondo.engine

import kotlin.math.sqrt

/** Operaciones de imagen sobre arreglos. Sin Android, para poder probarlas en la JVM. */
object ImageOps {

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** Distancia de color al cuadrado en RGB, con pesos aproximados a la percepción. */
    fun colorDistSq(a: Int, b: Int): Int {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return (2 * dr * dr + 4 * dg * dg + 3 * db * db) / 3
    }

    /** Luminancia 0..255 (BT.601 en enteros). */
    fun luma(c: Int): Int = (red(c) * 77 + green(c) * 150 + blue(c) * 29) shr 8

    /**
     * Magnitud del gradiente de Sobel sobre la luminancia, escalada a 0..255.
     * Los píxeles del borde de la imagen quedan en 0.
     */
    fun sobelEdges(argb: IntArray, w: Int, h: Int): ByteArray {
        val l = IntArray(w * h) { luma(argb[it]) }
        val out = ByteArray(w * h)
        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = (l[r0 + x + 1] + 2 * l[r1 + x + 1] + l[r2 + x + 1]) -
                    (l[r0 + x - 1] + 2 * l[r1 + x - 1] + l[r2 + x - 1])
                val gy = (l[r2 + x - 1] + 2 * l[r2 + x] + l[r2 + x + 1]) -
                    (l[r0 + x - 1] + 2 * l[r0 + x] + l[r0 + x + 1])
                // Máximo teórico ~1442; /4 deja los bordes nítidos cerca de 255.
                val m = (sqrt((gx * gx + gy * gy).toDouble()) / 4.0).toInt()
                out[r1 + x] = m.coerceAtMost(255).toByte()
            }
        }
        return out
    }

    /**
     * Suaviza el borde de la máscara con un desenfoque de caja separable aplicado dos veces
     * (aproxima un gaussiano). [radius] 0 devuelve una copia sin cambios.
     */
    fun smoothMask(mask: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return mask.copyOf()
        var src = IntArray(w * h) { mask[it].toInt() and 0xFF }
        var tmp = IntArray(w * h)
        repeat(2) {
            boxBlurH(src, tmp, w, h, radius)
            boxBlurV(tmp, src, w, h, radius)
        }
        return ByteArray(w * h) { src[it].toByte() }
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = (sum + div / 2) / div
                val outIdx = (x - r).coerceIn(0, w - 1)
                val inIdx = (x + r + 1).coerceIn(0, w - 1)
                sum += src[row + inIdx] - src[row + outIdx]
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (k in -r..r) sum += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum + div / 2) / div
                val outIdx = (y - r).coerceIn(0, h - 1)
                val inIdx = (y + r + 1).coerceIn(0, h - 1)
                sum += src[inIdx * w + x] - src[outIdx * w + x]
            }
        }
    }

    /**
     * Convierte la confianza del modelo de segmentación (0..1 por píxel) en alfa,
     * con una rampa suave entre [low] y [high] para no dejar bordes dentados.
     */
    fun confidenceToAlpha(conf: FloatArray, low: Float = 0.35f, high: Float = 0.65f): ByteArray {
        val span = high - low
        return ByteArray(conf.size) {
            val t = ((conf[it] - low) / span).coerceIn(0f, 1f)
            (t * 255f + 0.5f).toInt().toByte()
        }
    }

    /** Combina dos máscaras quedándose con lo más transparente de cada píxel. */
    fun minMask(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { minOf(a[it].toInt() and 0xFF, b[it].toInt() and 0xFF).toByte() }

    /**
     * Rectángulo más pequeño que contiene todos los píxeles con alfa > 0,
     * para recortar el espacio vacío al guardar. Vacío si todo está borrado.
     */
    fun opaqueBounds(mask: ByteArray, w: Int, h: Int): IntRect {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mask[row + x] != EditorState.ZERO) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) IntRect.EMPTY else IntRect(l, t, r + 1, b + 1)
    }
}
