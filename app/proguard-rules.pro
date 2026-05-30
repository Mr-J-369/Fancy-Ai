# Fancy AI — R8 keep rules.
# Obfuscation is on for release; the rules below protect the things R8 can't see are
# referenced by name (JNI, reflection-based JSON, WorkManager) so the release build behaves
# identically to debug. Everything not kept here is shrunk/renamed.

# ---- Attributes needed for Gson generics, annotations and good crash reports ----
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- JNI bridge to llama.cpp ----
# Native methods resolve by the symbol Java_com_mrj_fancyai_LlamaInference_*, and the native
# code calls back into onToken/onDone via GetStaticMethodID. Renaming any of this breaks linkage.
-keep class com.mrj.fancyai.LlamaInference { *; }
-keep class com.mrj.fancyai.LlamaInference$StreamBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ---- Gson models (serialized by field name via reflection) ----
# Room entities double as the backup DTOs, and the imaging/cloud DTOs go through Gson.
-keep class com.mrj.fancyai.data.db.entity.** { *; }
-keep class com.mrj.fancyai.data.model.** { *; }
-keep class com.mrj.fancyai.service.ChatMessage { *; }
-keep class com.mrj.fancyai.service.ForgeApi$* { *; }
-keep class com.mrj.fancyai.service.LocalDreamApi$* { *; }
-keep class com.mrj.fancyai.service.BackupService$* { *; }
# Don't let R8 strip fields that are read/written reflectively by Gson.
-keepclassmembers class com.mrj.fancyai.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- WorkManager ----
# Workers are instantiated reflectively by class name from the WorkManager DB.
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.mrj.fancyai.service.SocialWorker { *; }

# ---- Retrofit / OkHttp (mostly covered by their bundled rules; belt and suspenders) ----
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

# ---- Kotlin / coroutines metadata ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }
