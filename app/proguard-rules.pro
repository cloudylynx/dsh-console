# ============ 基础保留 ============
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.view.View
-keep class io.github.dshconsole.app.** { *; }

# ============ ★修复闪退：保留 Material / androidx 视图类 ============
# R8 会裁掉"只通过代码 new 出来"的 View 类
-keep class com.google.android.material.** { *; }
-keep class androidx.swiperefreshlayout.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.recyclerview.** { *; }

# Material 组件的构造方法（反射/动态创建时需要）
-keepclassmembers class com.google.android.material.** {
    public <init>(...);
}
-keepclassmembers class androidx.swiperefreshlayout.** {
    public <init>(...);
}

# BadgeDrawable 相关
-keep class com.google.android.material.badge.** { *; }
-keepclassmembers class * {
    com.google.android.material.badge.BadgeDrawable *;
}

# DynamicColors（莫奈取色）
-keep class com.google.android.material.color.** { *; }

# ============ WebView JS 接口 ============
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ============ 保留行号（崩溃日志可读）============
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============ 不警告 ============
-dontwarn com.google.android.material.**
-dontwarn androidx.**
