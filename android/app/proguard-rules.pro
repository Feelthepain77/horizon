# Keep MSAL internals (reflection-heavy).
-keep class com.microsoft.** { *; }

# JNI natives are looked up by name from Rust.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin metadata for MSAL's reflection paths.
-keep class kotlin.Metadata { *; }
-dontwarn org.slf4j.**
