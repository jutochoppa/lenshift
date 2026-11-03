# Lenshift ProGuard rules

# Keep the voice-interaction entry points referenced only from the manifest/metadata XML.
-keep class dev.lenshift.app.assist.** { *; }
-keep class dev.lenshift.app.capture.AccessibilityCaptureService { *; }
-keep class dev.lenshift.app.ui.CaptureActivity { *; }

# Tesseract4Android uses JNI.
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**

# AndroidMath: FreeType is reached through JNI by class and method name.
-keep class com.pvporbit.freetype.** { *; }
-keep class com.agog.mathdisplay.** { *; }
-dontwarn com.agog.mathdisplay.**
