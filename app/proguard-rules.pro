# R8 rules for the release build type.
#
# Gson, Jackson, Room, protobuf, and Koin reflect on app and vendored
# wallet types, so those packages are kept. R8 still removes unused
# library code outside this set.
#
# AGP 8.7's R8 warns that it cannot parse Kotlin 2.2 metadata. These
# keeps hold the app and wallet packages regardless of that warning.

-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Exceptions

-keep class com.samourai.** { *; }
-keep interface com.samourai.** { *; }
-keep class com.invertedx.** { *; }
-keep class org.bitcoinj.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class io.matthewnelson.** { *; }
-keep class com.sparrowwallet.** { *; }
-keep class com.lambdaworks.** { *; }

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Desktop-only references from transitive jars (H2, animal-sniffer, Conscrypt).
-dontwarn org.h2.**
-dontwarn javax.servlet.**
-dontwarn javax.naming.**
-dontwarn javax.transaction.**
-dontwarn org.ietf.jgss.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.joda.time.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.impl.**
-dontwarn com.google.errorprone.**
-dontwarn javax.money.**
-dontwarn org.javamoney.**

# Android has no JMX or java.lang.management. Logback and kmp-process
# reference them only from optional paths.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.management.InstanceNotFoundException
-dontwarn javax.management.MBeanRegistrationException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.MalformedObjectNameException
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn org.codehaus.janino.ClassBodyEvaluator
-dontwarn sun.reflect.Reflection
