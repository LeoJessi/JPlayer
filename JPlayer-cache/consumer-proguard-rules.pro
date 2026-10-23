# JPlayer-videocache 视频缓存模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类
-keep class top.jessi.videoplayer.cache.** { *; }

# 保留接口
-keep interface top.jessi.videoplayer.cache.** { *; }
-keep interface top.jessi.videoplayer.cache.source.** { *; }
-keep interface top.jessi.videoplayer.cache.cache.** { *; }
-keep interface top.jessi.videoplayer.cache.internal.** { *; }
-keep interface top.jessi.videoplayer.cache.file.** { *; }
-keep interface top.jessi.videoplayer.cache.headers.** { *; }
-keep interface top.jessi.videoplayer.cache.sourcestorage.** { *; }

# 保留 preload 预加载相关类
-keep class top.jessi.videoplayer.cache.preload.** { *; }

# 保留异常类
-keep class top.jessi.videoplayer.cache.ProxyCacheException { *; }
-keep class top.jessi.videoplayer.cache.InterruptedProxyCacheException { *; }

# 保留Serializable类
-keep class top.jessi.videoplayer.cache.file.Resolution { *; }

# 保留HTTP相关类名（可能被反射使用）
-keepnames class top.jessi.videoplayer.cache.HttpProxyCacheServer { *; }

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
