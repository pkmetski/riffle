// pdfium_text_jni.cpp
//
// Parasitic JNI bridge that exposes Pdfium FPDFText_* APIs to Kotlin without
// linking against (or vendoring) Pdfium itself. Resolves symbols at first call
// via dlopen("libmodpdfium.so", RTLD_NOLOAD) — the .so is already loaded into
// the process by com.shockwave.pdfium.PdfiumCore.<clinit> (barteksc), so
// RTLD_NOLOAD returns a handle to it and dlsym resolves the FPDFText_* exports
// from there.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <mutex>
#include <cstdint>
#include <cstring>

#define LOG_TAG "RiffleTextJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// ---- Inline Pdfium C ABI declarations (stable, public, BSD-3-Clause headers)
// We declare only what we need. Types are opaque pointers; calling convention
// matches Pdfium's public C API.

extern "C" {
typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_TEXTPAGE;
typedef unsigned short FPDF_WCHAR;
typedef int            FPDF_BOOL;
}

// Function-pointer typedefs for the symbols we dlsym.
using FnLoadDocumentFromFD = FPDF_DOCUMENT (*)(int file_handle, const char* password);
using FnLoadDocument       = FPDF_DOCUMENT (*)(const char* file_path, const char* password);
using FnCloseDocument      = void          (*)(FPDF_DOCUMENT);
using FnLoadPage           = FPDF_PAGE     (*)(FPDF_DOCUMENT, int page_index);
using FnClosePage          = void          (*)(FPDF_PAGE);
using FnGetPageCount       = int           (*)(FPDF_DOCUMENT);
using FnGetPageWidth       = double        (*)(FPDF_PAGE);
using FnGetPageHeight      = double        (*)(FPDF_PAGE);

using FnTextLoadPage         = FPDF_TEXTPAGE (*)(FPDF_PAGE);
using FnTextClosePage        = void          (*)(FPDF_TEXTPAGE);
using FnTextCountChars       = int           (*)(FPDF_TEXTPAGE);
using FnTextGetCharBox       = FPDF_BOOL     (*)(FPDF_TEXTPAGE, int index,
                                                 double* left, double* right,
                                                 double* bottom, double* top);
using FnTextGetCharIndexAtPos= int           (*)(FPDF_TEXTPAGE,
                                                 double x, double y,
                                                 double xTolerance, double yTolerance);
using FnTextCountRects       = int           (*)(FPDF_TEXTPAGE, int start, int count);
using FnTextGetRect          = FPDF_BOOL     (*)(FPDF_TEXTPAGE, int rect_index,
                                                 double* left, double* top,
                                                 double* right, double* bottom);
using FnTextGetText          = int           (*)(FPDF_TEXTPAGE, int start, int count,
                                                 FPDF_WCHAR* result);
using FnTextGetBoundedText   = int           (*)(FPDF_TEXTPAGE,
                                                 double left, double top,
                                                 double right, double bottom,
                                                 FPDF_WCHAR* result, int max_chars);

