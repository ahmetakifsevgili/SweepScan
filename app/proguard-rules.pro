# R8 for the release build (and the r8test build the device tests run against with -Pr8test).
#
# SweepScan's own code uses no reflection; it is kept whole anyway so stack traces in the field log
# stay readable and device tests can call into it. The savings come from the libraries.
-keep class com.sweepscan.** { *; }
-keepattributes SourceFile,LineNumberTable

# OpenCV ships no rules: its native code creates and calls org.opencv classes by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# zxing-cpp and ML Kit bring their own consumer rules (-keep class zxingcpp.** …).

# Filament: its JNI code creates and calls Java objects (callbacks, buffers) by name.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
