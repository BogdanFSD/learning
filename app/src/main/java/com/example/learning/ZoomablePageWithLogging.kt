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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull

private fun log(tag: String, msg: String) = Log.d("WSEL-$tag", msg)

/** One-page view: pinch-zoom, pan, long-press word selection with highlight. */
object ZoomablePageWithLogging {

    /** Exposed so MainActivity can do coordinate math if it needs to. */
    lateinit var currentTransform: Transform
        private set

    data class Transform(
        val scale: Float,
        val offset: Offset,
        val pxPerPt: Float,
        val matrix: Matrix          // view-px → bitmap-px
    )

    @Composable
    operator fun invoke(
        docPtr: Long,
        bitmap: Bitmap,
        onHighResNeeded: (Float) -> Unit,
        onSelection: (String) -> Unit
    ) {

        /* zoom & pan state */
        var scale  by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val pxPerPt = (96f * scale) / 72f

        /* px per PostScript point (matches raster DPI) */

        /* highlight boxes in view-px */
        val quads = remember { mutableStateListOf<android.graphics.RectF>() }
        val clipboard = LocalClipboardManager.current
        val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

        /* page height (pt) for Y-flip */
        val pageHpt = remember(docPtr) { PdfUtils.getPageSize(docPtr, 0)[1] }

        /* ───────── gestures ───────── */
        val gesture = Modifier
            /* pinch / pan */
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (quads.isEmpty()) {
                        scale  = (scale * zoom).coerceIn(1f, 8f)
                        offset += pan
                        if (scale > 2f) onHighResNeeded(scale)
                    }
                }
            }
            /* long-press word selection */
            .pointerInput(Unit) {
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown()
                        val lp = withTimeoutOrNull(longPressMs.toLong()) {
                            var cur = down
                            while (cur.pressed && cur.positionChange() == Offset.Zero) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.size > 1) return@withTimeoutOrNull null
                                cur = ev.changes.first { it.id == down.id }
                            }
                            cur
                        } ?: return@awaitPointerEventScope

                        val tp = PdfUtils.loadTextPage(docPtr, 0)

                        /* helper: char index under finger */
                        fun idxAt(p: Offset): Int {
                            val inv = Matrix().apply { currentTransform.matrix.invert(this) }
                            val xy  = floatArrayOf(p.x, p.y).also { inv.mapPoints(it) }
                            return PdfUtils.charIndexAtPos(tp, xy[0] / pxPerPt, xy[1] / pxPerPt)
                        }

                        var sel = expandWord(tp, idxAt(lp.position)) ?: run {
                            PdfUtils.closeTextPage(tp); return@awaitPointerEventScope
                        }
                        updateHighlight(tp, sel, pageHpt, pxPerPt, quads, clipboard, onSelection)

                        /* drag-extend */
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1) break
                            val ch = ev.changes.first { it.id == down.id }
                            if (!ch.pressed) break
                            val idx = idxAt(ch.position)
                            if (idx < 0) continue
                            val newSel = if (idx > sel.last) sel.first..idx else idx..sel.last
                            if (newSel != sel) {
                                sel = newSel
                                updateHighlight(tp, sel, pageHpt, pxPerPt, quads, clipboard, onSelection)
                            }
                        }
                        PdfUtils.closeTextPage(tp)
                    }
                }
            }

        /* build & expose transform */
        val mat = remember(scale, offset) {
            Matrix().apply { postScale(scale, scale); postTranslate(offset.x, offset.y) }
        }
        currentTransform = Transform(scale, offset, pxPerPt, mat)

        /* draw */
        Canvas(
            Modifier
                .fillMaxSize()
                .then(gesture)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        ) {
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null) }
            quads.forEach {
                drawRect(
                    Color.Yellow.copy(alpha = .4f),
                    Offset(it.left, it.top),
                    androidx.compose.ui.geometry.Size(it.width(), it.height())
                )
            }
        }
    }

    /* expand idx → word */
    private fun expandWord(tp: Long, idx: Int): IntRange? {
        if (idx < 0) return null
        fun isWord(i: Int) =
            PdfUtils.extractRange(tp, i, 1).firstOrNull()?.isLetterOrDigit() == true
        var l = idx; var r = idx
        while (l > 0 && isWord(l - 1)) l--
        val total = PdfUtils.extractRange(tp, 0, 1_000_000).length
        while (r + 1 < total && isWord(r + 1)) r++
        return l..r
    }

    /* build view-quads, copy text, call callback */
    private fun updateHighlight(
        tp: Long,
        range: IntRange,
        pageHpt: Float,
        pxPerPt: Float,
        quads: MutableList<android.graphics.RectF>,
        clip: androidx.compose.ui.platform.ClipboardManager,
        cb: (String) -> Unit
    ) {
        quads.clear()
        range.forEach { i ->
            val b = PdfUtils.charBox(tp, i)
            if (b.size == 4) {
                /* flip Y from page to canvas */
                val lPt = b[0]; val tPt = pageHpt - b[1]
                val rPt = b[2]; val bPt = pageHpt - b[3]
                val pts = floatArrayOf(
                    lPt * pxPerPt, tPt * pxPerPt,
                    rPt * pxPerPt, bPt * pxPerPt
                )
                currentTransform.matrix.mapPoints(pts)
                quads += android.graphics.RectF(pts[0], pts[1], pts[2], pts[3])
            }
        }
        val txt = PdfUtils.extractRange(tp, range.first, range.last - range.first + 1)
        clip.setText(androidx.compose.ui.text.AnnotatedString(txt))
        cb(txt)
        log("TEXT", txt)
    }
}
