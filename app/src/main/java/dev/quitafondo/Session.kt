package dev.quitafondo

import android.graphics.Bitmap
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.Tool

/**
 * Estado de la foto en curso, compartido entre pantallas mientras la app está abierta.
 * Si Android cierra el proceso en segundo plano se pierde, y cada pantalla vuelve al inicio.
 */
object Session {
    /** Foto decodificada (reducida) que se muestra en la pantalla de recorte. */
    var loaded: Bitmap? = null

    /** Foto recortada en edición. */
    var editor: EditorState? = null

    /** Controles del editor; se conservan entre fotos para no reajustarlos cada vez. */
    var tool: Tool = Tool.MANUAL
    var brushDp: Int = -1
    var offsetDp: Int = -1
    var tolerance: Int = -1
    var backdrop: Backdrop = Backdrop.CHECKER

    fun startNewPhoto(bitmap: Bitmap) {
        loaded = bitmap
        editor = null
    }

    fun clear() {
        loaded = null
        editor = null
    }
}

/** Fondo de contraste detrás de la imagen, para ver restos del fondo original. */
enum class Backdrop {
    CHECKER, WHITE, GRAY, BLACK;

    fun next(): Backdrop = entries[(ordinal + 1) % entries.size]
}
