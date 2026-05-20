-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Readium
-keep class org.readium.** { *; }

# ACRA
-keep class org.acra.** { *; }
-keepnames class * implements org.acra.collector.CrashReportData
