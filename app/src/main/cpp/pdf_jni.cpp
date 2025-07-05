#include <jni.h>
#include <android/bitmap.h>
#include <fpdfview.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <android/log.h>
#include <jni.h>
#include <jni.h>
#include "fpdf_text.h"
#include <vector>

#define LOG_TAG "PDFJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/* ---------- life-cycle --------------------------------------------------- */

    jint JNI_OnLoad(JavaVM*, void*) {
        FPDF_InitLibrary();
        LOGI("Pdfium initialised");
        return JNI_VERSION_1_6;
    }
    void JNI_OnUnload(JavaVM*, void*) {
        FPDF_DestroyLibrary();
        LOGI("Pdfium destroyed");
    }

    /* ---------- document ----------------------------------------------------- */

    JNIEXPORT jlong JNICALL
    Java_com_example_learning_PdfUtils_openPdfFromFD(JNIEnv*, jclass, jint fd) {
        int dupFd = dup(fd);
        off_t size = lseek(dupFd, 0, SEEK_END);
        lseek(dupFd, 0, SEEK_SET);

        void* data = mmap(nullptr, size, PROT_READ, MAP_SHARED, dupFd, 0);
        if (data == MAP_FAILED) {
            LOGE("mmap failed");
            close(dupFd);
            return 0;
        }

        FPDF_DOCUMENT doc = FPDF_LoadMemDocument(data, size, nullptr);
        if (!doc) {
            LOGE("Failed to load PDF");
            munmap(data, size);
            close(dupFd);
            return 0;
        }
        LOGI("PDF loaded (%ld bytes)", static_cast<long>(size));
        return reinterpret_cast<jlong>(doc);
    }

    JNIEXPORT jint JNICALL
    Java_com_example_learning_PdfUtils_getPageCount(JNIEnv*, jclass, jlong docPtr) {
        return FPDF_GetPageCount(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
    }

    JNIEXPORT void JNICALL
    Java_com_example_learning_PdfUtils_closeDocument(JNIEnv*, jclass, jlong docPtr) {
        FPDF_CloseDocument(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
    }

    /* ---------- page info ---------------------------------------------------- */

    JNIEXPORT jfloatArray JNICALL
            Java_com_example_learning_PdfUtils_getPageSize(JNIEnv* env, jclass,
                                                           jlong docPtr, jint pageIndex) {
        FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex);
        jfloat wh[2] = {0.f, 0.f};
        if (page) {
        wh[0] = FPDF_GetPageWidthF(page);
        wh[1] = FPDF_GetPageHeightF(page);
        FPDF_ClosePage(page);
        }
        jfloatArray arr = env->NewFloatArray(2);
        env->SetFloatArrayRegion(arr, 0, 2, wh);
        return arr;
    }

    /* ---------- rendering ---------------------------------------------------- */

    JNIEXPORT void JNICALL
    Java_com_example_learning_PdfUtils_renderPage(JNIEnv* env, jclass,
    jlong docPtr, jint pageIndex,
    jobject bitmap) {
        /* Android Bitmap info */
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("AndroidBitmap_getInfo failed");
        return;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap must be RGBA_8888");
        return;
        }

        /* lock pixels */
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        return;
        }

        /* load page */
        auto doc  = reinterpret_cast<FPDF_DOCUMENT>(docPtr);
        FPDF_PAGE page = FPDF_LoadPage(doc, pageIndex);
        if (!page) {
        LOGE("Cannot load page %d", pageIndex);
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
        }

        /* create Pdfium bitmap that points to the same pixel buffer */
        FPDF_BITMAP pdfBmp = FPDFBitmap_CreateEx(
                static_cast<int>(info.width),
                static_cast<int>(info.height),
                FPDFBitmap_BGRA,
                pixels,
                static_cast<int>(info.stride));

        FPDFBitmap_FillRect(pdfBmp, 0, 0, info.width, info.height, 0xFFFFFFFF); // white bkg
        FPDF_RenderPageBitmap(pdfBmp, page, 0, 0,
        info.width, info.height,
        0, /* rotate */
        0  /* flags  */);

        /* clean up */
        FPDFBitmap_Destroy(pdfBmp);
        FPDF_ClosePage(page);
        AndroidBitmap_unlockPixels(env, bitmap);
    }


