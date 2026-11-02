# R8 rules for the published release variant (minified and resource-shrunk since 2026-10-30).
# The fold variant deployed for tests stays unminified on purpose (ART verifier), see app/build.gradle.kts.

# Protobuf: the runtime reflects on descriptors, extensions and generated messages when parsing the robot frames.
-keep class com.google.protobuf.** { *; }
-keep class fr.luba.poc.proto.** { *; }
-dontwarn com.google.protobuf.**

-dontwarn javax.naming.**

# Crash reports: keep the line numbers and the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# @JavascriptInterface methods of the camera bridge are kept by the default Android rules (proguard-android-optimize).