namespace {

struct Symbols {
    void* handle = nullptr;
    FnLoadDocument          load_document          = nullptr;
    FnCloseDocument         close_document         = nullptr;
    FnLoadPage              load_page              = nullptr;
    FnClosePage             close_page             = nullptr;
    FnGetPageCount          get_page_count         = nullptr;
    FnGetPageWidth          get_page_width         = nullptr;
    FnGetPageHeight         get_page_height        = nullptr;
    FnTextLoadPage          text_load_page         = nullptr;
    FnTextClosePage         text_close_page        = nullptr;
    FnTextCountChars        text_count_chars       = nullptr;
    FnTextGetCharBox        text_get_char_box      = nullptr;
    FnTextGetCharIndexAtPos text_get_char_at_pos   = nullptr;
    FnTextCountRects        text_count_rects       = nullptr;
    FnTextGetRect           text_get_rect          = nullptr;
    FnTextGetText           text_get_text          = nullptr;
    FnTextGetBoundedText    text_get_bounded_text  = nullptr;
};

Symbols g_syms;
std::once_flag g_resolve_once;
bool g_resolve_ok = false;

template <typename Fn>
bool resolve_one(void* handle, const char* name, Fn* slot) {
    *slot = reinterpret_cast<Fn>(dlsym(handle, name));
    if (*slot == nullptr) {
        ALOGE("dlsym failed for %s: %s", name, dlerror());
        return false;
    }
    return true;
}

void resolve_symbols() {
    // RTLD_NOLOAD: get a handle to libmodpdfium.so if it's already loaded,
    // else return nullptr (we do NOT want to load it ourselves — the caller is
    // responsible for ensuring barteksc's PdfiumCore.<clinit> ran first).
    g_syms.handle = dlopen("libmodpdfium.so", RTLD_NOW | RTLD_NOLOAD);
    if (g_syms.handle == nullptr) {
        ALOGE("dlopen(libmodpdfium.so, RTLD_NOLOAD) returned null — "
              "PdfiumCore must be touched (its <clinit> loads the .so) before "
              "any PdfiumTextApi call.");
        return;
    }

    bool ok = true;
    ok &= resolve_one(g_syms.handle, "FPDF_LoadDocument",       &g_syms.load_document);
    ok &= resolve_one(g_syms.handle, "FPDF_CloseDocument",      &g_syms.close_document);
    ok &= resolve_one(g_syms.handle, "FPDF_LoadPage",           &g_syms.load_page);
    ok &= resolve_one(g_syms.handle, "FPDF_ClosePage",          &g_syms.close_page);
    ok &= resolve_one(g_syms.handle, "FPDF_GetPageCount",       &g_syms.get_page_count);
    ok &= resolve_one(g_syms.handle, "FPDF_GetPageWidth",       &g_syms.get_page_width);
    ok &= resolve_one(g_syms.handle, "FPDF_GetPageHeight",      &g_syms.get_page_height);
    ok &= resolve_one(g_syms.handle, "FPDFText_LoadPage",       &g_syms.text_load_page);
    ok &= resolve_one(g_syms.handle, "FPDFText_ClosePage",      &g_syms.text_close_page);
    ok &= resolve_one(g_syms.handle, "FPDFText_CountChars",     &g_syms.text_count_chars);
    ok &= resolve_one(g_syms.handle, "FPDFText_GetCharBox",     &g_syms.text_get_char_box);
    ok &= resolve_one(g_syms.handle, "FPDFText_GetCharIndexAtPos",
                                                                 &g_syms.text_get_char_at_pos);
    ok &= resolve_one(g_syms.handle, "FPDFText_CountRects",     &g_syms.text_count_rects);
    ok &= resolve_one(g_syms.handle, "FPDFText_GetRect",        &g_syms.text_get_rect);
    ok &= resolve_one(g_syms.handle, "FPDFText_GetText",        &g_syms.text_get_text);
    ok &= resolve_one(g_syms.handle, "FPDFText_GetBoundedText", &g_syms.text_get_bounded_text);
    g_resolve_ok = ok;
    if (ok) ALOGI("Pdfium FPDFText symbols resolved");
}

bool ensure_resolved() {
    std::call_once(g_resolve_once, resolve_symbols);
    return g_resolve_ok;
}

jobject new_rect_f(JNIEnv* env, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    jclass cls = env->FindClass("android/graphics/RectF");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFF)V");
    return env->NewObject(cls, ctor, left, top, right, bottom);
}

}  // namespace

extern "C" {

#define JNI_BRIDGE(name) \
    JNIEXPORT JNICALL Java_com_riffle_core_pdfium_text_PdfiumTextApi_##name

// --- Symbol resolution ------------------------------------------------------

JNIEXPORT jboolean JNI_BRIDGE(nativeEnsureResolved)(JNIEnv*, jobject) {
    return ensure_resolved() ? JNI_TRUE : JNI_FALSE;
}

// --- Document / page lifecycle ---------------------------------------------

JNIEXPORT jlong JNI_BRIDGE(nativeOpenDocument)(JNIEnv* env, jobject,
                                               jstring jpath, jstring jpassword) {
    if (!ensure_resolved()) return 0L;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* password = jpassword
        ? env->GetStringUTFChars(jpassword, nullptr) : nullptr;
    FPDF_DOCUMENT doc = g_syms.load_document(path, password);
    env->ReleaseStringUTFChars(jpath, path);
    if (jpassword) env->ReleaseStringUTFChars(jpassword, password);
    return reinterpret_cast<jlong>(doc);
}

JNIEXPORT void JNI_BRIDGE(nativeCloseDocument)(JNIEnv*, jobject, jlong docPtr) {
    if (!ensure_resolved() || docPtr == 0L) return;
    g_syms.close_document(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
}

JNIEXPORT jint JNI_BRIDGE(nativeGetPageCount)(JNIEnv*, jobject, jlong docPtr) {
    if (!ensure_resolved() || docPtr == 0L) return 0;
    return g_syms.get_page_count(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
}

JNIEXPORT jlong JNI_BRIDGE(nativeOpenPage)(JNIEnv*, jobject,
                                           jlong docPtr, jint pageIndex) {
    if (!ensure_resolved() || docPtr == 0L) return 0L;
    FPDF_PAGE page = g_syms.load_page(
        reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex);
    return reinterpret_cast<jlong>(page);
}

JNIEXPORT void JNI_BRIDGE(nativeClosePage)(JNIEnv*, jobject, jlong pagePtr) {
    if (!ensure_resolved() || pagePtr == 0L) return;
    g_syms.close_page(reinterpret_cast<FPDF_PAGE>(pagePtr));
}

JNIEXPORT jdouble JNI_BRIDGE(nativeGetPageWidth)(JNIEnv*, jobject, jlong pagePtr) {
    if (!ensure_resolved() || pagePtr == 0L) return 0.0;
    return g_syms.get_page_width(reinterpret_cast<FPDF_PAGE>(pagePtr));
}

JNIEXPORT jdouble JNI_BRIDGE(nativeGetPageHeight)(JNIEnv*, jobject, jlong pagePtr) {
    if (!ensure_resolved() || pagePtr == 0L) return 0.0;
    return g_syms.get_page_height(reinterpret_cast<FPDF_PAGE>(pagePtr));
}

// --- Text page lifecycle ----------------------------------------------------

JNIEXPORT jlong JNI_BRIDGE(nativeOpenTextPage)(JNIEnv*, jobject, jlong pagePtr) {
    if (!ensure_resolved() || pagePtr == 0L) return 0L;
    return reinterpret_cast<jlong>(
        g_syms.text_load_page(reinterpret_cast<FPDF_PAGE>(pagePtr)));
}

JNIEXPORT void JNI_BRIDGE(nativeCloseTextPage)(JNIEnv*, jobject, jlong textPagePtr) {
    if (!ensure_resolved() || textPagePtr == 0L) return;
    g_syms.text_close_page(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr));
}

// --- Char & rect queries ----------------------------------------------------

JNIEXPORT jint JNI_BRIDGE(nativeCountChars)(JNIEnv*, jobject, jlong textPagePtr) {
    if (!ensure_resolved() || textPagePtr == 0L) return 0;
    return g_syms.text_count_chars(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr));
}

JNIEXPORT jobject JNI_BRIDGE(nativeGetCharBox)(JNIEnv* env, jobject,
                                               jlong textPagePtr, jint charIndex) {
    if (!ensure_resolved() || textPagePtr == 0L) return nullptr;
    double left, right, bottom, top;
    if (!g_syms.text_get_char_box(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr),
                                  charIndex, &left, &right, &bottom, &top)) {
        return nullptr;
    }
    return new_rect_f(env,
        static_cast<jfloat>(left),
        static_cast<jfloat>(top),
        static_cast<jfloat>(right),
        static_cast<jfloat>(bottom));
}

