package dev.quitafondo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S01 Inicio: elegir una foto con el selector del sistema o recibirla desde "Compartir". */
class MainActivity : AppCompatActivity() {

    private lateinit var progress: View

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()

        progress = findViewById(R.id.progress)
        progress.findViewById<TextView>(R.id.progressText).setText(R.string.loading_photo)

        findViewById<View>(R.id.pick).setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    private fun handleShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        open(uri)
    }

    private fun open(uri: Uri) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = resultOf {
                withContext(Dispatchers.IO) { Bitmaps.decode(contentResolver, uri) }
            }
            progress.visibility = View.GONE
            result
                .onSuccess { bitmap ->
                    Session.startNewPhoto(bitmap)
                    startActivity(Intent(this@MainActivity, CropActivity::class.java))
                }
                .onFailure { e ->
                    toast(if (e is OutOfMemoryError) R.string.error_memory else R.string.error_load)
                }
        }
    }
}
