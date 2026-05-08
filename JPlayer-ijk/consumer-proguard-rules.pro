# JPlayer-ijk IJKPlayer模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类
-keep class top.jessi.videoplayer.ijk.** { *; }

# 保留接口
-keep interface top.jessi.videoplayer.ijk.** { *; }

# IJKPlayer native相关
-keep class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**

# 保留native方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留native invoke回调方法
-keepclassmembers class * {
    public void onNativeInvoke(int, android.os.Bundle);
}

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