JNIEXPORT jint JNI_BRIDGE(nativeGetCharIndexAtPos)(JNIEnv*, jobject,
        jlong textPagePtr, jdouble x, jdouble y, jdouble tolX, jdouble tolY) {
    if (!ensure_resolved() || textPagePtr == 0L) return -1;
    return g_syms.text_get_char_at_pos(
        reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), x, y, tolX, tolY);
}

JNIEXPORT jint JNI_BRIDGE(nativeCountRects)(JNIEnv*, jobject,
        jlong textPagePtr, jint startIndex, jint count) {
    if (!ensure_resolved() || textPagePtr == 0L) return 0;
    return g_syms.text_count_rects(
        reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), startIndex, count);
}

JNIEXPORT jobject JNI_BRIDGE(nativeGetRect)(JNIEnv* env, jobject,
        jlong textPagePtr, jint rectIndex) {
    if (!ensure_resolved() || textPagePtr == 0L) return nullptr;
    double left, top, right, bottom;
    if (!g_syms.text_get_rect(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr),
                              rectIndex, &left, &top, &right, &bottom)) {
        return nullptr;
    }
    return new_rect_f(env,
        static_cast<jfloat>(left),
        static_cast<jfloat>(top),
        static_cast<jfloat>(right),
        static_cast<jfloat>(bottom));
}

// --- Text extraction --------------------------------------------------------

JNIEXPORT jstring JNI_BRIDGE(nativeGetText)(JNIEnv* env, jobject,
        jlong textPagePtr, jint startIndex, jint count) {
    if (!ensure_resolved() || textPagePtr == 0L || count <= 0) {
        return env->NewStringUTF("");
    }
    // FPDFText_GetText needs space for `count + 1` to include the trailing NUL.
    auto buf = new FPDF_WCHAR[count + 1];
    int written = g_syms.text_get_text(
        reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), startIndex, count, buf);
    // `written` includes the trailing NUL on success. Use jchar (UTF-16) ctor.
    jstring out = env->NewString(
        reinterpret_cast<const jchar*>(buf),
        static_cast<jsize>(written > 0 ? written - 1 : 0));
    delete[] buf;
    return out;
}

JNIEXPORT jstring JNI_BRIDGE(nativeGetBoundedText)(JNIEnv* env, jobject,
        jlong textPagePtr,
        jdouble left, jdouble top, jdouble right, jdouble bottom) {
    if (!ensure_resolved() || textPagePtr == 0L) return env->NewStringUTF("");
    // First call with null buffer to ask for required length.
    int needed = g_syms.text_get_bounded_text(
        reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr),
        left, top, right, bottom, nullptr, 0);
    if (needed <= 0) return env->NewStringUTF("");
    auto buf = new FPDF_WCHAR[needed];
    int written = g_syms.text_get_bounded_text(
        reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr),
        left, top, right, bottom, buf, needed);
    jstring out = env->NewString(
        reinterpret_cast<const jchar*>(buf), static_cast<jsize>(written));
    delete[] buf;
    return out;
}

}  // extern "C"
