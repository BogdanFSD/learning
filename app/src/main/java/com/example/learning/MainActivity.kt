package com.example.learning

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val pdfDispatcher = Executors
        .newSingleThreadExecutor { Thread(it, "PdfThread") }
        .asCoroutineDispatcher()

    internal var docPtr: Long = 0L
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("pdfium")
        System.loadLibrary("pdf_jni")
        enableEdgeToEdge()

        val bmpState  = mutableStateOf<Bitmap?>(null)
        val infoState = mutableStateOf("")

        val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { openPdf(it, bmpState, infoState) }
        }

        setContent {
            val ctx = LocalContext.current
            LearningTheme {
                Scaffold { pad ->
                    Column(
                        Modifier
                            .padding(pad)
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Text("Pick PDF")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(infoState.value)
                        Spacer(Modifier.height(8.dp))

                        bmpState.value?.let { bmp ->
                            ZoomablePage(
                                docPtr = docPtr,               // ← pass the real docPtr
                                bitmap = bmp,
                                onHighResNeeded = { scale ->
                                    renderHighDpi(bmpState, scale)
                                }
                            ) { text ->
                                if (text.isNotEmpty()) {
                                    Toast.makeText(ctx, "Copied: $text", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openPdf(
        uri: Uri,
        bmp: MutableState<Bitmap?>,
        info: MutableState<String>
    ) = lifecycleScope.launch(pdfDispatcher) {
        renderJob?.cancel()
        if (docPtr != 0L) PdfUtils.closeDocument(docPtr)

        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@launch
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        docPtr = PdfUtils.openPdfFromFD(pfd.fd)
        if (docPtr == 0L) return@launch

        val pages = PdfUtils.getPageCount(docPtr)
        withContext(Dispatchers.Main) { info.value = "Pages: $pages" }

        bmp.value = renderPage(1f)
    }

    private fun renderHighDpi(bmp: MutableState<Bitmap?>, uiScale: Float) {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch(pdfDispatcher) {
            bmp.value = renderPage(uiScale)
        }
    }

    private fun renderPage(uiScale: Float): Bitmap {
        val sizePt = PdfUtils.getPageSize(docPtr, 0)
        val dpi    = (96f * uiScale).coerceAtMost(600f)
        val wPx    = (sizePt[0] * dpi / 72f).roundToInt().coerceAtLeast(1)
        val hPx    = (sizePt[1] * dpi / 72f).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).also {
            PdfUtils.renderPage(docPtr, 0, it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(pdfDispatcher) {
            renderJob?.cancel()
            if (docPtr != 0L) PdfUtils.closeDocument(docPtr)
            pdfDispatcher.close()
        }
    }
}
