# Fancy AI — R8 keep rules.
# JavaScript calls only these explicit terminal bridge methods.
-keepclassmembers class com.mrj.fancyai.ui.terminal.TerminalView$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers class com.mrj.fancyai.ui.shell.HomeApp { <fields>; }
# Navigation content keys use the destination enum names.
-keepclassmembers enum com.mrj.fancyai.ui.shell.HomeDestination { <fields>; }

# Preserve requested-image mode at the engine boundary.
-keepclassmembers class com.mrj.fancyai.service.llm.LlmInput { <fields>; }

# Preserve serialized chat/engine inputs, memory relationships, lorebooks, phone, Aura, and character models.
-keepclassmembers @kotlinx.serialization.Serializable class com.mrj.fancyai.** {
    <fields>;
}

# JNI reads configuration fields by name, constructs result objects, and invokes callbacks.
-keep class com.k2fsa.sherpa.onnx.*Config { *; }
-keep class com.k2fsa.sherpa.onnx.Offline* { *; }
-keep class com.k2fsa.sherpa.onnx.Online* { *; }
-keep class com.k2fsa.sherpa.onnx.GenerationConfig { *; }
-keep class com.k2fsa.sherpa.onnx.GeneratedAudio { *; }
-keep class com.k2fsa.sherpa.onnx.WaveData { *; }
-keep class com.k2fsa.sherpa.onnx.WaveReader { *; }
# EndpointConfig.rule1..rule3 hold EndpointRule objects; native code resolves the
# class and mustContainNonSilence/minTrailingSilence/minUtteranceLength by name
# while building the streaming recognizer from its defaulted endpoint config.
-keep class com.k2fsa.sherpa.onnx.EndpointRule { *; }

# Keep native loading, explicit sampler selection, generation, and its streaming callback.
-keepclasseswithmembernames,includedescriptorclasses class com.mrj.fancyai.engine.LlamaRuntime {
    native <methods>;
}
-keepclassmembers class * implements com.mrj.fancyai.engine.LlamaRuntime$NativeChunkReceiver {
    public void onChunk(byte[], int);
}

# MNN streams UTF-8 through JNI callbacks.
-keepclassmembers class * implements com.mrj.fancyai.engine.MnnRuntime$NativeChunkReceiver {
    public void onChunk(byte[], boolean);
}

# DiT Hexagon diffusion JNI entry points.
-keepclasseswithmembernames,includedescriptorclasses class com.mrj.fancyai.sd.DitDiffusion {
    native <methods>;
}

# LiteRT-LM runtime, configuration reflection, and JNI callbacks.
# liblitertlm_jni.so looks every configuration getter up by name from native code
# (GetMethodID for getThinkingTokenBudget, getEnableResponseFormat, getLoraConfig, ...),
# so obfuscating or shrinking any LiteRT-LM member aborts the engine process with
# "JNI DETECTED ERROR IN APPLICATION: mid == null".
-keep class com.google.ai.edge.litertlm.** { *; }

# Preserve social post, thought-process, and image-draft fields in release builds.
-keepclassmembers class com.mrj.fancyai.ui.social.SocialComment { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.rebbit.RebbitPost { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.rebbit.RebbitDraft { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.dare.DarePost { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.dare.DareDraft { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.ustagram.UstagramPost { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.ustagram.UstagramDraft { <fields>; }

-keepclassmembers class com.mrj.fancyai.ui.y.YPost { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.y.YDraft { <fields>; }

# Saved Chat prompt fields.
-keepclassmembers class com.mrj.fancyai.ui.settings.SystemPrompt {
    <fields>;
}

# Mmap and independent K/V cache selections recorded in benchmark exports.
-keepclassmembers class com.mrj.fancyai.ui.benchmark.BenchmarkConfiguration { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.benchmark.BenchmarkRun { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.benchmark.BenchmarkPass { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.benchmark.ComparisonMetric { <fields>; }
-keepclassmembers class com.mrj.fancyai.engine.LlamaEngineConfig { <fields>; }

# Preserve the added sampler-selection fields across the settings and IPC boundary.
-keepclassmembers class com.mrj.fancyai.ui.settings.GenerationSettings { <fields>; }
-keepclassmembers class com.mrj.fancyai.service.llm.LlmSessionConfig { <fields>; }
-keepclassmembers class com.mrj.fancyai.engine.LlamaGenerationOptions { <fields>; }
-keepclassmembers class com.mrj.fancyai.engine.LiteRtGenerationOptions { <fields>; }

# Resolved context carried by benchmark metrics across the engine boundary.
-keepclassmembers class com.mrj.fancyai.engine.LocalGenerationMetrics { <fields>; }
-keepclassmembers class com.mrj.fancyai.service.llm.LlmPerformanceMetrics { <fields>; }

# Native load/convert signatures now carry Android Context into signed ELF verification.
-keepclasseswithmembernames,includedescriptorclasses class com.mrj.fancyai.engine.MnnRuntime {
    native <methods>;
}
-keepclasseswithmembernames class com.mrj.fancyai.terminal.NativePty {
    native <methods>;
}
# Includes MnnUpscaler.nativeLoad(Context, String, int backend, int tileSide).
-keepclasseswithmembernames,includedescriptorclasses class com.mrj.fancyai.sd.** {
    native <methods>;
}

# Editable refinement settings passed from Aura to image generation.
-keepclassmembers class com.mrj.fancyai.ui.aura.AuraLocalGenerationConfig { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.aura.AuraRemoteGenerationConfig { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.aura.AuraState { <fields>; }

# Reasoning stays separate from group, game, and phone message content.
-keepclassmembers class com.mrj.fancyai.ui.groups.GroupMessage { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.games.GameMessage { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.phone.PhoneTurn { <fields>; }

-keepclassmembers class com.mrj.fancyai.service.llm.AssistantTurn { <fields>; }
-keepclassmembers class com.mrj.fancyai.ui.chat.ChatTurn { <fields>; }

# ONNX Runtime resolves native handles and result types by name from JNI.
-keep class ai.onnxruntime.** { *; }

# Remote image provider identity is persisted in preferences and image metadata.
-keepclassmembers enum com.mrj.fancyai.ui.aura.AuraEngine { *; }

# Apache Commons Compress (tar/bz2 extraction for voice models)
-keep class org.apache.commons.compress.archivers.tar.TarArchiveInputStream {
    public <init>(java.io.InputStream);
    public org.apache.commons.compress.archivers.tar.TarArchiveEntry getNextEntry();
    public int read(byte[], int, int);
    public void close();
}
-keep class org.apache.commons.compress.archivers.tar.TarArchiveEntry {
    public java.lang.String getName();
    public boolean isDirectory();
    public boolean isFile();
    public boolean isSymbolicLink();
    public boolean isLink();
    public long getSize();
}
-keep class org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream {
    public <init>(java.io.InputStream);
    public int read(byte[], int, int);
    public long getCompressedCount();
    public void close();
}
-keep class org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream$Data { *; }
-dontwarn org.apache.commons.compress.**
