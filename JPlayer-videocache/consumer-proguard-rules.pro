# JPlayer-videocache 视频缓存模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类
-keep class com.danikula.videocache.** { *; }

# 保留接口
-keep interface com.danikula.videocache.** { *; }
-keep interface com.danikula.videocache.file.** { *; }
-keep interface com.danikula.videocache.headers.** { *; }
-keep interface com.danikula.videocache.sourcestorage.** { *; }

# 保留异常类
-keep class com.danikula.videocache.ProxyCacheException { *; }
-keep class com.danikula.videocache.InterruptedProxyCacheException { *; }

# 保留Serializable类
-keep class com.danikula.videocache.file.Resolution { *; }

# 保留HTTP相关类名（可能被反射使用）
-keepnames class com.danikula.videocache.HttpProxyCacheServer { *; }

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
