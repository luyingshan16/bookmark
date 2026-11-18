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

# Preserve the API's JSON keys and DTOs instantiated through Gson reflection.
-keep,allowoptimization,allowobfuscation class com.personal.bookmark_app.data.remote.dto.**
-keepclassmembers,allowoptimization class com.personal.bookmark_app.data.remote.dto.** {
    <init>();
    !static !transient <fields>;
}
-keep,allowoptimization,allowobfuscation class com.personal.bookmark_app.data.remote.api.*Request,com.personal.bookmark_app.data.remote.api.*Response,com.personal.bookmark_app.data.remote.api.*Status,com.personal.bookmark_app.data.remote.api.*Result
-keepclassmembers,allowoptimization class com.personal.bookmark_app.data.remote.api.*Request,com.personal.bookmark_app.data.remote.api.*Response,com.personal.bookmark_app.data.remote.api.*Status,com.personal.bookmark_app.data.remote.api.*Result {
    <init>();
    !static !transient <fields>;
}
