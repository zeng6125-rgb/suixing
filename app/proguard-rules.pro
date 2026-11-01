# Shizuku's `newProcess` is reached by reflection (see capability/Shell.kt) and the library is what
# the provider and its binder talk to, so none of it may be renamed or stripped.
-keep class rikka.shizuku.** { *; }

-keep class kotlinx.serialization.** { *; }

# kotlinx.serialization 按 **companion 上的 serializer() 访问器** 取序列化器，所以 R8 必须：
# 保留 `serializer()` 的**名字**、保留 companion 字段、不把 `$$serializer` 类整个删掉。
# 下面这组是库官方文档给的规则。
#
# 注意它们**不保证类名不变**（实测：`AppSettings$$serializer -> ng`）。真正被保住的是成员名：
#   · `serializer()` 名字保留（mapping 里可见 `serializer():147:147 -> serializer`）；
#   · companion / INSTANCE 字段不被删（名字可以改，Kotlin 侧是静态引用）。
# 所以"R8 不会改名 $$serializer"是错的表述 —— 会改，只是不影响解析。
#
# 这些规则从 0.8.614 起**真的生效**（isMinifyEnabled = true）。它们挡的是运行期才暴露的
# SerializationException：单测跑的是未混淆类，拦不住。改动后必须真机冒烟。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# @Serializable and @Polymorphic are read at runtime by polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontwarn org.jetbrains.annotations.**
