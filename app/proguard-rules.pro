# ============================================================================
# Anyfy ProGuard / R8 Optimization & Obfuscation Rules
# ============================================================================

# General attributes preservation
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-repackageclasses ''
-allowaccessmodification

# ----------------------------------------------------------------------------
# 1. On-Device yt-dlp & Python Runtime
# ----------------------------------------------------------------------------
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**

# ----------------------------------------------------------------------------
# 2. QuickJS JavaScript Engine (Dokar3)
# ----------------------------------------------------------------------------
-keep class io.github.dokar3.quickjs.** { *; }
-dontwarn io.github.dokar3.quickjs.**

# ----------------------------------------------------------------------------
# 3. NewPipe / PipePipeExtractor / BravePipeExtractor / Jsoup
# ----------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ----------------------------------------------------------------------------
# 4. Moshi JSON Serialization & Retrofit
# ----------------------------------------------------------------------------
-dontwarn com.squareup.moshi.**
-keep class com.squareup.moshi.** { *; }
-keep class * extends com.squareup.moshi.JsonAdapter
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# ----------------------------------------------------------------------------
# 5. Room Database Entities and DAOs
# ----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.entity.** { *; }
-keep class com.example.data.local.dao.** { *; }
-dontwarn androidx.room.paging.**

# ----------------------------------------------------------------------------
# 6. Socket.IO & Realtime Networking
# ----------------------------------------------------------------------------
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ----------------------------------------------------------------------------
# 7. Media3 / ExoPlayer Audio Engine & Caching
# ----------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-keep class com.example.service.cache.** { *; }
-dontwarn androidx.media3.**

# ----------------------------------------------------------------------------
# 8. Coil Image Loading
# ----------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# ----------------------------------------------------------------------------
# 9. Anyfy Domain Models, State & Serializable Data Transfer Objects
# ----------------------------------------------------------------------------
-keep class com.example.model.** { *; }
-keep class com.example.service.importer.model.** { *; }
-keep class com.example.service.update.model.** { *; }
-keep class com.example.service.sponsorblock.** { *; }
-keep class com.example.service.integrations.ytmusic.** { *; }
-keep class com.example.service.lyrics.** { *; }
-keep class com.example.service.diagnostics.** { *; }
-keep class com.example.viewmodel.** { *; }

# ----------------------------------------------------------------------------
# 10. Coroutines & Desugaring
# ----------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn java.lang.invoke.**

# ----------------------------------------------------------------------------
# 11. PipePipe / Rhino / Extractor JDK Desktop fallbacks
# ----------------------------------------------------------------------------
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**

