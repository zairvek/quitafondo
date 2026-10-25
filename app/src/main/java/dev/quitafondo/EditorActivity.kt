package dev.quitafondo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import dev.quitafondo.engine.EditorState
import dev.quitafondo.engine.Tool
import dev.quitafondo.view.EraserView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S04 Editor: borrar el fondo con Borrar, Mágico, Color, Reparar y Persona. */
class EditorActivity : AppCompatActivity() {

    private lateinit var state: EditorState
    private lateinit var eraser: EraserView
    private lateinit var undo: View
    private lateinit var redo: View
    private lateinit var hint: TextView
    private lateinit var progress: View
    private lateinit var progressText: TextView
    private lateinit var rowTolerance: View

    /** Tareas largas en curso (bordes, persona). La capa de espera se quita cuando llega a 0. */
    private var busyJobs = 0
    private var edgesRunning = false
    private var personRunning = false

    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(250).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        state = Session.editor ?: run { restartFromHome(); return }

        setContentView(R.layout.activity_editor)
        findViewById<View>(R.id.root).padForSystemBars()

        eraser = findViewById(R.id.eraser)
        undo = findViewById(R.id.undo)
        redo = findViewById(R.id.redo)
        hint = findViewById(R.id.hint)
        progress = findViewById(R.id.progress)
        progressText = progress.findViewById(R.id.progressText)
        rowTolerance = findViewById(R.id.rowTolerance)

        eraser.setState(state)
        eraser.backdrop = Session.backdrop
        eraser.onHistoryChanged = { updateHistoryButtons() }

        if (Session.brushDp < 0) Session.brushDp = resources.getInteger(R.integer.brush_default_dp)
        if (Session.offsetDp < 0) Session.offsetDp = resources.getInteger(R.integer.offset_default_dp)
        if (Session.tolerance < 0) Session.tolerance = resources.getInteger(R.integer.tolerance_default)

        setupSlider(
            findViewById(R.id.rowSize), R.string.slider_size,
            resources.getInteger(R.integer.brush_min_dp), resources.getInteger(R.integer.brush_max_dp),
            Session.brushDp, preview = true,
        ) { v -> Session.brushDp = v; eraser.brushRadiusPx = dp(v.toFloat()) }
        setupSlider(
            findViewById(R.id.rowOffset), R.string.slider_offset,
            0, resources.getInteger(R.integer.offset_max_dp),
            Session.offsetDp, preview = true,
        ) { v -> Session.offsetDp = v; eraser.cursorOffsetPx = dp(v.toFloat()) }
        setupSlider(rowTolerance, R.string.slider_tolerance, 0, 100, Session.tolerance, preview = false) { v ->
            Session.tolerance = v
            eraser.tolerance = v
        }

        val tools = findViewById<MaterialButtonToggleGroup>(R.id.tools)
        tools.check(buttonFor(Session.tool))
        selectTool(Session.tool, showHint = true)
        tools.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) selectTool(toolFor(checkedId), showHint = true)
        }

        findViewById<View>(R.id.toolPerson).setOnClickListener { runPersonCutout() }
        findViewById<View>(R.id.backdrop).setOnClickListener {
            Session.backdrop = Session.backdrop.next()
            eraser.backdrop = Session.backdrop
        }
        undo.setOnClickListener { eraser.undo() }
        redo.setOnClickListener { eraser.redo() }
        findViewById<View>(R.id.back).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.done).setOnClickListener {
            startActivity(Intent(this, SaveActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this) { confirmExit() }

        updateHistoryButtons()
    }

    override fun onDestroy() {
        if (::hint.isInitialized) hint.removeCallbacks(hideHint)
        super.onDestroy()
    }

    private fun setupSlider(
        row: View,
        @StringRes label: Int,
        from: Int,
        to: Int,
        value: Int,
        preview: Boolean,
        onChange: (Int) -> Unit,
    ) {
        row.findViewById<TextView>(R.id.label).setText(label)
        val slider = row.findViewById<Slider>(R.id.slider)
        slider.valueTo = to.toFloat()
        slider.valueFrom = from.toFloat()
        slider.value = value.coerceIn(from, to).toFloat()
        onChange(slider.value.toInt())
        slider.addOnChangeListener { _, v, _ -> onChange(v.toInt()) }
        if (preview) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { eraser.previewCursor = true }
                override fun onStopTrackingTouch(slider: Slider) { eraser.previewCursor = false }
            })
        }
    }

    private fun buttonFor(tool: Tool): Int = when (tool) {
        Tool.MANUAL -> R.id.toolManual
        Tool.MAGIC -> R.id.toolMagic
        Tool.COLOR -> R.id.toolColor
        Tool.REPAIR -> R.id.toolRepair
    }

    private fun toolFor(id: Int): Tool = when (id) {
        R.id.toolMagic -> Tool.MAGIC
        R.id.toolColor -> Tool.COLOR
        R.id.toolRepair -> Tool.REPAIR
        else -> Tool.MANUAL
    }

    private fun selectTool(tool: Tool, showHint: Boolean) {
        Session.tool = tool
        eraser.tool = tool
        // Invisible y no "gone": así el lienzo no cambia de tamaño y se conserva el zoom.
        rowTolerance.isInvisible = !(tool == Tool.MAGIC || tool == Tool.COLOR)
        if (showHint) {
            showHint(
                when (tool) {
                    Tool.MANUAL -> R.string.hint_manual
                    Tool.MAGIC -> R.string.hint_magic
                    Tool.COLOR -> R.string.hint_color
                    Tool.REPAIR -> R.string.hint_repair
                },
            )
        }
        if (tool == Tool.MAGIC && state.edges == null && !edgesRunning) {
            // El mapa de bordes se calcula una vez por foto, fuera del hilo principal.
            edgesRunning = true
            setBusy(true, R.string.finding_edges)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.Default) { state.ensureEdges() }
                } finally {
                    edgesRunning = false
                    setBusy(false)
                }
            }
        }
    }

    private fun showHint(@StringRes text: Int) {
        hint.removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.text = getString(text) + "\n" + getString(R.string.hint_zoom)
        hint.alpha = 1f
        hint.postDelayed(hideHint, 3500)
    }

    private fun runPersonCutout() {
        if (personRunning) return
        personRunning = true
        setBusy(true, R.string.finding_person)
        lifecycleScope.launch {
            val result = try {
                resultOf { withContext(Dispatchers.Default) { AutoCutout.personMask(state) } }
            } finally {
                personRunning = false
                setBusy(false)
            }
            result
                .onSuccess { mask ->
                    if (mask == null) {
                        toast(R.string.no_person)
                    } else {
                        state.replaceMask(mask)
                        eraser.refresh(state.fullRect)
                        updateHistoryButtons()
                    }
                }
                .onFailure { toast(R.string.person_failed) }
        }
    }

    private fun setBusy(busy: Boolean, @StringRes text: Int = 0) {
        busyJobs = if (busy) busyJobs + 1 else (busyJobs - 1).coerceAtLeast(0)
        if (busy && text != 0) progressText.setText(text)
        val any = busyJobs > 0
        eraser.busy = any
        progress.isVisible = any
    }

    private fun updateHistoryButtons() {
        setEnabledLook(undo, state.history.canUndo)
        setEnabledLook(redo, state.history.canRedo)
    }

    private fun setEnabledLook(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    private fun confirmExit() {
        if (!state.history.canUndo) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.discard_no, null)
            .show()
    }
}
