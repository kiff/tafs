# Release rules for R8. The libraries' own rules (kotlinx.serialization, Firebase, Play Billing,
# AndroidX) come with them. These keep what they can't see.

# KIFF's receipt check (kiff/ReceiptCheck.kt) reads secp256k1 from CustomNamedCurves by name.
# Keep the curves it builds from, and the classes the check uses.
-keep class org.bouncycastle.math.ec.custom.sec.** { *; }
-keep class org.bouncycastle.crypto.ec.CustomNamedCurves { *; }
-keep class org.bouncycastle.crypto.digests.KeccakDigest { *; }
-keep class org.bouncycastle.math.ec.ECAlgorithms { *; }
-keep class org.bouncycastle.asn1.x9.X9IntegerConverter { *; }
-dontwarn org.bouncycastle.**

# The app's @Serializable types: kept with their generated serializers, so each class
# still finds its serializer at run time.
-keepclassmembers @kotlinx.serialization.Serializable class bot.tafs.app.** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class bot.tafs.app.**$$serializer { *; }

# Stack traces in Play's crash reports, readable with the build's mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