/* ---------- text extraction ------------------------------------------- */










JNIEXPORT jstring JNICALL
        Java_com_example_learning_PdfUtils_extractText(JNIEnv* env,jclass,jlong tpPtr){
auto tp = reinterpret_cast<FPDF_TEXTPAGE>(tpPtr);
int n   = FPDFText_CountChars(tp);
if(n<=0) return env->NewStringUTF("");
std::vector<unsigned short> buf(n+1);
int copied = FPDFText_GetText(tp,0,n,buf.data());
if(copied<=0) return env->NewStringUTF("");
return env->NewString(reinterpret_cast<jchar*>(buf.data()),copied-1);
}



JNIEXPORT jstring JNICALL
        Java_com_example_learning_PdfUtils_getBoundedText(JNIEnv* env,jclass,jlong tpPtr,
jfloat l,jfloat t,jfloat r,jfloat b){
auto tp  = reinterpret_cast<FPDF_TEXTPAGE>(tpPtr);
int cap  = 2048;
std::vector<unsigned short> buf(cap);
int len  = FPDFText_GetBoundedText(tp,l,t,r,b,buf.data(),cap);  // extracts UTF-16
if(len<=0) return env->NewStringUTF("");
return env->NewString(reinterpret_cast<jchar*>(buf.data()),len);
}






JNIEXPORT jlong JNICALL
        Java_com_example_learning_PdfUtils_loadTextPage(JNIEnv*,jclass,jlong doc,jint index){
FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(doc), index);
return page ? reinterpret_cast<jlong>(FPDFText_LoadPage(page)) : 0;
}
JNIEXPORT void JNICALL
Java_com_example_learning_PdfUtils_closeTextPage(JNIEnv*,jclass,jlong tp){
FPDFText_ClosePage(reinterpret_cast<FPDF_TEXTPAGE>(tp));
}

/* ───── hit-test: char index at point ───── */
JNIEXPORT jint JNICALL
        Java_com_example_learning_PdfUtils_charIndexAtPos(JNIEnv*,jclass,
                                                          jlong tpPtr,
jfloat xPt,jfloat yPt){
return FPDFText_GetCharIndexAtPos(reinterpret_cast<FPDF_TEXTPAGE>(tpPtr),
        xPt,yPt,8.0,8.0);
}

/* ───── single char box ───── */
JNIEXPORT jfloatArray JNICALL
        Java_com_example_learning_PdfUtils_charBox(JNIEnv* env,jclass,jlong tpPtr,jint idx){
double l,r,b,t;
if(!FPDFText_GetCharBox(reinterpret_cast<FPDF_TEXTPAGE>(tpPtr),idx,&l,&r,&b,&t))
return env->NewFloatArray(0);
jfloat out[4] = {static_cast<float>(l),
                 static_cast<float>(t),
                 static_cast<float>(r),
                 static_cast<float>(b)};
jfloatArray arr = env->NewFloatArray(4);
env->SetFloatArrayRegion(arr,0,4,out);
return arr;
}

/* ───── extract UTF-16 range → Kotlin String ───── */
JNIEXPORT jstring JNICALL
        Java_com_example_learning_PdfUtils_extractRange(JNIEnv* env,jclass,
                                                        jlong tpPtr,jint start,jint cnt){
std::vector<unsigned short> buf(cnt+1);
FPDFText_GetText(reinterpret_cast<FPDF_TEXTPAGE>(tpPtr),start,cnt,buf.data());
return env->NewString(reinterpret_cast<jchar*>(buf.data()), cnt);
}

JNIEXPORT jint JNICALL
        Java_com_example_learning_PdfUtils_getCharCount(JNIEnv* env, jclass, jlong tpPtr) {
FPDF_TEXTPAGE tp = reinterpret_cast<FPDF_TEXTPAGE>(tpPtr);
return FPDFText_CountChars(tp);
}



} // extern "C"


