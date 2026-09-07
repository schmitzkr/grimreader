# kotlinx.serialization: keep serializers and the @Serializable classes' metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.schmitzkr.grimreader.**$$serializer { *; }
-keepclassmembers class com.schmitzkr.grimreader.** { *** Companion; }
-keepclasseswithmembers class com.schmitzkr.grimreader.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit keeps generic signatures for its interface methods.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# The EPUB reader's WebView calls these from JS by (reflection-visible) name;
# a minified release build can otherwise rename or strip them silently.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
