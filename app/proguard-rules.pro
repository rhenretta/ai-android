-keepattributes *Annotation*,InnerClasses,Signature
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    static ** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
