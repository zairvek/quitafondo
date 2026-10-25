package dev.quitafondo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.quitafondo.view.CropView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S03 Recorte: encuadrar el objeto antes de borrar. */
class CropActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        val bitmap = Session.loaded ?: run { restartFromHome(); return }

        setContentView(R.layout.activity_crop)
        findViewById<View>(R.id.root).padForSystemBars()

        val cropView = findViewById<CropView>(R.id.cropView)
        val progress = findViewById<View>(R.id.progress)
        progress.findViewById<TextView>(R.id.progressText).setText(R.string.preparing)
        cropView.setBitmap(bitmap)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.reset).setOnClickListener { cropView.reset() }
        findViewById<View>(R.id.done).setOnClickListener {
            val rect = cropView.cropRect()
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = resultOf {
                    withContext(Dispatchers.Default) { Bitmaps.toEditorState(bitmap, rect) }
                }
                progress.visibility = View.GONE
                result
                    .onSuccess { state ->
                        Session.editor = state
                        startActivity(Intent(this@CropActivity, EditorActivity::class.java))
                    }
                    .onFailure { e ->
                        toast(if (e is OutOfMemoryError) R.string.error_memory else R.string.error_load)
                    }
            }
        }
    }
}
