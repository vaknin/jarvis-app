# uniffi's Kotlin bindings reach the Rust library through JNA, which reflects on Structure fields,
# callback interfaces and the library interface; keep all of it.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.kivan.jarvis.core.** { *; }
-dontwarn java.awt.**
-keep class com.kivan.jarvis.Native { native <methods>; }
