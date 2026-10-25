package dev.quitafondo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE02020.toInt()
    private val blue = 0xFF2040E0.toInt()

    /** Imagen 100x100: fondo blanco con un cuadrado rojo de 40x40 en el centro (30..69). */
    private fun squareImage(): EditorState {
        val w = 100
        val h = 100
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 30..69 && y in 30..69) red else white
        }
        return EditorState(w, h, px)
    }

    private fun EditorState.a(x: Int, y: Int) = alphaAt(x, y)

    @Test
    fun manualErasesCircleOnly() {
        val s = squareImage()
        Stroke(s, Tool.MANUAL, radius = 5f, tolerance = 50).apply { begin(50f, 50f); end() }
        assertEquals(0, s.a(50, 50))
        assertEquals(0, s.a(53, 50))
        assertEquals(255, s.a(60, 50))
        assertTrue(s.history.canUndo)
    }

    @Test
    fun strokeInterpolatesBetweenPoints() {
        val s = squareImage()
        Stroke(s, Tool.MANUAL, radius = 3f, tolerance = 50).apply {
            begin(10f, 10f); moveTo(90f, 10f); end()
        }
        for (x in 10..90 step 5) assertEquals("x=$x", 0, s.a(x, 10))
        assertEquals(255, s.a(50, 20))
    }

    @Test
    fun repairRestoresErased() {
        val s = squareImage()
        Stroke(s, Tool.MANUAL, 10f, 50).apply { begin(50f, 50f); end() }
        Stroke(s, Tool.REPAIR, 4f, 50).apply { begin(50f, 50f); end() }
        assertEquals(255, s.a(50, 50))
        assertEquals(0, s.a(57, 50))
    }

    @Test
    fun colorModeKeepsDifferentColors() {
        val s = squareImage()
        // Pincel grande que cubre fondo y cuadrado; empieza sobre el blanco.
        Stroke(s, Tool.COLOR, radius = 30f, tolerance = 30).apply { begin(25f, 50f); end() }
        assertEquals("fondo blanco borrado", 0, s.a(10, 50))
        assertEquals("rojo intacto", 255, s.a(35, 50))
    }

    @Test
    fun magicStopsAtEdges() {
        val s = squareImage()
        // Centro en el fondo, el círculo se mete en el cuadrado.
        Stroke(s, Tool.MAGIC, radius = 20f, tolerance = 40).apply { begin(22f, 50f); end() }
        assertEquals(0, s.a(22, 50))
        assertEquals(0, s.a(10, 50))
        assertEquals("el borde frena el relleno", 255, s.a(32, 50))
        assertEquals(255, s.a(38, 50))
    }

    @Test
    fun magicDoesNotJumpToSeparateRegionOfSameColor() {
        // Fondo blanco, barra azul vertical en x=48..52 que separa dos zonas blancas.
        val w = 100
        val h = 40
        val px = IntArray(w * h) { if ((it % w) in 48..52) blue else white }
        val s = EditorState(w, h, px)
        Stroke(s, Tool.MAGIC, radius = 30f, tolerance = 40).apply { begin(40f, 20f); end() }
        assertEquals(0, s.a(40, 20))
        assertEquals("barra intacta", 255, s.a(50, 20))
        assertEquals("el otro lado no se toca", 255, s.a(60, 20))
    }

    @Test
    fun undoRedoRoundTrip() {
        val s = squareImage()
        Stroke(s, Tool.MANUAL, 8f, 50).apply { begin(20f, 20f); end() }
        Stroke(s, Tool.MANUAL, 8f, 50).apply { begin(80f, 80f); end() }
        assertEquals(0, s.a(80, 80))

        s.history.undo(s.alpha, s.width)
        assertEquals(255, s.a(80, 80))
        assertEquals(0, s.a(20, 20))

        s.history.undo(s.alpha, s.width)
        assertEquals(255, s.a(20, 20))
        assertFalse(s.history.canUndo)

        s.history.redo(s.alpha, s.width)
        assertEquals(0, s.a(20, 20))
        assertTrue(s.history.canRedo)

        // Un trazo nuevo borra la pila de rehacer.
        Stroke(s, Tool.MANUAL, 4f, 50).apply { begin(50f, 10f); end() }
        assertFalse(s.history.canRedo)
    }

    @Test
    fun cancelLeavesNoTrace() {
        val s = squareImage()
        val st = Stroke(s, Tool.MANUAL, 10f, 50)
        st.begin(50f, 50f)
        st.moveTo(70f, 70f)
        st.cancel()
        assertEquals(255, s.a(50, 50))
        assertEquals(255, s.a(70, 70))
        assertFalse(s.history.canUndo)
    }

    @Test
    fun strokeWithNoChangeIsNotRecorded() {
        val s = squareImage()
        Stroke(s, Tool.REPAIR, 5f, 50).apply { begin(50f, 50f); end() }
        assertFalse(s.history.canUndo)
    }

    @Test
    fun undoStackDropsOldestWhenFull() {
        val st = UndoStack(maxBytes = 1000)
        val r = IntRect(0, 0, 10, 10) // 100 bytes antes + 100 después
        repeat(8) { i ->
            st.push(r, ByteArray(100) { i.toByte() }, ByteArray(100) { (i + 1).toByte() })
        }
        assertEquals(5, st.undoCount)
    }

    @Test
    fun undoThenNewEditDoesNotLeakMemoryCount() {
        val st = UndoStack(maxBytes = 1000)
        val r = IntRect(0, 0, 10, 10)
        val mask = ByteArray(100)
        // Muchos ciclos de "cambio, deshacer, cambio nuevo": la cuenta no debe crecer sin límite.
        repeat(50) { i ->
            st.push(r, ByteArray(100) { 1 }, ByteArray(100) { (i + 2).toByte() })
            st.undo(mask, 10)
        }
        assertEquals(0L, st.bytesInUse)
        repeat(4) { i -> st.push(r, ByteArray(100) { 1 }, ByteArray(100) { (i + 2).toByte() }) }
        assertEquals(4, st.undoCount)
        st.undo(mask, 10)
        st.redo(mask, 10)
        assertEquals(800L, st.bytesInUse)
    }

    @Test
    fun replaceMaskIsUndoable() {
        val s = squareImage()
        s.replaceMask(ByteArray(s.width * s.height))
        assertEquals(0, s.visiblePixelCount())
        s.history.undo(s.alpha, s.width)
        assertEquals(s.width * s.height, s.visiblePixelCount())
    }

    @Test
    fun smoothMaskSoftensHardEdge() {
        val w = 20
        val h = 1
        val mask = ByteArray(w) { if (it < 10) 0 else -1 }
        val out = ImageOps.smoothMask(mask, w, h, 2)
        val v = { i: Int -> out[i].toInt() and 0xFF }
        assertEquals(0, v(0))
        assertEquals(255, v(19))
        assertTrue(v(9) in 1..254)
        assertTrue(v(10) in 1..254)
        assertTrue(v(9) < v(10))
    }

    @Test
    fun smoothRadiusZeroIsCopy() {
        val mask = byteArrayOf(0, -1, 0, -1)
        val out = ImageOps.smoothMask(mask, 4, 1, 0)
        assertTrue(mask.contentEquals(out))
    }

    @Test
    fun compositeAppliesAlpha() {
        val s = squareImage()
        Stroke(s, Tool.MANUAL, 2f, 50).apply { begin(5f, 5f); end() }
        val out = IntArray(s.width * s.height)
        s.composite(out)
        assertEquals(0x00FFFFFF, out[5 * s.width + 5])
        assertEquals(red, out[50 * s.width + 50])
    }

    @Test
    fun confidenceRamp() {
        val a = ImageOps.confidenceToAlpha(floatArrayOf(0f, 0.5f, 1f))
        assertEquals(0, a[0].toInt() and 0xFF)
        assertTrue((a[1].toInt() and 0xFF) in 100..155)
        assertEquals(255, a[2].toInt() and 0xFF)
    }

    @Test
    fun opaqueBoundsFindsSubject() {
        val s = squareImage()
        Stroke(s, Tool.COLOR, 200f, 20).apply { begin(1f, 1f); end() }
        assertEquals(IntRect(30, 30, 70, 70), ImageOps.opaqueBounds(s.alpha, s.width, s.height))
    }

    @Test
    fun opaqueBoundsEmptyWhenAllErased() {
        val s = squareImage()
        s.replaceMask(ByteArray(s.width * s.height))
        assertTrue(ImageOps.opaqueBounds(s.alpha, s.width, s.height).isEmpty)
    }

    @Test
    fun transparentInputKeepsTransparencyAndRepairRespectsIt() {
        // Mitad izquierda transparente (como un PNG ya recortado), mitad derecha opaca.
        val w = 20
        val h = 10
        val px = IntArray(w * h) { if ((it % w) < 10) 0x00FFFFFF else red }
        val s = EditorState(w, h, px)
        assertEquals(0, s.a(2, 5))
        assertEquals(255, s.a(15, 5))
        Stroke(s, Tool.MANUAL, 30f, 50).apply { begin(10f, 5f); end() }
        Stroke(s, Tool.REPAIR, 30f, 50).apply { begin(10f, 5f); end() }
        assertEquals("Reparar no inventa píxeles donde la foto ya era transparente", 0, s.a(2, 5))
        assertEquals(255, s.a(15, 5))
    }

    @Test
    fun strokeAtImageBorderDoesNotCrash() {
        val s = squareImage()
        listOf(Tool.REPAIR, Tool.MAGIC, Tool.COLOR, Tool.MANUAL).forEach { t ->
            Stroke(s, t, 15f, 50).apply { begin(-20f, -20f); moveTo(120f, 120f); moveTo(0f, 99.9f); end() }
        }
        assertEquals(0, s.a(0, 0))
    }
}
