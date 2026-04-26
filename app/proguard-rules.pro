-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.stanley.reddittldr.**$$serializer { *; }
-keepclassmembers class com.stanley.reddittldr.** {
    *** Companion;
}
-keepclasseswithmembers class com.stanley.reddittldr.** {
    kotlinx.serialization.KSerializer serializer(...);
}
