package dev.quitafondo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException

/**
 * Como runCatching, pero deja pasar la cancelación de corrutinas: si la pantalla se cierra o gira
 * a mitad de una tarea, no se muestra un error falso.
 */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

fun Context.dimen(@DimenRes id: Int): Float = resources.getDimension(id)

fun Context.toast(textRes: Int) = Toast.makeText(this, textRes, Toast.LENGTH_LONG).show()

/** Dibuja detrás de las barras del sistema con iconos claros: la app siempre usa fondo oscuro. */
fun ComponentActivity.edgeToEdgeDark() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
)

/** La app dibuja detrás de las barras del sistema; esto deja el contenido dentro de la zona segura. */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/** Vuelve al inicio cuando la sesión se perdió (por ejemplo, si Android cerró la app en segundo plano). */
fun Activity.restartFromHome() {
    toast(R.string.session_lost)
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    finish()
}

/** Pintura de tablero de ajedrez con casillas de tamaño fijo en pantalla. */
fun Context.checkerPaint(): Paint {
    val tile = dimen(R.dimen.checker_tile).toInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(tile * 2, tile * 2, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val light = Paint().apply { color = color(R.color.checker_light) }
    val dark = Paint().apply { color = color(R.color.checker_dark) }
    c.drawRect(0f, 0f, tile * 2f, tile * 2f, light)
    c.drawRect(tile.toFloat(), 0f, tile * 2f, tile.toFloat(), dark)
    c.drawRect(0f, tile.toFloat(), tile.toFloat(), tile * 2f, dark)
    return Paint().apply {
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
