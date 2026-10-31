# JARVIS Stage 8 Production R8 / ProGuard Hardening Rules

# Keep Retrofit & OkHttp interfaces and annotations
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep Moshi DTOs and Kotlin reflect metadata
-keep class kotlin.Metadata { *; }
-keep class com.example.data.remote.** { *; }
-keep class com.example.domain.model.** { *; }
-keep class com.example.domain.tool.** { *; }

# Keep Room Entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Android Services & BroadcastReceivers
-keep class com.example.services.** { *; }

# Keep Firebase Auth & App Check classes
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Strip verbose debug logs in release builds while preserving error logs
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
