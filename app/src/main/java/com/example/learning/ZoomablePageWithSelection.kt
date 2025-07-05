package com.example.learning

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WordSelect"

object ZoomablePageWithSelection {

    /** Exposed to MainActivity for coordinate conversion */
    lateinit var currentTransform: Transform
        private set

    data class Transform(
        val scale: Float,
        val offset: Offset,
        val pxPerPt: Float,
        val matrix: Matrix
    )

    @Composable
    operator fun invoke(
        bitmap: Bitmap,
        onHighResNeeded: (Float) -> Unit,
        onSelectionChange: (String) -> Unit    // emits selected text
    ) {
        /* zoom / pan */
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        /* highlighted glyph quads */
        val selQuads = remember { mutableStateListOf<android.graphics.RectF>() }
        val clipboard = LocalClipboardManager.current
        val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

        /* ---- gestures ---- */
        val gestureMod = Modifier
            /* pan / pinch (disabled while selecting) */
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (selQuads.isEmpty()) {
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset += pan
                        if (scale > 2f) onHighResNeeded(scale)
                    }
                }
            }
            /* long-press to select word, drag to extend */
            .pointerInput(Unit) {
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown()
                        val tp   = PdfUtils.loadTextPage(MainActivity().docPtr, 0)

                        /* wait long-press without extra pointers or movement */
                        val lp = withTimeoutOrNull(longPressMs.toLong()) {
                            var cur = down
                            while (cur.pressed && cur.positionChange() == Offset.Zero) {
                                val evt = awaitPointerEvent()
                                if (evt.changes.size > 1) return@withTimeoutOrNull null
                                cur = evt.changes.first { it.id == down.id }
                            }
                            cur
                        } ?: run { PdfUtils.closeTextPage(tp); return@awaitPointerEventScope }

                        /* initial word */
                        val idx = charIndex(tp, lp.position)
                        if (idx < 0) { PdfUtils.closeTextPage(tp); return@awaitPointerEventScope }
                        var range = expandToWord(tp, idx)
                        selectRange(tp, range, selQuads, clipboard, onSelectionChange)

                        /* drag-extend */
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first { it.id == down.id }
                            if (!ch.pressed || ev.changes.size > 1) break
                            val newIdx = charIndex(tp, ch.position)
                            if (newIdx >= 0 && newIdx != range.last) {
                                range = if (newIdx > range.last)
                                    range.first..newIdx else newIdx..range.last
                                selectRange(tp, range, selQuads, clipboard, onSelectionChange)
                            }
                        }
                        PdfUtils.closeTextPage(tp)
                    }
                }
            }

        /* transform for Activity */
        val pxPerPt = LocalContext.current.resources.displayMetrics.density
        val mat = remember(scale, offset) {
            Matrix().apply {
                postScale(scale, scale)
                postTranslate(offset.x, offset.y)
            }
        }
        currentTransform = Transform(scale, offset, pxPerPt, mat)

        /* draw page + yellow quads */
        Canvas(
            Modifier
                .fillMaxSize()
                .then(gestureMod)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        ) {
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null) }
            selQuads.forEach { q ->
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.4f),
                    topLeft = Offset(q.left, q.top),
                    size = androidx.compose.ui.geometry.Size(q.width(), q.height())
                )
            }
        }
    }

    /* ---------- helpers ---------- */

    private fun charIndex(tp: Long, pos: Offset): Int {
        val ui = currentTransform
        val viewToBmp = Matrix().apply { ui.matrix.invert(this) }
        val xy = floatArrayOf(pos.x, pos.y).also { viewToBmp.mapPoints(it) }
        val xPt = xy[0] / ui.pxPerPt
        val yPt = xy[1] / ui.pxPerPt
        return PdfUtils.charIndexAtPos(tp, xPt, yPt)
    }

    private fun expandToWord(tp: Long, start: Int): IntRange {
        var l = start; var r = start
        val total = PdfUtils.extractRange(tp, 0, 0x7FFFFFFF).length
        fun isLetter(i: Int) = PdfUtils.extractRange(tp, i, 1)
            .firstOrNull()?.isLetterOrDigit() ?: false
        while (l > 0 && isLetter(l - 1)) l--
        while (r + 1 < total && isLetter(r + 1)) r++
        return l..r
    }

    private fun selectRange(
        tp: Long,
        range: IntRange,
        quads: MutableList<android.graphics.RectF>,
        clipboard: androidx.compose.ui.platform.ClipboardManager,
        onSel: (String) -> Unit
    ) {
        quads.clear()
        range.forEach { i ->
            val box = PdfUtils.charBox(tp, i)
            if (box.size == 4) quads += android.graphics.RectF(box[0], box[1], box[2], box[3])
        }
        val txt = PdfUtils.extractRange(tp, range.first, range.last - range.first + 1)
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(txt))
        onSel(txt)
        Log.d(TAG, "Selected \"$txt\"")
    }
}
