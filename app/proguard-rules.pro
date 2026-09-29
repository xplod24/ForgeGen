# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------------------------------------
# 3.4.1: the APK published on GitHub is shrunk and optimized by R8 (-Pforgegen.publish, see app/build.gradle.kts).

# Names stay readable: crash logs, the out-of-memory report and the debug mode's log show the real classes and methods.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Gson reads and writes the app's own classes (settings, presets, the saved queue, the server's answers) by their
# field names, so R8 must not drop fields it sees written but never read, and keeps the constructors: an older save
# without a new field gets its default from the no-argument constructor Kotlin makes for classes with all defaults.
-keep class com.example.forgegen.** { <fields>; <init>(...); }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * extends com.google.gson.reflect.TypeToken
