-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-keep,includedescriptorclasses class com.tankful.android.**$$serializer { *; }
-keepclassmembers class com.tankful.android.** {
    *** Companion;
}

-keep class fr.acinq.secp256k1.** { *; }
-keep class androidx.room.** { *; }
