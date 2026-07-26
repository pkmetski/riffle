-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Readium
-keep class org.readium.** { *; }

# ACRA
-keep class org.acra.** { *; }
-keepnames class * implements org.acra.collector.CrashReportData

# OkHttp 5.x removed okhttp3.internal.Util; ktor-client-okhttp still depends on okhttp-sse:4.x
# which references it. Suppress the missing-class R8 warning so the release build succeeds.
-dontwarn okhttp3.internal.**
