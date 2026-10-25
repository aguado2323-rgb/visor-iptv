# libVLC usa JNI y reflexión
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.visor.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.visor.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Sin trazas de depuración en la versión final
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
