package com.example.learning

import android.graphics.Bitmap

object PdfUtils {
    @JvmStatic external fun openPdfFromFD(fd: Int): Long
    @JvmStatic external fun closeDocument(doc: Long)
    @JvmStatic external fun getPageCount(doc: Long): Int
    @JvmStatic external fun getPageSize(doc: Long, page: Int): FloatArray
    @JvmStatic external fun renderPage(doc: Long, page: Int, bmp: Bitmap)

    /* text page helpers */
    @JvmStatic external fun loadTextPage(doc: Long, page: Int): Long
    @JvmStatic external fun closeTextPage(text: Long)

    /* hit-test & extraction */
    @JvmStatic external fun charIndexAtPos(text: Long, xPt: Float, yPt: Float): Int
    @JvmStatic external fun charBox(text: Long, index: Int): FloatArray
    @JvmStatic external fun extractRange(text: Long, start: Int, count: Int): String
    // New: get total character count on a text page
    @JvmStatic external fun getCharCount(text: Long): Int
    init { System.loadLibrary("pdf_jni") }
}
