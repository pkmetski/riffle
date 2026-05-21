package android.support.v4.util;

/**
 * Stub satisfying pdfium-android:1.8.2's reference to android.support.v4.util.ArrayMap.
 * com.android.support is globally excluded to prevent duplicate-class conflicts with AndroidX,
 * but pdfium-android:1.8.2's PdfDocument uses only new ArrayMap() — nothing else from
 * support-v4. We provide this thin subclass of the AndroidX equivalent so pdfium loads.
 */
public class ArrayMap<K, V> extends androidx.collection.ArrayMap<K, V> {
}
