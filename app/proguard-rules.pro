# 模块入口类由 META-INF/xposed/java_init.list 声明，必须保留（框架按名字实例化它）。
-keep class com.lookie.opluswubi.WubiModule { *; }

# 反射访问目标 App 的成员，保留我们自己的实现类与内部类名（便于排查）
-keep class com.lookie.opluswubi.** { *; }

# ⚠️ 这里**故意不 keep** `SourceFile` / `LineNumberTable`：正式版不带行号表与源文件名。
# 只保留 Kotlin 反射/序列化真正需要的属性。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 关掉对 kotlin.Metadata 的告警
-dontwarn kotlin.**
-dontwarn androidx.**
-dontwarn io.github.libxposed.**
