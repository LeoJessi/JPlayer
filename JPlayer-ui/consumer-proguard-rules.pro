# JPlayer-ui UI组件模块混淆规则
# 作为consumer配置，使用方会自动合并这些规则

# 保留所有公共API类
-keep class top.jessi.videocontroller.** { *; }

# 保留接口
-keep interface top.jessi.videocontroller.** { *; }

# 保留带注解的View属性（用于XML布局解析）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留自定义View的构造方法（用于XML布局inflate）
-keepclassmembers class top.jessi.videocontroller.component.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
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
