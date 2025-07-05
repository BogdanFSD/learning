package com.example.learning

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

private const val LOG = "ZOOM-SEL"

@Composable
fun ZoomablePage(
    docPtr: Long,
    bitmap: android.graphics.Bitmap,
    onHighResNeeded: (Float) -> Unit,
    onSelection: (String) -> Unit
) {
    /* ─── state ─── */
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize(0, 0)) }

    val ptPerPx = 72f / 96f
    val pageH by remember(docPtr) { mutableStateOf(PdfUtils.getPageSize(docPtr, 0)[1]) }

    /* inverse matrix (no centering translation) */
    val invViewMatrix by remember {
        derivedStateOf { viewToPdfMatrix(scale, offset) }
    }

    val quads = remember { mutableStateListOf<RectF>() }
    val probes = remember { mutableStateListOf<Offset>() }

    val clipboard = LocalClipboardManager.current
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

    /* ─── fit page with 5 % margin ─── */
    LaunchedEffect(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val sx = canvasSize.width.toFloat() / bitmap.width
            val sy = canvasSize.height.toFloat() / bitmap.height
            val fit = min(sx, sy)
            val safe = min(fit, 1f) * 0.95f           // never enlarge, leave margin
            scale = safe

            val contentW = bitmap.width * safe
            val contentH = bitmap.height * safe
            offset = Offset(
                (canvasSize.width - contentW) / 2f,
                (canvasSize.height - contentH) / 2f
            )
            Log.d(LOG, "Fit scale=$scale offset=$offset")
        }
    }

    /* ─── pinch/pan ─── */
    val transformableState = rememberTransformableState { zoom, pan, _ ->
        val newScale = (scale * zoom).coerceIn(1f, 8f)
        val contentW = bitmap.width * newScale
        val contentH = bitmap.height * newScale
        val boundX = max(0f, (contentW - canvasSize.width) / 2f)
        val boundY = max(0f, (contentH - canvasSize.height) / 2f)

        val newOffset = offset + pan
        scale = newScale
        offset = Offset(
            newOffset.x.coerceIn(-boundX, boundX),
            newOffset.y.coerceIn(-boundY, boundY)
        )
        Log.d(LOG, "PANZOOM scale=$scale offset=$offset")
        onHighResNeeded(scale)
    }

    /* ─── draw ─── */
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(state = transformableState)
            .pointerInput(docPtr) {
                detectTapGestures(onLongPress = { downPos ->
                    Log.d(LOG, "LP at $downPos (threshold=$longPressMs ms)")
                    val tp = PdfUtils.loadTextPage(docPtr, 0)
                    probes.clear()

                    fun Offset.toPdfPt(): Pair<Float, Float> {
                        val p = floatArrayOf(x, y)
                        invViewMatrix.mapPoints(p)
                        val xPt = p[0] * ptPerPx
                        val yPt = pageH - p[1] * ptPerPx
                        return xPt to yPt
                    }

                    fun idxAt(p: Offset) = p.toPdfPt().let { PdfUtils.charIndexAtPos(tp, it.first, it.second) }

                    fun search(start: Offset): Int {
                        val step = 6f; val maxR = 30f; val angles = (0 until 360 step 30)
                        probes += start
                        idxAt(start).takeIf { it >= 0 }?.let { return it }
                        var r = step
                        while (r <= maxR) {
                            for (a in angles) {
                                val rad = Math.toRadians(a.toDouble())
                                val p = Offset(start.x + r * cos(rad).toFloat(), start.y + r * sin(rad).toFloat())
                                probes += p
                                idxAt(p).takeIf { it >= 0 }?.let { return it }
                            }
                            r += step
                        }
                        return -1
                    }

                    val idx = search(downPos)
                    Log.d(LOG, "HIT idx=$idx")
                    val range = expandWord(tp, idx)
                    if (range != null) {
                        quads.clear()
                        val pxPerPt = (96f * scale) / 72f
                        highlightRange(tp, range, pageH, pxPerPt, quads)
                        val text = PdfUtils.extractRange(tp, range.first, range.last - range.first + 1)
                        clipboard.setText(AnnotatedString(text))
                        onSelection(text)
                        Log.d(LOG, "COPIED \"$text\"")
                    } else Log.d(LOG, "MISS no word")
                    PdfUtils.closeTextPage(tp)
                })
            }
    ) {
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null) }
        quads.forEach { r -> drawRect(Color.Yellow.copy(alpha = 0.35f), Offset(r.left, r.top), androidx.compose.ui.geometry.Size(r.width(), r.height())) }
        probes.forEach { drawCircle(Color.Red.copy(alpha = 0.5f), 6f, it) }
    }
}

/* simple inverse based on user pan/zoom only */
private fun viewToPdfMatrix(scale: Float, offset: Offset): Matrix {
    val m = Matrix()
    m.postScale(scale, scale)
    m.postTranslate(offset.x, offset.y)
    m.invert(m)
    return m
}

/* word expansion – unchanged */
private fun expandWord(tp: Long, idx: Int): IntRange? {
    if (idx < 0) return null
    fun isLetter(i: Int) = PdfUtils.extractRange(tp, i, 1).firstOrNull()?.isLetterOrDigit() == true
    var l = idx; var r = idx; val total = PdfUtils.getCharCount(tp)
    while (l > 0 && isLetter(l - 1)) l--
    while (r + 1 < total && isLetter(r + 1)) r++
    return l..r
}

/* highlight helper – unchanged */
private fun highlightRange(tp: Long, range: IntRange, pageH: Float, pxPerPt: Float, quads: MutableList<RectF>) {
    quads.clear()
    for (i in range) PdfUtils.charBox(tp, i).takeIf { it.size == 4 }?.let { b ->
        quads += RectF(b[0] * pxPerPt, (pageH - b[3]) * pxPerPt, b[2] * pxPerPt, (pageH - b[1]) * pxPerPt)
    }
}
