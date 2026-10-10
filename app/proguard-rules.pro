# Shizuku's `newProcess` is reached by reflection (see capability/Shell.kt) and the library is what
# the provider and its binder talk to, so none of it may be renamed or stripped.
-keep class rikka.shizuku.** { *; }

-keep class kotlinx.serialization.** { *; }

# kotlinx.serialization looks a class's serializer up at runtime, so R8 must not rename the generated
# companion or `$$serializer`, nor drop the `serializer()` accessor. Without these rules every
# @Serializable model in data/ (19 classes) throws a SerializationException on the first read or
# write — a crash only a minified build can produce. These are the rules from the library's own
# documentation; they change nothing today (isMinifyEnabled = false) and are what would make turning
# it on survivable.
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
