cmake_minimum_required(VERSION 3.22)
project(riffle_pdfium_text LANGUAGES CXX)

# Build a tiny shared library that exposes FPDFText_* Pdfium symbols to Kotlin
# via JNI. We do NOT link against libmodpdfium.so at build time; we resolve
# every Pdfium symbol at runtime via dlopen(RTLD_NOLOAD) + dlsym, against the
# .so that com.github.barteksc:pdfium-android has already loaded into the
# process via System.loadLibrary("modpdfium").
#
# The bridge therefore has no compile-time dependency on Pdfium itself —
# it only needs the public function signatures, which are inline-declared in
# pdfium_text_jni.cpp using stable Pdfium C-ABI types.

add_library(riffle_pdfium_text SHARED src/pdfium_text_jni.cpp)

# The JNIEXPORT macro on each entry point sets default visibility on its own
# symbol; everything else is hidden by -fvisibility=hidden below. This keeps
# the .so footprint minimal and avoids accidentally re-exporting Pdfium
# symbols we dlsym (we want callers to go through dlsym themselves, not
# through us).

target_compile_options(riffle_pdfium_text PRIVATE
    -fvisibility=hidden     # only JNI exports get default visibility via JNIEXPORT
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(riffle_pdfium_text
    PRIVATE ${log-lib}
    PRIVATE ${dl-lib}
)
