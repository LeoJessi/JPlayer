# JPlayer-java 核心模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类和方法
-keep class top.jessi.videoplayer.player.** { *; }
-keep class top.jessi.videoplayer.controller.** { *; }
-keep class top.jessi.videoplayer.render.** { *; }
-keep class top.jessi.videoplayer.media.** { *; }

# 保留接口
-keep interface top.jessi.videoplayer.controller.** { *; }
-keep interface top.jessi.videoplayer.render.** { *; }

# 保留抽象类
-keep class top.jessi.videoplayer.player.AbstractPlayer { *; }
-keep class top.jessi.videoplayer.player.AbstractPlayer$* { *; }

# 保留枚举
-keep enum top.jessi.videoplayer.** { *; }

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
