# JPlayer-exo ExoPlayer模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类
-keep class top.jessi.videoplayer.exo.** { *; }

# 保留接口
-keep interface top.jessi.videoplayer.exo.** { *; }

# Media3 / ExoPlayer 相关
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# OkHttp（ExoPlayer数据源使用）
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# 保留注解
-keepattributes *Annotation*

# 保留泛型签名
-keepattributes Signature

# 保留异常信息
-keepattributes Exceptions

# 保留内部类
-keepattributes InnerClasses

# 保留行号信息（便于调试）
-keepattributes SourceFile,LineNumberTable
