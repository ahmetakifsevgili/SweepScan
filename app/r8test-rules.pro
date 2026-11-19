# Only for the r8test build: the test APK uses Kotlin and AndroidX from the app under test, so
# those stay whole. The libraries that matter at run time (ML Kit, OpenCV, zxing-cpp, CameraX)
# are shrunk exactly as in the release build.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn **
