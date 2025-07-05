cmake_minimum_required(VERSION 3.10.2)
project(learning)

# ✅ Enforce only x86_64 ABI
if(NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "Only x86_64 ABI is supported. Current ABI: ${ANDROID_ABI}")
endif()

# -------- Pdfium (pre-built) ---------------------------------------------
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so)

# -------- your JNI glue ---------------------------------------------------
add_library(pdf_jni SHARED pdf_jni.cpp)

target_include_directories(pdf_jni PRIVATE
        ${CMAKE_SOURCE_DIR}/pdfium)  # Pdfium headers (must contain fpdfview.h, etc.)

target_link_libraries(
        pdf_jni
        pdfium
        android
        log
        jnigraphics)  # for AndroidBitmap_*
